#!/bin/bash
# Run all nine benchmark services in one go.
#   ./run_all_benchmarks.sh            -> configuration C (typed + Tecton)  -> results_final/
#   MODES="A B C" ./run_all_benchmarks.sh   -> A (rerun, for timing), B, C in turn
#   ONLY='Genome|Market' ./run_all_benchmarks.sh   -> only services whose name matches
#   FORCE=1 ./run_all_benchmarks.sh    -> redo controllers that already have a result
# Per controller it calls run_service.sh -> time_runs.sh, so CSV, console log and time are all saved.
# Run it from the ssvchk folder.
set -u
T=/Users/admin/Documents/UK/dev/ssvtest/semver
B=$T/X1_Testing_Code/benchmarks
UM=$B/aratrl-service/jdk8_2/user-management     # the project your A runs used for RBAC/User (not runtime-tests)
MODES=${MODES:-C}
ONLY=${ONLY:-.}
CTRL=${CTRL:-.}              # only controllers whose name matches this regex
EXTRA_ALL=${EXTRA_ALL:-}     # extra flags for every controller, e.g. EXTRA_ALL='--detail class_02|class_07' (use with MODES=D)

# JDK per service (macOS). Blank a variable to leave --java-home out.
JAVA8=${JAVA8:-$(/usr/libexec/java_home -v 1.8 2>/dev/null)}
JAVA11=${JAVA11:-$(/usr/libexec/java_home -v 11 2>/dev/null)}
j8=${JAVA8:+--java-home $JAVA8}
j11=${JAVA11:+--java-home $JAVA11}

# Flags you used for specific services in the A run (edit if yours differed):
YT_EXTRA=${YT_EXTRA:-}            # YouTube: your old --extra-src ... (not recoverable from the history; paste it here)
MARKET_EXTRA=${MARKET_EXTRA:-}    # Market is multi-module: if the plain run cannot build it, put
                                  #   --project <market-rest> --classes <market-rest>/target/classes --cp-file /tmp/market.cp --no-build
                                  # here (the .cp file comes from: mvn -B -DskipTests compile dependency:build-classpath -pl market-rest -am -Dmdep.outputFile=/tmp/market.cp)

# service-name | parent folder of the original controllers | extra flags for every controller there
SERVICES=(
"Genome_Nexus|$B/aratrl-service/jdk8_2/genome-nexus/web/src/main/java/org/cbioportal/genome_nexus/web|$j8"
"Market|$B/aratrl-service/jdk11/market/market-rest/src/main/java/market/rest|$j11 $MARKET_EXTRA"
"Person|$B/aratrl-service/jdk8_2/person-controller/src/main/java/com/mongodb/starter/controllers|$j8"
"User_Management|$UM/src/main/java/com/giassi/microservice/demo2/rest|$j8 --project $UM"
"Features_Service|$B/aratrl-service/jdk8_1/cs/rest/original/features-service/src/main/java/org/javiermf/features/services/rest|$j8"
"YouTube|$B/services/youtube/src/main/java/youtube/api/resources|$j8 $YT_EXTRA"
"Project_Tracking_System|$B/aratrl-service/jdk11/project-tracking-system/src/main/java/com/pfa/pack/controller/api|$j11"
"Language_Tool|$B/aratrl-service/jdk8_1/cs/rest/original/languagetool/languagetool-server/src/main/java/org/languagetool/server|$j8 --include-nonpublic"
"Rest_Countries_V1|$B/aratrl-service/jdk8_1/cs/rest/original/restcountries/src/main/java/eu/fayder/restcountries/v1/rest|$j8 --http-5xx-crash"
"Rest_Countries_V2|$B/aratrl-service/jdk8_1/cs/rest/original/restcountries/src/main/java/eu/fayder/restcountries/v2/rest|$j8 --http-5xx-crash"
)

for mode in $MODES; do
  echo "################ mode $mode ################"
  for row in "${SERVICES[@]}"; do
    IFS='|' read -r svc parent extra <<< "$row"
    echo "$svc" | grep -Eq "$ONLY" || continue
    echo; echo "######## $svc ($mode)"
    ./run_service.sh "$mode" "$svc" "$parent" "$extra $EXTRA_ALL" "$CTRL"
  done
done
echo; echo "done. Check:  ls results_final/*/*.csv | wc -l   (39 expected after mode C)"