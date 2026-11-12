#!/usr/bin/env bash
# install-bb-fields.sh -- installs the reviewed ops/bb/bb-build (ticket owner fields, T3), ops/bb/bb-tickets and, since
# 2026-11-09 (orphan-ticket fix), ops/bb/bb-queue-lib.sh and ops/bb/bb-queue into ~/tools on bb hosts. Since 2026-11-11 (queue
# tools: load gate, port groups) the set also holds bb-hold, bb-wait, bb-netns-run and bb-portgroup.py, which the new bb-build and
# bb-queue call. The host's operator runs it, after the diff and md5s have been reviewed. Nothing in a build or a test ever runs it against a real host.
#
# Usage: install-bb-fields.sh user@address [user@address ...]     at least one target; there is no default host
#   Each target matches ^[a-z_][a-z0-9_-]{0,31}@[A-Za-z0-9.:-]+$. The md5 precondition is PER HOST: a host whose files differ
#   (a cloudbb copy may have diverged) is refused; the install never merges.
#   Ssh: ssh -i <key> -o BatchMode=yes -o ConnectTimeout=15 <target> <cmd>, <key> from the `key` line of the bb host config
#   (~/.config/bb-temper/hosts or BB_TEMPER_HOSTS; ops/bb/hosts.example; none: exit 78). BB_INSTALL_SSH (tests only) replaces
#   everything before <target>, and then no config is read.
# Phase 0, local: the eight files of ops/bb (next to this script) named below must be the pinned reviewed files, else exit 65
#   with no ssh made.
# Phase 1, read-only, EVERY target first: installed bb-build is one of BB_BUILD_OLD|MID|PREV|LIVE|PRE31|PRERC|NEW, bb-queue-lib.sh one of BB_LIB_OLD|PREV|PRERC|NEW,
#   bb-queue one of BB_QUEUE_OLD|PREV|LIVE|PRE31|PRERC|NEW, bb-tickets is absent, BB_TICKETS_OLD|PRE31|NEW, bb-hold is absent, BB_HOLD_OLD (the 2026-11-09
#   host snapshot) or BB_HOLD_NEW, bb-wait is absent, BB_WAIT_PRERC or its NEW, bb-netns-run is absent, BB_NETNS_PREV or BB_NETNS_NEW, bb-portgroup.py is absent, BB_PORTGROUP_PREV or BB_PORTGROUP_NEW. Any mismatch prints `refused <target> <file> installed=<md5|absent>
#   expected=<pin>[|<pin>...]` (every accepted pin except `absent`, in the order above) for every mismatch on every target and exits 65 having written nothing anywhere; an unreachable target is 69.
# Phase 2, per target and per file to change (bb-tickets, bb-hold, bb-wait, bb-netns-run, bb-portgroup.py, bb-queue-lib.sh, bb-queue,
#   then bb-build: the helpers and the lib before the tools that call or source them), one remote `bash -s` script: receive the file as
#   ~/tools/<f>.new (base64 on stdin); its md5 must be the pinned one; re-check that the installed file is still what phase 1
#   saw (closes the race with phase 1); chmod 0755; cp -p an existing file (not bb-tickets) to <f>.bak-<oldmd5> (an existing .bak of that name is
#   kept; the older .pre-* / .bak-* backups are never touched); mv -f .new into place. The mv is atomic, so a running
#   `bb-build __run` waiter keeps reading the old inode instead of changing bytes under it.
# Output per file: `installed <target> <f> before=<md5|absent> after=<md5>` or `unchanged <target> <f> <md5>`.
# Exit: 0 done or nothing to do; 2 usage (no target or a bad one); 78 no ssh key configured; 65 refused (phase 2: a re-check refusal on the named target); 69 unreachable in
#   phase 1; 74 a phase-2 failure (files already moved stay installed; each mv was whole).
# Uninstall: mv -f ~/tools/bb-build.bak-999aa32577930aaf715ed4ce7c31fe02 ~/tools/bb-build; rm -f ~/tools/bb-tickets
#   mv -f ~/tools/bb-queue-lib.sh.bak-814400ff1d980be4b2da3e8e0ec62644 ~/tools/bb-queue-lib.sh
#   mv -f ~/tools/bb-queue.bak-7efbfbecb9739fed35223cdc97eb185d ~/tools/bb-queue
#   (a host moved on to the 2026-11-11 set: restore each file from its <f>.bak-<oldmd5>, and rm -f the files that were absent before)
set -euo pipefail

readonly BB_BUILD_OLD=999aa32577930aaf715ed4ce7c31fe02
readonly BB_LIB_OLD=814400ff1d980be4b2da3e8e0ec62644
readonly BB_LIB_PREV=621b2252c3da05e7dd1161d076908864   # the 2026-11-09 orphan-ticket version (cloudbb-3 holds it)
readonly BB_LIB_PRERC=d890115b62a8af7583a18a7eddd75bab   # live on all four hosts from 2026-11-12: the ticket rc= is read from .bb/rc (replaced by the exit-status record)
readonly BB_LIB_NEW=dea803ecd5ad27608015d63bb799f30e
readonly BB_QUEUE_OLD=7efbfbecb9739fed35223cdc97eb185d
readonly BB_QUEUE_PREV=a04b7be02f840d4f1e2bd19c54c52b3f
readonly BB_QUEUE_LIVE=af86457ae18f4ca331e47137cd088bc7   # what the 2026-11-11 hosts run: its header says `group tickets running:`, which the worker cannot parse
readonly BB_QUEUE_PRE31=45514cb5a7e737fc773929812bcdc5f7   # live on all four hosts since 2026-11-12: no [no-requireDocker] mark
readonly BB_QUEUE_PRERC=5691f8d57fc2d88e18d23ab33fc98b75   # live on all four hosts from 2026-11-12: the ticket rc= is read from .bb/rc (replaced by the exit-status record)
readonly BB_QUEUE_NEW=c0a2fb389b13765fb391a07ebcb75ecb
readonly BB_BUILD_MID=a1100c916b866ad3716bc4095ddb1991   # the T3 fields version, before the waiter's first-writer-wins guard
readonly BB_BUILD_PREV=cae49573d06f632d3081f7d5be1e9b66
readonly BB_BUILD_LIVE=015b2a2261a6789c5af215b3745a2b84   # what c3/c4/d1 run since 2026-11-11: no requireDocker flag
readonly BB_BUILD_PRE31=6abf8a6e0ae685dd7dbf32372f317883   # live on all four hosts since 2026-11-12: requireDocker flag always on
readonly BB_BUILD_PRERC=50920a7137f237aaa512d84d117ad4f2   # live on all four hosts from 2026-11-12: the ticket rc= is read from .bb/rc (replaced by the exit-status record)
readonly BB_BUILD_NEW=e6e5b66ce739e6be167f415e1a918859
readonly BB_TICKETS_OLD=472cd816644fe0ee0902b8da3d33de8e   # before the per-ticket progressAt (issue #10)
readonly BB_TICKETS_PRE31=e1aeba98541ba781295001f956b08c8d   # live since 2026-11-12: drops a ticket key with a capital letter
readonly BB_TICKETS_NEW=6174119f0cd567c5dc462d1e063c6214
readonly BB_HOLD_OLD=0bc2401b6e94fd05b66e1df04387e2ac   # the 2026-11-09 host snapshot
readonly BB_HOLD_NEW=3561bb8e22fc2f7f3e98dc795f31eecb
readonly BB_WAIT_PRERC=da248f0b4f9248f2bfed64330b3ca66e   # live on all four hosts from 2026-11-12: the ticket rc= is read from .bb/rc (replaced by the exit-status record)
readonly BB_WAIT_NEW=e219fdf57212edac93a75bebf16c7db1
readonly BB_NETNS_PREV=9661165d4ef938ea43eec5b8d2d619d9   # 2026-11-11: no docker socket in the container; live on c3/c4/d1
readonly BB_NETNS_NEW=bf05b7c51c2f7863ee685a195273e195
readonly BB_PORTGROUP_PREV=a93ade2ff7dddcc75cee3d4169a08499   # the 2026-11-09 host snapshot, live on c4/d1 since 2026-11-11
readonly BB_PORTGROUP_NEW=884caf3cf37b12cf5cf1d97e96f76a4c
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
FILES=(bb-tickets bb-hold bb-wait bb-netns-run bb-portgroup.py bb-queue-lib.sh bb-queue bb-build)
PROBE_ORDER=(bb-build bb-queue-lib.sh bb-queue bb-tickets bb-hold bb-wait bb-netns-run bb-portgroup.py)   # refusal lines come in this order
new_of() {
  case $1 in
    bb-build) echo "$BB_BUILD_NEW" ;; bb-queue-lib.sh) echo "$BB_LIB_NEW" ;; bb-queue) echo "$BB_QUEUE_NEW" ;;
    bb-tickets) echo "$BB_TICKETS_NEW" ;; bb-hold) echo "$BB_HOLD_NEW" ;; bb-wait) echo "$BB_WAIT_NEW" ;;
    bb-netns-run) echo "$BB_NETNS_NEW" ;; bb-portgroup.py) echo "$BB_PORTGROUP_NEW" ;;
  esac
}
accepted_of() {  # the md5s a host may hold besides absent, in the order the refusal names them
  case $1 in
    bb-build) echo "$BB_BUILD_OLD $BB_BUILD_MID $BB_BUILD_PREV $BB_BUILD_LIVE $BB_BUILD_PRE31 $BB_BUILD_PRERC $BB_BUILD_NEW" ;;
    bb-queue-lib.sh) echo "$BB_LIB_OLD $BB_LIB_PREV $BB_LIB_PRERC $BB_LIB_NEW" ;;
    bb-queue) echo "$BB_QUEUE_OLD $BB_QUEUE_PREV $BB_QUEUE_LIVE $BB_QUEUE_PRE31 $BB_QUEUE_PRERC $BB_QUEUE_NEW" ;;
    bb-hold) echo "$BB_HOLD_OLD $BB_HOLD_NEW" ;;
    bb-tickets) echo "$BB_TICKETS_OLD $BB_TICKETS_PRE31 $BB_TICKETS_NEW" ;;
    bb-portgroup.py) echo "$BB_PORTGROUP_PREV $BB_PORTGROUP_NEW" ;;
    bb-netns-run) echo "$BB_NETNS_PREV $BB_NETNS_NEW" ;;
    bb-wait) echo "$BB_WAIT_PRERC $BB_WAIT_NEW" ;;
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
PROBE='for f in bb-build bb-queue-lib.sh bb-queue bb-tickets bb-hold bb-wait bb-netns-run bb-portgroup.py; do
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
