#!/bin/bash
# closed-fd-probe.sh -- by-hand regression for F-A (not run by Maven). Needs the Mac's /bin/bash 3.2.
# bash 3.2 lets `{ true >&1; }` succeed on a CLOSED descriptor; bash 5 reports the error, so no Linux test can tell the
# old dl_shield probe from the /dev/fd/N probe (the "mfs" mutant survives there by equivalence). Here both probes run under
# /bin/bash with stdout/stderr closed: the old one must say "open" (the bug), the one lib.sh uses must say "closed".
# Also checks lib.sh still uses the /dev/fd probe. Exit 0 = pass.
set -u
BASH_MAC=${BASH_MAC:-/bin/bash}
LIB=${LIB:-$(cd "$(dirname "$0")/.." && pwd)/lib.sh}
fail=0
echo "bash: $("$BASH_MAC" --version | head -n 1)"
for fd in 1 2; do
  # a closed fd 1 swallows the echo: report through fd 3
  old=$("$BASH_MAC" -c "exec $fd>&-; if { true >&$fd; } 2> /dev/null; then echo open >&3; else echo closed >&3; fi" 3>&1)
  new=$("$BASH_MAC" -c "exec $fd>&-; if [ -e /dev/fd/$fd ]; then echo open >&3; else echo closed >&3; fi" 3>&1)
  echo "fd $fd closed: old probe says $old, /dev/fd probe says $new"
  [ "$new" = closed ] || { echo "FAIL: the /dev/fd probe does not see fd $fd closed"; fail=1; }
  if [ "$old" = open ]; then echo "  (old probe reproduces F-A on this bash)"; fi
done
grep -q 'if \[ -e /dev/fd/1 \]; then exec 5>&1' "$LIB" || { echo "FAIL: lib.sh dl_shield no longer uses the /dev/fd/1 probe"; fail=1; }
grep -q 'if \[ -e /dev/fd/2 \]; then exec 6>&2' "$LIB" || { echo "FAIL: lib.sh dl_shield no longer uses the /dev/fd/2 probe"; fail=1; }
# end to end: dl_shield and dl_cleanup under a closed stdout/stderr must exit 0 and leave no pump behind
for spec in '>&-' '2>&-' '>&- 2>&-'; do
  rc=$("$BASH_MAC" -c ". '$LIB'; DL_B=/dev/null; DL_TMP=\$(mktemp -d); dl_shield; dl_cleanup; echo rc=0 >&3" 3>&1 $spec 2> /dev/null || echo rc=$?)
  echo "dl_shield+dl_cleanup with [$spec]: ${rc:-no output}"
  case "$rc" in *rc=0*) ;; *) echo "FAIL: nonzero or aborted"; fail=1 ;; esac
done
[ "$fail" = 0 ] && echo PASS || echo FAILED
exit "$fail"
