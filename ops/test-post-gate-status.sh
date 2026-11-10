#!/usr/bin/env bash
# Hermetic test of post-gate-status.py: fake `java` (temper result) and `gh` (PR/branch head) on PATH, dry runs only.
# Pins that the status is refused (exit 3) when the PR or branch head is no longer the gated SHA, and when the job ran
# with tests skipped or narrowed (m4-design §5.1).
# usage: test-post-gate-status.sh [script]   (default: the post-gate-status.py next to this file)
set -u
script=${1:-$(dirname "$0")/post-gate-status.py}
fake=$(mktemp -d); trap 'rm -rf "$fake"' EXIT
gated=1111111111111111111111111111111111111111
plain='"userProperties":[{"define":"-DfailIfNoSpecifiedTests=false","tests":null}]'
record() { # userProperties member (or nothing: a pre-D-6 controller)
  echo "{\"job\":41,\"verdict\":\"PASSED\",\"reason\":null,\"source\":{\"sha\":\"$gated\"},\"counts\":{\"testcases\":20911,\"failed\":0,\"skipped\":40},\"failed\":[]${1:+,$1}}" > "$fake/result"
}
record "$plain"
cat > "$fake/java" <<'J'
#!/bin/sh
cat "$(dirname "$0")/result"
J
cat > "$fake/gh" <<'G'
#!/bin/sh
cat "$(dirname "$0")/head"
G
chmod +x "$fake/java" "$fake/gh"
fails=0
check() { # name, head, expected exit, args...
  local name=$1 head=$2 want=$3; shift 3
  echo "$head" > "$fake/head"
  out=$(PATH="$fake:$PATH" python3 -I "$script" "$@" 2>&1); got=$?
  if [ "$got" = "$want" ]; then echo "ok   $name (exit $got)"; else echo "FAIL $name: exit $got, want $want: $out"; fails=$((fails+1)); fi
}
moved=2222222222222222222222222222222222222222
check "branch head is the gated SHA"      $gated 0 41 --branch overhaul/streams
check "branch head moved after the gate"  $moved 3 41 --branch overhaul/streams
check "PR head is the gated SHA"          $gated 0 41 2205
check "PR head moved after the gate"      $moved 3 41 2205
check "both PR and branch"                $gated 3 41 2205 --branch overhaul/streams
check "neither PR nor branch"             $gated 3 41
record '"userProperties":[{"define":"-DskipTests=true","tests":"skipped"}]'
check "tests skipped"                     $gated 3 41 2205
record '"userProperties":[{"define":"-pl core","tests":"narrowed"}]'
check "reactor narrowed"                  $gated 3 41 2205
record '"userProperties":[{"define":"-pl=core","tests":"unknown"}]'
check "option temper does not list"       $gated 3 41 2205
record ""
check "no userProperties field"           $gated 0 41 2205
[ $fails = 0 ] && echo "PASS" || { echo "FAILED: $fails"; exit 1; }
