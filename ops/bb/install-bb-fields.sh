#!/usr/bin/env bash
# install-bb-fields.sh -- installs the reviewed ops/bb/bb-build (ticket owner fields, T3), ops/bb/bb-tickets and, since
# 2026-11-09 (orphan-ticket fix), ops/bb/bb-queue-lib.sh and ops/bb/bb-queue into ~/tools on bb hosts. Since 2026-11-11 (queue
# tools: load gate, port groups) the set also holds bb-hold, bb-wait, bb-netns-run and bb-portgroup.py, which the new bb-build and
# bb-queue call. Since the bb-mutate batch fix it also holds bb-mutate (the mutation-testing harness; nothing else calls it). Since issue #48 it also holds bb-await (the one bounded wait for agent lanes). Since issue #53 bb-queue-lib.sh holds grants while MemAvailable is below a per-host floor, and bb-queue shows that gate. Since 2026-11-20 it also holds bb-result (the one-screen summary of a finished ticket: verdict, test counts, first failure) and bb-build prints its usage for --help. Since 2026-11-19 it also holds bb-jbct-bootstrap (the helper bb-build calls before a jbct run). Since issue #56 it also bounds grants by a per-host reactor cap (~/work/max-reactors) and subtracts off-queue JVM headroom from MemAvailable; bb-queue shows the cap. Since the 2026-11-19 CTO ruling the cap counts the queue's LAUNCH LEDGER (bb-build and bb-hold record each launch), never a process pattern. The host's operator runs it, after the diff and md5s have been reviewed. Nothing in a build or a test ever runs it against a real host.
#
# Usage: install-bb-fields.sh user@address [user@address ...]     at least one target; there is no default host
#   Each target matches ^[a-z_][a-z0-9_-]{0,31}@[A-Za-z0-9.:-]+$. The md5 precondition is PER HOST: a host whose files differ
#   (a cloudbb copy may have diverged) is refused; the install never merges.
#   Ssh: ssh -i <key> -o BatchMode=yes -o ConnectTimeout=15 <target> <cmd>, <key> from the `key` line of the bb host config
#   (~/.config/bb-temper/hosts or BB_TEMPER_HOSTS; ops/bb/hosts.example; none: exit 78). BB_INSTALL_SSH (tests only) replaces
#   everything before <target>, and then no config is read.
# Phase 0, local: the fifteen files of ops/bb (next to this script) named below must be the pinned reviewed files, else exit 65
#   with no ssh made.
# Phase 1, read-only, EVERY target first: installed bb-build is one of BB_BUILD_OLD|MID|PREV|LIVE|PRE31|PRERC|PREVIA|PREMEM|PRECAP|PREJBCT|PREHELP|NEW, bb-queue-lib.sh one of BB_LIB_OLD|PREV|PRERC|PREMEM|PRECAP|PREOFFQ|PRELEDGER|NEW,
#   bb-queue one of BB_QUEUE_OLD|PREV|LIVE|PRE31|PRERC|PREVIA|PREMEM|PRECAP|PREOFFQ|PRELEDGER|NEW, bb-tickets is absent, BB_TICKETS_OLD|PRE31|PREOFFQ|NEW, bb-hold is absent, BB_HOLD_OLD (the 2026-11-09
#   host snapshot), BB_HOLD_PRELEDGER or BB_HOLD_NEW, bb-wait is absent, BB_WAIT_PRERC or its NEW, bb-await is absent or BB_AWAIT_NEW, bb-netns-run is absent, BB_NETNS_PREV or BB_NETNS_NEW, bb-portgroup.py is absent, BB_PORTGROUP_PREV or BB_PORTGROUP_NEW, bb-mutate is absent, BB_MUTATE_OLD, BB_MUTATE_PREVIA or BB_MUTATE_NEW, bb-jbct-bootstrap is absent or BB_JBCTBOOT_NEW, bb-result is absent, BB_RESULT_PRECP or BB_RESULT_NEW, and bb-cp-record, bb-test and bb-testcount (the bb-test lane) are absent or their NEW pin. Any mismatch prints `refused <target> <file> installed=<md5|absent>
#   expected=<pin>[|<pin>...]` (every accepted pin except `absent`, in the order above) for every mismatch on every target and exits 65 having written nothing anywhere; an unreachable target is 69.
# Phase 2, per target and per file to change (bb-cp-record, bb-test, bb-testcount, bb-mutate, bb-tickets, bb-hold, bb-wait, bb-await, bb-netns-run, bb-portgroup.py, bb-jbct-bootstrap, bb-result, bb-queue-lib.sh, bb-queue,
#   then bb-build: the helpers and the lib before the tools that call or source them; bb-build calls bb-cp-record), one remote `bash -s` script: receive the file as
#   ~/tools/<f>.new (base64 on stdin); its md5 must be the pinned one; re-check that the installed file is still what phase 1
#   saw (closes the race with phase 1); chmod 0755; cp -p an existing file (not bb-tickets) to <f>.bak-<oldmd5> (an existing .bak of that name is
#   kept; the older .pre-* / .bak-* backups are never touched); mv -f .new into place. The mv is atomic, so a running
#   `bb-build __run` waiter keeps reading the old inode instead of changing bytes under it.
# Output per file: `installed <target> <f> before=<md5|absent> after=<md5>` or `unchanged <target> <f> <md5>`.
# Exit: 0 done or nothing to do; 2 usage (no target or a bad one); 78 no ssh key configured; 65 refused (phase 2: a re-check refusal on the named target); 69 unreachable in
#   phase 1; 74 a phase-2 failure (files already moved stay installed; each mv was whole).
# Uninstall: mv -f ~/tools/bb-build.bak-999aa32577930aaf715ed4ce7c31fe02 ~/tools/bb-build; rm -f ~/tools/bb-tickets ~/tools/bb-cp-record ~/tools/bb-test ~/tools/bb-testcount
#   mv -f ~/tools/bb-queue-lib.sh.bak-814400ff1d980be4b2da3e8e0ec62644 ~/tools/bb-queue-lib.sh
#   mv -f ~/tools/bb-queue.bak-7efbfbecb9739fed35223cdc97eb185d ~/tools/bb-queue
#   (a host moved on to the 2026-11-11 set: restore each file from its <f>.bak-<oldmd5>, and rm -f the files that were absent before)
set -euo pipefail

readonly BB_BUILD_OLD=999aa32577930aaf715ed4ce7c31fe02
readonly BB_LIB_OLD=814400ff1d980be4b2da3e8e0ec62644
readonly BB_LIB_PREV=621b2252c3da05e7dd1161d076908864   # the 2026-11-09 orphan-ticket version (cloudbb-3 holds it)
readonly BB_LIB_PRERC=d890115b62a8af7583a18a7eddd75bab   # live on all four hosts from 2026-11-12: the ticket rc= is read from .bb/rc (replaced by the exit-status record)
readonly BB_LIB_PREMEM=a8fb757b12597e167b9804f7d14327e0   # the #42 version: no memory floor (issue #53)
readonly BB_LIB_PRECAP=cca5e8cd6942f988bb932f37f4de93e1   # the #53 version: no host reactor cap, no off-queue JVM headroom (issue #56)
readonly BB_LIB_PREOFFQ=e1b318e7bef1d4acff934b041d5ecc1a   # the #56/#64 version: no off-queue reactor list (2026-11-20); the cap counts reactors by process pattern
readonly BB_LIB_PRELEDGER=2569b8af2cf8acc063024e553be66a93   # the #66 version: the cap counts reactors by process pattern (replaced by the launch ledger)
readonly BB_LIB_NEW=e607b50fda23b34c7468cd89d0e9f247
readonly BB_QUEUE_OLD=7efbfbecb9739fed35223cdc97eb185d
readonly BB_QUEUE_PREV=a04b7be02f840d4f1e2bd19c54c52b3f
readonly BB_QUEUE_LIVE=af86457ae18f4ca331e47137cd088bc7   # what the 2026-11-11 hosts run: its header says `group tickets running:`, which the worker cannot parse
readonly BB_QUEUE_PRE31=45514cb5a7e737fc773929812bcdc5f7   # live on all four hosts since 2026-11-12: no [no-requireDocker] mark
readonly BB_QUEUE_PRERC=5691f8d57fc2d88e18d23ab33fc98b75   # live on all four hosts from 2026-11-12: the ticket rc= is read from .bb/rc (replaced by the exit-status record)
readonly BB_QUEUE_PREVIA=617b0bff8c73753e4a8e8b57201192ab   # live from the 2026-11-13 install: no [mutant <id>] mark (issue #46)
readonly BB_QUEUE_PREMEM=de6c69845c05e97f1234b4b4977f1b52   # the #46 version: no MEMORY GATE line, no [granted by memory override] mark (issue #53)
readonly BB_QUEUE_PRECAP=841403902eeb4af490300f8f56011c8e   # the #53 version: no HOST CAP line (issue #56)
readonly BB_QUEUE_PREOFFQ=fcbdc6cf8130550b57f83ebb00ef859b   # the #56/#64 version: no `offq` subcommand, the "+ N reactor(s)" count is reactors minus tickets (2026-11-20)
readonly BB_QUEUE_PRELEDGER=9cb677b671867eecdf90c9514bbd0732   # the #66 version: no queue-launches count in the RUNNING line
readonly BB_QUEUE_NEW=6d96fab85663b88fa2d0b9b0214d4b59
readonly BB_BUILD_MID=a1100c916b866ad3716bc4095ddb1991   # the T3 fields version, before the waiter's first-writer-wins guard
readonly BB_BUILD_PREV=cae49573d06f632d3081f7d5be1e9b66
readonly BB_BUILD_LIVE=015b2a2261a6789c5af215b3745a2b84   # what c3/c4/d1 run since 2026-11-11: no requireDocker flag
readonly BB_BUILD_PRE31=6abf8a6e0ae685dd7dbf32372f317883   # live on all four hosts since 2026-11-12: requireDocker flag always on
readonly BB_BUILD_PRERC=50920a7137f237aaa512d84d117ad4f2   # live on all four hosts from 2026-11-12: the ticket rc= is read from .bb/rc (replaced by the exit-status record)
readonly BB_BUILD_PREVIA=01e0bc34ea0effa8a8273a781f8ea52d   # live from the 2026-11-13 install: no via= ticket field (issue #46)
readonly BB_BUILD_PREMEM=eb10b2f4ea6c244444e95aa56bcf7e09
readonly BB_BUILD_PRECAP=8478b2174ce3ff6de293b55a283e3ce7   # the #53 version: comments only differ (issue #56)
readonly BB_BUILD_PREJBCT=12c68a554862caa672f7211456dfc84c   # the #56 version: no jbct plugin bootstrap hook, no bb-cp-record hook, no BB_LAUNCH
readonly BB_BUILD_PREHELP=a942996fc17ef7851bac693f135defc2   # the #64/#66 version: no --help, no bb-cp-record hook, no BB_LAUNCH, no launch ledger (bb-test lane)
readonly BB_BUILD_PRERESULT=24183c7d359b4030faeef894bbe4ba52   # the #67 version: --help and bb-result, no bb-cp-record hook, no launch ledger
readonly BB_BUILD_NEW=7c507772c3c43ff084098f591c4e0af0
# bb-jbct-bootstrap: absent on every host before its first install; bb-build calls it, so it is installed first.
readonly BB_JBCTBOOT_NEW=3e5396ecee48b99949456cf805df1e60
# bb-result: absent on every host before its first install. No trailing comment on a pin line: bb-drift reads it by pattern.
readonly BB_RESULT_PRECP=2b99dd5c414175c4fd40a9836da76298   # the #67 version, live on every host from 2026-11-20: no refused verdict line
readonly BB_RESULT_NEW=f4505867d09a1b3946280944678757ba
readonly BB_TICKETS_OLD=472cd816644fe0ee0902b8da3d33de8e   # before the per-ticket progressAt (issue #10)
readonly BB_TICKETS_PRE31=e1aeba98541ba781295001f956b08c8d   # live since 2026-11-12: drops a ticket key with a capital letter
readonly BB_TICKETS_PREOFFQ=6174119f0cd567c5dc462d1e063c6214   # the #31 version: no `offq` in the trailer (2026-11-20)
readonly BB_TICKETS_NEW=8d34d9dc9ab2a4f867af863ec21be77e
readonly BB_HOLD_OLD=0bc2401b6e94fd05b66e1df04387e2ac   # the 2026-11-09 host snapshot
readonly BB_HOLD_PRELEDGER=3561bb8e22fc2f7f3e98dc795f31eecb   # the #56 version: its slot was counted by pattern, not recorded in the launch ledger
readonly BB_HOLD_NEW=c8c6947d1de945e38c5cf08f8448dae9
readonly BB_WAIT_PRERC=da248f0b4f9248f2bfed64330b3ca66e   # live on all four hosts from 2026-11-12: the ticket rc= is read from .bb/rc (replaced by the exit-status record)
readonly BB_WAIT_NEW=d5b0ff8d43994a78cd188b4a41fcd995
# bb-await (issue #48): absent on every host before its first install. No trailing comment on a pin line: bb-drift reads it by pattern.
readonly BB_AWAIT_NEW=56523085fccf5678eb7c208e7352c11b
readonly BB_NETNS_PREV=9661165d4ef938ea43eec5b8d2d619d9   # 2026-11-11: no docker socket in the container; live on c3/c4/d1
readonly BB_NETNS_NEW=bf05b7c51c2f7863ee685a195273e195
readonly BB_PORTGROUP_PREV=a93ade2ff7dddcc75cee3d4169a08499   # the 2026-11-09 host snapshot, live on c4/d1 since 2026-11-11
readonly BB_PORTGROUP_NEW=884caf3cf37b12cf5cf1d97e96f76a4c
# The bb-test lane helpers: absent on every host before their first install. No trailing comment on a pin line: bb-drift reads it by pattern.
readonly BB_CPRECORD_NEW=3a75a8750a940c983170a43c6c8611b0
readonly BB_TEST_NEW=9b3a16c4b0facf832f8cccabe77f0a55
readonly BB_TESTCOUNT_NEW=7ef8a4c3eba2a517146eed6d7ac31511
readonly BB_MUTATE_OLD=f9bfbd37c5feafe67995f6716e8fecb0   # the host snapshot, identical on all four hosts: batch credit unsound for multi-module specs
readonly BB_MUTATE_PREVIA=2c341d44d428ba445db7ea03ee90af64   # the batch-sound version (#41): does not export BB_VIA (issue #46)
readonly BB_MUTATE_NEW=130f094861c0f835869ff42230d7bffb
readonly TARGET_RE='^[a-z_][a-z0-9_-]{0,31}@[A-Za-z0-9.:-]+$'

HERE=$(cd "$(dirname "$0")" && pwd)

md5_of() {
  if command -v md5sum > /dev/null 2>&1; then md5sum < "$1" | cut -d' ' -f1; else md5 -q "$1"; fi
}

[ $# -gt 0 ] || { echo "usage: install-bb-fields.sh user@address [user@address ...] (no default host)" >&2; exit 2; }
targets=("$@")
for t in "${targets[@]}"; do
  [[ $t =~ $TARGET_RE ]] || { echo "install-bb-fields: bad target '$t' (user@address)" >&2; exit 2; }
done

# The files, in install order (helpers and the lib before the tools that call or source them), with what each may be on a host.
# absent_ok: the file may be missing. bash 3.2 compatible: no associative arrays.
FILES=(bb-cp-record bb-test bb-testcount bb-mutate bb-tickets bb-hold bb-wait bb-await bb-netns-run bb-portgroup.py bb-jbct-bootstrap bb-result bb-queue-lib.sh bb-queue bb-build)
PROBE_ORDER=(bb-cp-record bb-test bb-testcount bb-build bb-queue-lib.sh bb-queue bb-tickets bb-hold bb-wait bb-await bb-netns-run bb-portgroup.py bb-mutate bb-jbct-bootstrap bb-result)   # refusal lines come in this order
new_of() {
  case $1 in
    bb-build) echo "$BB_BUILD_NEW" ;; bb-queue-lib.sh) echo "$BB_LIB_NEW" ;; bb-queue) echo "$BB_QUEUE_NEW" ;;
    bb-tickets) echo "$BB_TICKETS_NEW" ;; bb-hold) echo "$BB_HOLD_NEW" ;; bb-wait) echo "$BB_WAIT_NEW" ;; bb-await) echo "$BB_AWAIT_NEW" ;;
    bb-netns-run) echo "$BB_NETNS_NEW" ;; bb-portgroup.py) echo "$BB_PORTGROUP_NEW" ;;
    bb-mutate) echo "$BB_MUTATE_NEW" ;;
    bb-cp-record) echo "$BB_CPRECORD_NEW" ;; bb-test) echo "$BB_TEST_NEW" ;; bb-testcount) echo "$BB_TESTCOUNT_NEW" ;;
    bb-jbct-bootstrap) echo "$BB_JBCTBOOT_NEW" ;;
    bb-result) echo "$BB_RESULT_NEW" ;;
  esac
}
accepted_of() {  # the md5s a host may hold besides absent, in the order the refusal names them
  case $1 in
    bb-build) echo "$BB_BUILD_OLD $BB_BUILD_MID $BB_BUILD_PREV $BB_BUILD_LIVE $BB_BUILD_PRE31 $BB_BUILD_PRERC $BB_BUILD_PREVIA $BB_BUILD_PREMEM $BB_BUILD_PRECAP $BB_BUILD_PREJBCT $BB_BUILD_PREHELP $BB_BUILD_PRERESULT $BB_BUILD_NEW" ;;
    bb-queue-lib.sh) echo "$BB_LIB_OLD $BB_LIB_PREV $BB_LIB_PRERC $BB_LIB_PREMEM $BB_LIB_PRECAP $BB_LIB_PREOFFQ $BB_LIB_PRELEDGER $BB_LIB_NEW" ;;
    bb-queue) echo "$BB_QUEUE_OLD $BB_QUEUE_PREV $BB_QUEUE_LIVE $BB_QUEUE_PRE31 $BB_QUEUE_PRERC $BB_QUEUE_PREVIA $BB_QUEUE_PREMEM $BB_QUEUE_PRECAP $BB_QUEUE_PREOFFQ $BB_QUEUE_PRELEDGER $BB_QUEUE_NEW" ;;
    bb-hold) echo "$BB_HOLD_OLD $BB_HOLD_PRELEDGER $BB_HOLD_NEW" ;;
    bb-tickets) echo "$BB_TICKETS_OLD $BB_TICKETS_PRE31 $BB_TICKETS_PREOFFQ $BB_TICKETS_NEW" ;;
    bb-portgroup.py) echo "$BB_PORTGROUP_PREV $BB_PORTGROUP_NEW" ;;
    bb-netns-run) echo "$BB_NETNS_PREV $BB_NETNS_NEW" ;;
    bb-wait) echo "$BB_WAIT_PRERC $BB_WAIT_NEW" ;;
    bb-await) echo "$BB_AWAIT_NEW" ;;
    bb-mutate) echo "$BB_MUTATE_OLD $BB_MUTATE_PREVIA $BB_MUTATE_NEW" ;;
    bb-result) echo "$BB_RESULT_PRECP $BB_RESULT_NEW" ;;
    *) new_of "$1" ;;
  esac
}
absent_ok() { case $1 in bb-build|bb-queue-lib.sh|bb-queue) return 1 ;; *) return 0 ;; esac; }
file_index() { local i=0 f; for f in "${FILES[@]}"; do [ "$f" = "$1" ] && { echo "$i"; return; }; i=$((i + 1)); done; }

# ---- phase 0: the local files are the reviewed ones
for f in "${FILES[@]}"; do
  [ "$(md5_of "$HERE/$f")" = "$(new_of "$f")" ] || { echo "install-bb-fields: local file is not the reviewed one" >&2; exit 65; }
done

if [ -n "${BB_INSTALL_SSH:-}" ]; then
  read -r -a SSH <<< "$BB_INSTALL_SSH"
else
  hosts=${BB_TEMPER_HOSTS:-$HOME/.config/bb-temper/hosts}; key=
  if [ -r "$hosts" ]; then
    dup=$(awk '{ sub(/\r$/, ""); sub(/#.*/, "") } $1 == "key" && NF == 2 && ++n == 2 { print NR; exit }' "$hosts")
    [ -z "$dup" ] || { echo "install-bb-fields: $hosts: line $dup: key is listed twice" >&2; exit 78; }
    key=$(awk '{ sub(/\r$/, ""); sub(/#.*/, "") } $1 == "key" && NF == 2 { v = $2 } END { print v }' "$hosts")
  fi
  case $key in '~/'*) key=$HOME/${key#'~/'} ;; esac
  [ -n "$key" ] || { echo "install-bb-fields: no ssh key: no 'key <path>' line in $hosts" >&2; exit 78; }
  SSH=(ssh -i "$key" -o BatchMode=yes -o ConnectTimeout=15)
fi

# ---- phase 1: read-only, every target
PROBE='for f in bb-cp-record bb-test bb-testcount bb-build bb-queue-lib.sh bb-queue bb-tickets bb-hold bb-wait bb-await bb-netns-run bb-portgroup.py bb-mutate bb-jbct-bootstrap bb-result; do
  if [ -f "$HOME/tools/$f" ]; then printf "%s %s\n" "$f" "$(md5sum < "$HOME/tools/$f" | cut -d" " -f1)"; else printf "%s absent\n" "$f"; fi
done'
INST=()   # per target and file (FILES order): the installed md5 (or absent), as phase 1 saw it, at index target * ${#FILES[@]} + file
refused=0
i=0
for t in "${targets[@]}"; do
  if ! out=$("${SSH[@]}" "$t" bash -s <<< "$PROBE"); then
    echo "install-bb-fields: $t unreachable (phase 1); nothing written" >&2; exit 69
  fi
  for f in "${PROBE_ORDER[@]}"; do
    m=$(printf '%s\n' "$out" | awk -v f="$f" '$1 == f { print $2 }' | head -n 1)
    [ -n "$m" ] || { echo "install-bb-fields: $t gave no answer for $f; nothing written" >&2; exit 69; }
    INST[i * ${#FILES[@]} + $(file_index "$f")]=$m
    ok=0; pins=
    for p in $(accepted_of "$f"); do
      [ "$m" != "$p" ] || ok=1
      pins=${pins:+$pins|}$p
    done
    if [ "$m" = absent ] && absent_ok "$f"; then ok=1; fi
    if [ "$ok" = 0 ]; then echo "refused $t $f installed=$m expected=$pins"; refused=1; fi
  done
  i=$((i + 1))
done
[ "$refused" = 0 ] || { echo "install-bb-fields: refused; nothing written on any target" >&2; exit 65; }

# ---- phase 2: per target, per file to change
REMOTE_HEAD='set -eu
umask 022
f=$1; new=$2; old=$3; d=$HOME/tools
trap '"'"'rm -f "$d/$f.new"'"'"' EXIT
base64 -d > "$d/$f.new" <<'"'"'PAYLOAD'"'"''
REMOTE_TAIL='got=$(md5sum < "$d/$f.new" | cut -d" " -f1)
[ "$got" = "$new" ] || { echo "payload md5 $got is not $new" >&2; exit 65; }
if [ -f "$d/$f" ]; then cur=$(md5sum < "$d/$f" | cut -d" " -f1); else cur=absent; fi
[ "$cur" = "$old" ] || { echo "$f changed since phase 1: $cur, expected $old" >&2; exit 65; }
chmod 0755 "$d/$f.new"
if [ "$old" != absent ] && [ "$f" != bb-tickets ]; then [ -e "$d/$f.bak-$old" ] || cp -p "$d/$f" "$d/$f.bak-$old"; fi
mv -f "$d/$f.new" "$d/$f"
md5sum < "$d/$f" | cut -d" " -f1'
install_one() {  # install_one <target> <file> <new md5> <installed md5 as phase 1 saw it>
  local t=$1 f=$2 new=$3 old=$4 script after rc errf err
  if [ "$old" = "$new" ]; then echo "unchanged $t $f $new"; return 0; fi
  script="$REMOTE_HEAD"$'\n'"$(base64 < "$HERE/$f")"$'\n'"PAYLOAD"$'\n'"$REMOTE_TAIL"
  errf=$(mktemp); rc=0; after=$("${SSH[@]}" "$t" bash -s -- "$f" "$new" "$old" <<< "$script" 2> "$errf") || rc=$?
  err=$(cat "$errf"); rm -f "$errf"; [ -z "$err" ] || echo "$err" >&2
  if [ "$rc" = 65 ]; then
    if grep -q '^payload md5 ' <<< "$err"; then echo "install-bb-fields: refused: payload md5 mismatch after transfer ($f on $t); nothing replaced" >&2
    else echo "install-bb-fields: $t $f: refused by the phase-2 re-check; nothing replaced" >&2; fi
    exit 65
  fi
  if [ "$rc" != 0 ]; then echo "install-bb-fields: $t $f: phase 2 failed (rc $rc)" >&2; exit 74; fi
  echo "installed $t $f before=$old after=$after"
}
i=0
for t in "${targets[@]}"; do
  for f in "${FILES[@]}"; do
    install_one "$t" "$f" "$(new_of "$f")" "${INST[i * ${#FILES[@]} + $(file_index "$f")]}"
  done
  i=$((i + 1))
done
