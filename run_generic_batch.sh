#!/usr/bin/env bash
# run_generic_batch.sh
#
# Runs GenericSSVChk against every v2.java under a generated output directory,
# comparing its verdict to the SemVer severity each class targets. Works on
# ANY benchmark -- package, V1 class name, and V2 class name are all
# auto-detected from the source files, nothing is hardcoded to RBAC/com.test.
#
# NOTE: uses [[:space:]] instead of \s in all sed/grep patterns, because BSD
# sed (macOS default) does not support \s -- only GNU sed does. [[:space:]]
# is POSIX and works identically on both.
#
# Usage:
#   ./run_generic_batch.sh /path/to/YourServiceV1.java /path/to/output_dir
#
# Requires: javac, java on PATH.

set -euo pipefail

V1_SRC="${1:?Usage: $0 <V1_Source.java> <output_dir>}"
OUT_DIR="${2:?Usage: $0 <V1_Source.java> <output_dir>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Workdir: $WORK"

expected_severity() {
  case "$1" in
    1|2|3|4)   echo "Patch" ;;
    5|6)       echo "Minor" ;;
    7|8|9|10|11|12) echo "Major" ;;
    *)         echo "" ;;
  esac
}

map_verdict() {
  case "$1" in
    MAJOR) echo "Major" ;;
    MINOR) echo "Minor" ;;
    PATCH|CRASH_FIX|REFACTOR) echo "Patch" ;;
    *) echo "UNKNOWN" ;;
  esac
}

detect_package() {
  grep -m1 -E '^[[:space:]]*package[[:space:]]+' "$1" \
    | sed -E 's/^[[:space:]]*package[[:space:]]+([a-zA-Z0-9_.]+)[[:space:]]*;.*/\1/'
}

detect_class() {
  grep -m1 -E 'public[[:space:]]+(final[[:space:]]+)?class[[:space:]]+' "$1" \
    | sed -E 's/.*class[[:space:]]+([A-Za-z0-9_]+).*/\1/'
}

# --- Auto-detect package + V1 class name straight from the source file ---
PKG="$(detect_package "$V1_SRC")"
PKG_PATH="${PKG//./\/}"
V1_CLASS="$(detect_class "$V1_SRC")"

if [[ -z "$PKG" || -z "$V1_CLASS" ]]; then
  echo "ERROR: could not auto-detect 'package ...;' or 'public class ...' from $V1_SRC" >&2
  echo "  detected package='$PKG' class='$V1_CLASS'" >&2
  exit 1
fi
echo "Detected package: $PKG   V1 class: $V1_CLASS"

PKG_DIR="$WORK/$PKG_PATH"
mkdir -p "$PKG_DIR"
V1_DIR="$(cd "$(dirname "$V1_SRC")" && pwd)"
cp "$V1_DIR"/*.java "$PKG_DIR/"

cp "$SCRIPT_DIR/GenericSSVChk.java" "$WORK/GenericSSVChk.java"
pushd "$WORK" >/dev/null
javac "$PKG_PATH"/*.java
javac GenericSSVChk.java
popd >/dev/null

TOTAL=0
AGREE=0
declare -a MISMATCHES=()

for v2_file in $(find "$OUT_DIR" -name "v2.java" | sort); do
  cell_dir="$(dirname "$v2_file")"
  class_dir="$(dirname "$cell_dir")"
  class_id="$(basename "$class_dir" | sed -E 's/class_0*([0-9]+)_.*/\1/')"
  expected="$(expected_severity "$class_id")"
  [[ -z "$expected" ]] && continue

  V2_CLASS="$(detect_class "$v2_file")"
  if [[ -z "$V2_CLASS" ]]; then
    echo "  could not detect class name in $v2_file — skipping"
    continue
  fi

  TOTAL=$((TOTAL+1))
  run="$WORK/run_$TOTAL"
  mkdir -p "$run/$PKG_PATH"
  cp "$PKG_DIR"/*.java "$run/$PKG_PATH/"
  cp "$v2_file" "$run/$PKG_PATH/${V2_CLASS}.java"
  cp "$WORK/GenericSSVChk.class" "$run/"

  echo "----------------------------------------------------------------"
  echo "[$TOTAL] $v2_file  (expected: $expected, V2 class: $V2_CLASS)"

  pushd "$run" >/dev/null
  echo "  compiling..."
  javac "$PKG_PATH"/*.java 2>&1 || { echo "  COMPILE FAILED — skipping"; popd >/dev/null; continue; }
  echo "  running checker (this streams [seed]/[probe] progress lines as it works)..."
  set +e
  out=$(java -cp .:.. GenericSSVChk "$PKG.$V1_CLASS" "$PKG.$V2_CLASS" \
        "$PKG_PATH/$V1_CLASS.java" "$PKG_PATH/$V2_CLASS.java")
  set -e
  popd >/dev/null

  verdict=$(echo "$out" | grep -m1 "\[Verdict\]" | sed 's/\[Verdict\] //')
  mapped=$(map_verdict "$verdict")

  echo "  verdict=$verdict  mapped=$mapped"
  if [[ "$mapped" == "$expected" ]]; then
    AGREE=$((AGREE+1))
  else
    MISMATCHES+=("$v2_file  expected=$expected  got=$mapped")
  fi
done

echo "===================================================================="
echo "GENERIC CHECKER SCORE: $AGREE / $TOTAL agree with expected SemVer severities"
if [[ ${#MISMATCHES[@]} -gt 0 ]]; then
  echo ""
  echo "Mismatches (worth reading individually — some may reveal LLM"
  echo "mutation generation that didn't do what the prompt asked for,"
  echo "not checker failure):"
  for m in "${MISMATCHES[@]}"; do echo "  - $m"; done
fi
echo "===================================================================="
