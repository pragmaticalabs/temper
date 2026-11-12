#!/usr/bin/env bash
# CI canary: a Docker-dependent test must never pass by silence (#23: 239 cases once ran with no testcase at all).
# Reads the surefire/failsafe XML under <root> (default .) after the build and fails when
#   1. Docker is not reachable (`docker info`), or
#   2. a report carries no <testcase> element (the suite's tests="N" header is NOT used: a class with @Nested
#      classes reports tests="0" in its own header while the nested cases sit inside), or
#   3. a testcase was skipped because Docker is missing (the fixtures' own assumption messages).
set -euo pipefail

root="${1:-.}"
fail=0

if [ "${CANARY_SKIP_DOCKER_INFO:-}" != 1 ] && ! docker info >/dev/null 2>&1; then
    echo "::error::docker is not reachable in this job" >&2
    fail=1
fi

reports=$(find "$root" -type f -name 'TEST-*.xml' \( -path '*/target/surefire-reports*/*' -o -path '*/target/failsafe-reports/*' \) | sort)
if [ -z "$reports" ]; then
    echo "::error::no surefire/failsafe reports under $root: nothing ran" >&2
    exit 1
fi

count=0
while IFS= read -r f; do
    count=$((count + 1))
    if ! grep -q '<testcase' "$f"; then
        echo "::error::report with no testcase (silent zero): $f" >&2
        fail=1
    fi
    if grep -qE 'docker is not available|Could not find a valid Docker' "$f"; then
        echo "::error::tests skipped for want of Docker: $f" >&2
        fail=1
    fi
done <<< "$reports"

[ "$fail" = 0 ] && echo "docker canary: $count reports, docker reachable, no silent zero, no docker skip"
exit "$fail"
