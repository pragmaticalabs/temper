#!/usr/bin/env bash
# install-bb-fields.sh -- installs the reviewed ops/bb/bb-build (ticket owner fields, T3), ops/bb/bb-tickets and, since
# 2026-11-09 (orphan-ticket fix), ops/bb/bb-queue-lib.sh and ops/bb/bb-queue into ~/tools on bb hosts. The host's operator
# runs it, after the diff and md5s have been reviewed. Nothing in a build or a test ever runs it against a real host.
#
# Usage: install-bb-fields.sh user@address [user@address ...]     at least one target; there is no default host
#   Each target matches ^[a-z_][a-z0-9_-]{0,31}@[A-Za-z0-9.:-]+$. The md5 precondition is PER HOST: a host whose files differ
#   (a cloudbb copy may have diverged) is refused; the install never merges.
#   Ssh: ssh -i <key> -o BatchMode=yes -o ConnectTimeout=15 <target> <cmd>, <key> from the `key` line of the bb host config
#   (~/.config/bb-temper/hosts or BB_TEMPER_HOSTS; ops/bb/hosts.example; none: exit 78). BB_INSTALL_SSH (tests only) replaces
#   everything before <target>, and then no config is read.
# Phase 0, local: ops/bb/bb-build, bb-tickets, bb-queue-lib.sh and bb-queue (next to this script) must be the pinned reviewed
#   files, else exit 65 with no ssh made.
# Phase 1, read-only, EVERY target first: installed bb-build is BB_BUILD_OLD, BB_BUILD_MID or BB_BUILD_NEW, bb-queue-lib.sh is BB_LIB_OLD or
#   BB_LIB_NEW, bb-queue is BB_QUEUE_OLD or BB_QUEUE_NEW, bb-tickets is absent or BB_TICKETS_NEW. Any mismatch prints `refused <target> <file> installed=<md5|absent>
#   expected=<pin>[|<pin>...]` (every accepted pin, OLD|MID|NEW where there is a MID) for every mismatch on every target and exits 65 having written nothing anywhere; an unreachable target is 69.
# Phase 2, per target and per file to change (bb-tickets, bb-queue-lib.sh, bb-queue, then bb-build: the lib before the tools that
#   source it), one remote `bash -s` script: receive the file as
#   ~/tools/<f>.new (base64 on stdin); its md5 must be the pinned one; re-check that the installed file is still what phase 1
#   saw (closes the race with phase 1); chmod 0755; cp -p an existing bb-build, bb-queue-lib.sh or bb-queue to <f>.bak-<oldmd5> (an existing .bak of that name is
#   kept; the older .pre-* / .bak-* backups are never touched); mv -f .new into place. The mv is atomic, so a running
#   `bb-build __run` waiter keeps reading the old inode instead of changing bytes under it.
# Output per file: `installed <target> <f> before=<md5|absent> after=<md5>` or `unchanged <target> <f> <md5>`.
# Exit: 0 done or nothing to do; 2 usage (no target or a bad one); 78 no ssh key configured; 65 refused (phase 2: a re-check refusal on the named target); 69 unreachable in
#   phase 1; 74 a phase-2 failure (files already moved stay installed; each mv was whole).
# Uninstall: mv -f ~/tools/bb-build.bak-999aa32577930aaf715ed4ce7c31fe02 ~/tools/bb-build; rm -f ~/tools/bb-tickets
#   mv -f ~/tools/bb-queue-lib.sh.bak-814400ff1d980be4b2da3e8e0ec62644 ~/tools/bb-queue-lib.sh
#   mv -f ~/tools/bb-queue.bak-7efbfbecb9739fed35223cdc97eb185d ~/tools/bb-queue
set -euo pipefail

readonly BB_BUILD_OLD=999aa32577930aaf715ed4ce7c31fe02
readonly BB_LIB_OLD=814400ff1d980be4b2da3e8e0ec62644
readonly BB_LIB_NEW=621b2252c3da05e7dd1161d076908864
readonly BB_QUEUE_OLD=7efbfbecb9739fed35223cdc97eb185d
readonly BB_QUEUE_NEW=a04b7be02f840d4f1e2bd19c54c52b3f
readonly BB_BUILD_MID=a1100c916b866ad3716bc4095ddb1991   # the T3 fields version, before the waiter's first-writer-wins guard
readonly BB_BUILD_NEW=cae49573d06f632d3081f7d5be1e9b66
readonly BB_TICKETS_NEW=472cd816644fe0ee0902b8da3d33de8e
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

# ---- phase 0: the local files are the reviewed ones
[ "$(md5_of "$HERE/bb-build")" = "$BB_BUILD_NEW" ] && [ "$(md5_of "$HERE/bb-tickets")" = "$BB_TICKETS_NEW" ] \
  && [ "$(md5_of "$HERE/bb-queue-lib.sh")" = "$BB_LIB_NEW" ] && [ "$(md5_of "$HERE/bb-queue")" = "$BB_QUEUE_NEW" ] \
  || { echo "install-bb-fields: local file is not the reviewed one" >&2; exit 65; }

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
PROBE='for f in bb-build bb-queue-lib.sh bb-queue bb-tickets; do
  if [ -f "$HOME/tools/$f" ]; then printf "%s %s\n" "$f" "$(md5sum < "$HOME/tools/$f" | cut -d" " -f1)"; else printf "%s absent\n" "$f"; fi
done'
BV=(); KV=(); LV=(); QV=()   # per target index: the installed bb-build / bb-tickets / bb-queue-lib.sh / bb-queue md5 (or absent), as phase 1 saw it
refused=0
i=0
for t in "${targets[@]}"; do
  if ! out=$("${SSH[@]}" "$t" bash -s <<< "$PROBE"); then
    echo "install-bb-fields: $t unreachable (phase 1); nothing written" >&2; exit 69
  fi
  b=""; l=""; k=""; q=""
  for f in bb-build bb-queue-lib.sh bb-queue bb-tickets; do
    m=$(printf '%s\n' "$out" | awk -v f="$f" '$1 == f { print $2 }' | head -n 1)
    [ -n "$m" ] || { echo "install-bb-fields: $t gave no answer for $f; nothing written" >&2; exit 69; }
    case $f in bb-build) b=$m;; bb-queue-lib.sh) l=$m;; bb-queue) q=$m;; bb-tickets) k=$m;; esac
  done
  BV[i]=$b; KV[i]=$k; LV[i]=$l; QV[i]=$q; i=$((i + 1))
  if [ "$b" != "$BB_BUILD_OLD" ] && [ "$b" != "$BB_BUILD_MID" ] && [ "$b" != "$BB_BUILD_NEW" ]; then
    echo "refused $t bb-build installed=$b expected=$BB_BUILD_OLD|$BB_BUILD_MID|$BB_BUILD_NEW"; refused=1; fi
  if [ "$l" != "$BB_LIB_OLD" ] && [ "$l" != "$BB_LIB_NEW" ]; then echo "refused $t bb-queue-lib.sh installed=$l expected=$BB_LIB_OLD|$BB_LIB_NEW"; refused=1; fi
  if [ "$q" != "$BB_QUEUE_OLD" ] && [ "$q" != "$BB_QUEUE_NEW" ]; then echo "refused $t bb-queue installed=$q expected=$BB_QUEUE_OLD|$BB_QUEUE_NEW"; refused=1; fi
  if [ "$k" != absent ] && [ "$k" != "$BB_TICKETS_NEW" ]; then
    echo "refused $t bb-tickets installed=$k expected=$BB_TICKETS_NEW"; refused=1; fi
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
  install_one "$t" bb-tickets "$BB_TICKETS_NEW" "${KV[i]}"
  install_one "$t" bb-queue-lib.sh "$BB_LIB_NEW" "${LV[i]}"
  install_one "$t" bb-queue "$BB_QUEUE_NEW" "${QV[i]}"
  install_one "$t" bb-build "$BB_BUILD_NEW" "${BV[i]}"
  i=$((i + 1))
done
