#!/usr/bin/env bash
# Pins ops/ci/docker-canary.sh: green on real-shaped reports, red on each of the three failures.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export CANARY_SKIP_DOCKER_INFO=1

mk() { mkdir -p "$tmp/$1/m/target/surefire-reports"; printf '%s\n' "$2" > "$tmp/$1/m/target/surefire-reports/TEST-x.xml"; }
nested='<testsuite name="A" tests="0"><testcase name="t" classname="A$N"/></testsuite>'
mk ok "$nested"
mk zero '<testsuite name="A" tests="0"></testsuite>'
mk skip '<testsuite name="A" tests="1" skipped="1"><testcase name="t"><skipped><![CDATA[Assumption failed: docker is not available]]></skipped></testcase></testsuite>'

"$here/docker-canary.sh" "$tmp/ok" >/dev/null || { echo "FAIL: nested-class report must pass"; exit 1; }
for bad in zero skip; do
    if "$here/docker-canary.sh" "$tmp/$bad" >/dev/null 2>&1; then echo "FAIL: $bad must fail"; exit 1; fi
done
mk fixture "$nested"
mkdir -p "$tmp/fixture/m/target/docker-it/e/target/surefire-reports"
printf '%s\n' '<testsuite name="F" tests="0"></testsuite>' > "$tmp/fixture/m/target/docker-it/e/target/surefire-reports/TEST-f.xml"
"$here/docker-canary.sh" "$tmp/fixture" >/dev/null || { echo "FAIL: a test's own fixture report must be ignored"; exit 1; }
mkdir -p "$tmp/empty"
if "$here/docker-canary.sh" "$tmp/empty" >/dev/null 2>&1; then echo "FAIL: no reports must fail"; exit 1; fi
echo "docker canary self-test: ok"
