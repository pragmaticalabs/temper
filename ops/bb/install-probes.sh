#!/usr/bin/env bash
# install-probes.sh -- installs the reviewed ops/bb/bb-checkout (probes-aware) and ops/bb/bb-probe-reap into ~/tools on bb hosts
# and creates ~/work/probes.git there (ops/bb/probes-init.sh, run over ssh from stdin; it is not installed). The host's operator
# runs it, after the diff and md5s have been reviewed. Nothing in a build or a test ever runs it against a real host.
# Separate from install-bb-fields.sh on purpose: that script is pinned to a different file set.
#
# Usage: install-probes.sh user@address [user@address ...]     at least one target; there is no default host
#   Each target matches ^[a-z_][a-z0-9_-]{0,31}@[A-Za-z0-9.:-]+$. Ssh as install-bb-fields.sh: ssh -i <key> -o BatchMode=yes
#   -o ConnectTimeout=15 <target> <cmd>, <key> from the host config (~/.config/bb-temper/hosts or BB_TEMPER_HOSTS; none: exit 78);
#   BB_INSTALL_SSH (tests only) replaces everything before <target>, and then no config is read.
# Phase 0, local: bb-checkout, bb-probe-reap and probes-init.sh next to this script must be the pinned reviewed files, else exit 65.
# Phase 1, read-only, EVERY target first: the installed bb-checkout must be BB_CHECKOUT_OLD (the 2026-11-09 snapshot, identical on all
#   hosts) or BB_CHECKOUT_NEW, and bb-probe-reap absent or BB_REAP_NEW. A mismatch prints `refused <target> <file> installed=<md5|absent>
#   expected=<pin>[|<pin>]` and the run exits 65 having written nothing anywhere; an unreachable target is 69.
# Phase 2, per target: bb-probe-reap, then probes-init.sh (idempotent), then bb-checkout, each file as in install-bb-fields.sh:
#   ~/tools/<f>.new (base64 on stdin), md5 checked, installed file re-checked against phase 1, chmod 0755, cp -p an existing
#   bb-checkout to bb-checkout.bak-<oldmd5> (kept if it exists), atomic mv.
# Output: `installed <target> <f> before=<md5|absent> after=<md5>`, `unchanged <target> <f> <md5>`, and probes-init's own line.
# Exit: 0 done or nothing to do; 2 usage; 78 no ssh key; 65 refused; 69 unreachable in phase 1; 74 a phase-2 failure.
# Uninstall: mv -f ~/tools/bb-checkout.bak-7098ea8dee142600771968559e15e4db ~/tools/bb-checkout; rm -f ~/tools/bb-probe-reap
#   (~/work/probes.git holds only probe refs; remove it by hand if it is not wanted).
set -euo pipefail

readonly BB_CHECKOUT_OLD=7098ea8dee142600771968559e15e4db
readonly BB_CHECKOUT_NEW=07a63e0eacd70c7269a9bf38f699b23d
readonly BB_REAP_NEW=f322bb6d6e8967fe0d71c508ba3ae75d
readonly PROBES_INIT_NEW=a4b1b264837dda6f1e536fb4fba5c11d
readonly TARGET_RE='^[a-z_][a-z0-9_-]{0,31}@[A-Za-z0-9.:-]+$'

HERE=$(cd "$(dirname "$0")" && pwd)

md5_of() {
  if command -v md5sum > /dev/null 2>&1; then md5sum < "$1" | cut -d' ' -f1; else md5 -q "$1"; fi
}

[ $# -gt 0 ] || { echo "usage: install-probes.sh user@address [user@address ...] (no default host)" >&2; exit 2; }
targets=("$@")
for t in "${targets[@]}"; do
  [[ $t =~ $TARGET_RE ]] || { echo "install-probes: bad target '$t' (user@address)" >&2; exit 2; }
done

# ---- phase 0: the local files are the reviewed ones
[ "$(md5_of "$HERE/bb-checkout")" = "$BB_CHECKOUT_NEW" ] && [ "$(md5_of "$HERE/bb-probe-reap")" = "$BB_REAP_NEW" ] \
  && [ "$(md5_of "$HERE/probes-init.sh")" = "$PROBES_INIT_NEW" ] \
  || { echo "install-probes: local file is not the reviewed one" >&2; exit 65; }

if [ -n "${BB_INSTALL_SSH:-}" ]; then
  read -r -a SSH <<< "$BB_INSTALL_SSH"
else
  hosts=${BB_TEMPER_HOSTS:-$HOME/.config/bb-temper/hosts}; key=
  if [ -r "$hosts" ]; then
    dup=$(awk '{ sub(/\r$/, ""); sub(/#.*/, "") } $1 == "key" && NF == 2 && ++n == 2 { print NR; exit }' "$hosts")
    [ -z "$dup" ] || { echo "install-probes: $hosts: line $dup: key is listed twice" >&2; exit 78; }
    key=$(awk '{ sub(/\r$/, ""); sub(/#.*/, "") } $1 == "key" && NF == 2 { v = $2 } END { print v }' "$hosts")
  fi
  case $key in '~/'*) key=$HOME/${key#'~/'} ;; esac
  [ -n "$key" ] || { echo "install-probes: no ssh key: no 'key <path>' line in $hosts" >&2; exit 78; }
  SSH=(ssh -i "$key" -o BatchMode=yes -o ConnectTimeout=15)
fi

# ---- phase 1: read-only, every target
PROBE='for f in bb-checkout bb-probe-reap; do
  if [ -f "$HOME/tools/$f" ]; then printf "%s %s\n" "$f" "$(md5sum < "$HOME/tools/$f" | cut -d" " -f1)"; else printf "%s absent\n" "$f"; fi
done'
CV=(); RV=()   # per target index: the installed bb-checkout / bb-probe-reap md5 (or absent), as phase 1 saw it
refused=0
i=0
for t in "${targets[@]}"; do
  if ! out=$("${SSH[@]}" "$t" bash -s <<< "$PROBE"); then
    echo "install-probes: $t unreachable (phase 1); nothing written" >&2; exit 69
  fi
  c=$(printf '%s\n' "$out" | awk '$1 == "bb-checkout" { print $2 }' | head -n 1)
  r=$(printf '%s\n' "$out" | awk '$1 == "bb-probe-reap" { print $2 }' | head -n 1)
  [ -n "$c" ] && [ -n "$r" ] || { echo "install-probes: $t gave no answer; nothing written" >&2; exit 69; }
  CV[i]=$c; RV[i]=$r; i=$((i + 1))
  if [ "$c" != "$BB_CHECKOUT_OLD" ] && [ "$c" != "$BB_CHECKOUT_NEW" ]; then
    echo "refused $t bb-checkout installed=$c expected=$BB_CHECKOUT_OLD|$BB_CHECKOUT_NEW"; refused=1; fi
  if [ "$r" != absent ] && [ "$r" != "$BB_REAP_NEW" ]; then
    echo "refused $t bb-probe-reap installed=$r expected=absent|$BB_REAP_NEW"; refused=1; fi
done
[ "$refused" = 0 ] || { echo "install-probes: refused; nothing written on any target" >&2; exit 65; }

# ---- phase 2: per target
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
if [ "$old" != absent ]; then [ -e "$d/$f.bak-$old" ] || cp -p "$d/$f" "$d/$f.bak-$old"; fi
mv -f "$d/$f.new" "$d/$f"
md5sum < "$d/$f" | cut -d" " -f1'
install_one() {  # install_one <target> <file> <new md5> <installed md5 as phase 1 saw it>
  local t=$1 f=$2 new=$3 old=$4 script after rc errf err
  if [ "$old" = "$new" ]; then echo "unchanged $t $f $new"; return 0; fi
  script="$REMOTE_HEAD"$'\n'"$(base64 < "$HERE/$f")"$'\n'"PAYLOAD"$'\n'"$REMOTE_TAIL"
  errf=$(mktemp); rc=0; after=$("${SSH[@]}" "$t" bash -s -- "$f" "$new" "$old" <<< "$script" 2> "$errf") || rc=$?
  err=$(cat "$errf"); rm -f "$errf"; [ -z "$err" ] || echo "$err" >&2
  if [ "$rc" = 65 ]; then
    if grep -q '^payload md5 ' <<< "$err"; then echo "install-probes: refused: payload md5 mismatch after transfer ($f on $t); nothing replaced" >&2
    else echo "install-probes: $t $f: refused by the phase-2 re-check; nothing replaced" >&2; fi
    exit 65
  fi
  if [ "$rc" != 0 ]; then echo "install-probes: $t $f: phase 2 failed (rc $rc)" >&2; exit 74; fi
  echo "installed $t $f before=$old after=$after"
}
init_probes() {  # init_probes <target>: probes-init.sh from stdin; idempotent
  local t=$1
  "${SSH[@]}" "$t" bash -s < "$HERE/probes-init.sh" || { echo "install-probes: $t probes-init failed" >&2; exit 74; }
}
i=0
for t in "${targets[@]}"; do
  install_one "$t" bb-probe-reap "$BB_REAP_NEW" "${RV[i]}"
  init_probes "$t"
  install_one "$t" bb-checkout "$BB_CHECKOUT_NEW" "${CV[i]}"
  i=$((i + 1))
done
