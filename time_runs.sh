#!/bin/bash
# Times one ssvchk_variants.py run and appends "service,controller,seconds" to timing_<MODE>.csv
# usage: ./time_runs.sh MODE SERVICE CONTROLLER.java variants/CONTROLLER [extra ssvchk_variants.py args...]
#   MODE = A | B | C   (A: no Tecton flags, B: --inputs corpus.json --input-mode tecton, C: ... both)
# example (C):  ./time_runs.sh C Features_Service /path/ProductsResource.java variants/ProductsResource \
#                  --factory-src factory/ProductsResourceFactory.java --strict
# results go to results_<MODE>/<SERVICE>/<Controller>.csv  (A -> results_A_rerun, B -> results_B, C -> results_final)
# console output is also saved to logs/<MODE>/<Controller>.log
# sum per service:  awk -F, '{t[$1]+=$3} END{for(s in t) printf "%s,%d s (%.1f min)\n", s, t[s], t[s]/60}' timing_C.csv
mode=$1; svc=$2; src=$3; var=${4%/}; shift 4
case $mode in
  A) out=results_A_rerun; flags="";;
  B) out=results_B; flags="--inputs corpus.json --input-mode tecton";;
  C) out=results_final; flags="--inputs corpus.json --input-mode both";;
  D) out=results_detail; flags="--inputs corpus.json --input-mode both";;   # same as C, separate folders/logs/timing: for --detail reruns
  *) echo "MODE must be A, B, C or D"; exit 1;;
esac
mkdir -p "$out/$svc" "logs/$mode"
s=$(date +%s)
python3 -u ssvchk_variants.py "$src" "$var" --csv "$out/$svc/$(basename "$var").csv" $flags "$@" 2>&1 | tee "logs/$mode/$(basename "$var").log"
e=$(date +%s)
echo "$svc,$(basename "$var"),$((e-s))" >> "timing_$mode.csv"
echo "[time] $(basename "$var") mode $mode: $((e-s)) s"