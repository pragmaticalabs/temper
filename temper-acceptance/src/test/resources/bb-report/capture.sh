#!/usr/bin/env bash
# Regenerates the captured bb-report outputs of this directory (BbReportParserTest, TrustComparisonTest).
#
#   capture.sh <path to bb-report> <s1-reports dir> <output dir>
#
# bb-report is bigboy's ~/tools/bb-report; the captures were made 2026-10-14 with the scratchpad copy archived at
# ~/temper-wt/scratch-archive-2026-10-13/4d4552c4-e892-4abb-8746-fbe3687860a6/bbtools/bb-report (m1-design §8.1 [V]).
# The XML is the real S1 TC evidence from bigboy (the S1 spike's TC reports: 26 files, 293
# <testcase>, tests= sum 177 because of @Nested). Each scenario is a synthesized checkout: .bb/ as bb-build writes it,
# the XML under a module's target/surefire-reports, newer (or not) than .bb/run.marker.
set -euo pipefail
report=$1; xml=$2; out=$3
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

checkout() {  # checkout <name> <rc> <args> <BUILD SUCCESS|FAILURE> -> creates $work/<name>
  local co=$work/$1
  mkdir -p "$co/.bb" "$co/db/postgres-async/target/surefire-reports"
  echo "$2" > "$co/.bb/rc"; echo 1760000000 > "$co/.bb/start"; echo 1760005640 > "$co/.bb/end"
  printf '%s' "$3" > "$co/.bb/args"
  printf '[INFO] Reactor Summary for pragmatica 1.0.0-rc4:\n[INFO] core ............................................... SUCCESS [  9.120 s]\n[INFO] postgres-async ..................................... %s [01:31 min]\n[INFO] BUILD %s\n' \
    "$( [ "$4" = SUCCESS ] && echo SUCCESS || echo FAILURE )" "$4" > "$co/.bb/build.log"
  touch -t 202610130200 "$co/.bb/run.marker"
}

capture() {  # capture <name>
  local rc=0
  "$report" "$work/$1" > "$out/$1.out" 2>&1 || rc=$?
  echo "$rc" > "$out/$1.exit"
}

# green: the S1 XML as written, a default `clean install`.
checkout green 0 "" SUCCESS
cp "$xml"/*.xml "$work/green/db/postgres-async/target/surefire-reports/"
capture green

# red: one top-level failure (PipelineTest) and one error inside a @Nested class (CrossTypeConversionTest$NumericCrossType).
checkout red 1 "clean install" FAILURE
cp "$xml"/*.xml "$work/red/db/postgres-async/target/surefire-reports/"
d=$work/red/db/postgres-async/target/surefire-reports
python3 - "$d" <<'PY'
import re, sys
d = sys.argv[1]
def flip(name, classname, element):
    path = f"{d}/TEST-{name}.xml"
    s = open(path).read()
    m = re.search(r'(<testcase [^>]*classname="' + re.escape(classname) + r'"[^>]*?)/>', s)
    s = s[:m.start()] + m.group(1) + f'><{element} message="probe" type="java.lang.AssertionError">probe</{element}></testcase>' + s[m.end():]
    open(path, "w").write(s)
flip("org.pragmatica.postgres.PipelineTest", "org.pragmatica.postgres.PipelineTest", "failure")
flip("org.pragmatica.postgres.CrossTypeConversionTest", "org.pragmatica.postgres.CrossTypeConversionTest$NumericCrossType", "error")
PY
capture red
cp "$d/TEST-org.pragmatica.postgres.PipelineTest.xml" "$out/red-TEST-org.pragmatica.postgres.PipelineTest.xml"
cp "$d/TEST-org.pragmatica.postgres.CrossTypeConversionTest.xml" "$out/red-TEST-org.pragmatica.postgres.CrossTypeConversionTest.xml"

# empty: tests requested, but every XML is older than the marker (bb-report exit 3).
checkout empty 0 "clean install" SUCCESS
cp "$xml"/TEST-org.pragmatica.postgres.PipelineTest.xml "$work/empty/db/postgres-async/target/surefire-reports/"
touch -t 202610120000 "$work/empty/db/postgres-async/target/surefire-reports/"*.xml
capture empty

# dre: a dependency-resolution failure in the log, and one module's XML.
checkout dre 1 "clean install" FAILURE
printf '[ERROR] Failed to execute goal on project forge-tests: Could not resolve dependencies for project org.pragmatica-lite.aether:forge-tests:jar:1.0.0-rc4\n[ERROR] -> [Help 1] org.apache.maven.lifecycle.LifecycleExecutionException: DependencyResolutionException\n' >> "$work/dre/.bb/build.log"
cp "$xml"/TEST-org.pragmatica.postgres.PipelineTest.xml "$xml"/TEST-org.pragmatica.postgres.TransactionTest.xml "$work/dre/db/postgres-async/target/surefire-reports/"
capture dre

# running: no .bb/rc yet (bb-report exit 1).
mkdir -p "$work/running/.bb"
capture running
