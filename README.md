# SSVChk (anonymous artifact)

SSVChk checks whether a declared version increment matches the behavioral difference between two
versions of a class. It reports one of five levels, **Major**, **Minor**, **Patch**, **Crash** or
**Refactor**, which correspond to the five components of a SemSemVer version `major.minor.patch.crash.refactor`.

This repository contains the checker, the generated variants, the final per-variant results and the scripts that produce the tables of the evaluation.

## How it works

1. **Signature analysis** compares the public functions of the two versions. It reports Major if a
   function was removed or its signature changed, and Minor if functions were only added.
2. **Dynamic analysis** runs both versions on the same inputs, with the old version as the oracle, and
   records Fault Introduce, Output Remap and Fault Repair.
3. **Verdict.** The evidence of all functions and inputs is combined, and the first level whose test
   holds decides, in the order Major, Minor, Patch, Crash, Refactor.

The inputs are values generated from the parameter types, plus inputs from an API-specification-based
test generator (Tecton). The inputs used for the reported results are in `corpus.json`.

## Contents

| Path | Purpose |
|---|---|
| `GenericSSVChk.java`, `CorpusInputs.java`, `HttpSSVChk.java` | the checker |
| `factory/` | one factory per class, building the two versions with collaborators |
| `variants/` | the 1,404 variants (36 per class, 39 classes, 9 services) |
| `corpus.json` | test inputs used for the reported results |
| `merge_corpora.py`, `tgen_plan.py`, `tgen_to_corpus.py` | build `corpus.json` from the generator output |
| `ssvchk_variants.py` | scores the variants of one class |
| `run_all_benchmarks.sh`, `run_service.sh`, `time_runs.sh` | run all services and record the time |
| `rq_tables.py`, `detail_extract.py` | build the result tables and the report of the failing variants |
| `results_final/` | per-variant results of the reported run |
| `timing_C.csv` | check time of the reported run |

## Requirements

- JDK 11 (some services also build with JDK 8), Maven, Python 3.9 or later.
- The benchmark services of the evaluation. They are third-party and not included here. Obtain them from
  the public RESTful benchmark repository cited in the paper and set `SEMVER_ROOT` to the folder that
  contains them.
- Only to regenerate the test inputs with Tecton: an API key for the language model in the environment
  variable `DEEPSEEK_API_KEY`.

## Reproduce the tables

Without rerunning anything, from the stored results:

```
python3 rq_tables.py C=results_final
```

This prints the pass/fail counts per level, the expected-versus-reported matrix and the list of failing
variants.

To rerun the checker:

```
export SEMVER_ROOT=/path/to/benchmarks
./run_all_benchmarks.sh                      # all nine services, results in results_final/
ONLY='Genome' ./run_all_benchmarks.sh        # one service
```

The original services are never modified. Compiled output goes to a temporary directory.

## Results

On 1,404 variants, SSVChk reports the expected level for 1,380 (98.3%). Checking all variants takes about
41 minutes in total. The paper describes the setup and the analysis of the failures.
