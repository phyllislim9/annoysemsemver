#!/bin/bash
# Run every controller of one benchmark service in one mode, using time_runs.sh.
# usage: ./run_service.sh MODE SERVICE PARENT_DIR ["extra flags for every controller"] [only-regex]
#   MODE        A | B | C          (see time_runs.sh)
#   SERVICE     name used for results_<A_rerun|B|final>/<SERVICE>/ , e.g. Genome_Nexus
#   PARENT_DIR  folder holding the ORIGINAL <Controller>.java files of that service
#   extra flags e.g. "--include-nonpublic" or "--http-5xx-crash"  (use "" for none)
#   only-regex  optional, run only controllers whose name matches (e.g. 'Annotation|Pdb')
# A controller is run when PARENT_DIR/<Name>.java exists AND variants/<Name>/ exists.
# Factory: factory/<Name>Factory.java is used when it exists. Keeps going after a failure.
mode=$1; svc=$2; parent=${3%/}; extra=${4:-}; only=${5:-.}
[ -d "$parent" ] || { echo "no such folder: $parent"; exit 1; }
case $mode in
  A) outdir=results_A_rerun;;
  B) outdir=results_B;;
  C) outdir=results_final;;
  D) outdir=results_detail;;
  *) echo "MODE must be A, B, C or D"; exit 1;;
esac
ran=0; skipped=0
for f in "$parent"/*.java; do
  c=$(basename "$f" .java)
  [ -d "variants/$c" ] || continue
  echo "$c" | grep -Eq "$only" || continue
  fac=""; [ -f "factory/${c}Factory.java" ] && fac="--factory-src factory/${c}Factory.java"
  if [ -f "$outdir/$svc/$c.csv" ] && [ -z "${FORCE:-}" ]; then
    echo "[skip] $c (result exists; FORCE=1 to redo)"; skipped=$((skipped+1)); continue
  fi
  echo "=== $mode $svc $c"
  ./time_runs.sh "$mode" "$svc" "$f" "variants/$c" $fac --strict $extra || echo "[FAILED] $c"
  ran=$((ran+1))
done
echo "--- $svc mode $mode: ran $ran, skipped $skipped"