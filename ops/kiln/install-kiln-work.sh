#!/usr/bin/env bash
# install-kiln-work.sh -- OWNER action: let the Mac's kiln_work key record work ledger events on bigboy (kiln Work tab).
#
# Run ON bigboy, as the user kiln runs as (aether), from a checkout holding this change:
#   1. Mac:    ssh-keygen -t ed25519 -N '' -C kiln-work -f ~/.ssh/kiln_work
#              scp ~/.ssh/kiln_work.pub <user>@<bigboy address>:kiln_work.pub
#   2. bigboy: ops/kiln/install-kiln-work.sh ~/kiln_work.pub        (add --dry-run first to see what it would do)
#   3. Mac:    ops/kiln/kiln-work T0-SMOKE design --round 0 --note "install check"   -> {"event":N,...}
#      (kiln must already run a jar with POST /api/v1/ingest/work and migration V5; otherwise kiln answers 404, exit 22.)
#
# It installs ops/kiln/kiln-ingest as ~/tools/kiln-ingest (mode 0755; the `mac` verb is unchanged, `work` is added) and
# appends ONE line to ~/.ssh/authorized_keys, after copying the file to authorized_keys.bak-<UTC stamp>:
#   restrict,command="exec ~/tools/kiln-ingest work" ssh-ed25519 AAAA... kiln-work
# `restrict` disables forwarding, a pty and ~/.ssh/rc; the forced command ignores any command the client names (and
# kiln-ingest refuses one). No from= (Q-16: the Mac's LAN address is not fixed), as for the kiln_ingest key.
#
# Refused (exit 3), with nothing changed: a key file that is not exactly one `ssh-ed25519 <base64> [comment]` line, a
# key already present in authorized_keys (any options), or a missing ops/kiln/kiln-ingest next to this script.
# The ledger it feeds (work_item, work_event) is the one kiln table set that is NOT a cache: nothing rebuilds it. Before
# dropping or recreating kiln-pg, back it up as data only (V5 creates the tables, so a dump with CREATE TABLE clashes):
#   docker exec kiln-pg pg_dump --data-only -U kiln -t work_item -t work_event kiln > work-ledger.sql
# Restore: start kiln once so V5 applies, `systemctl --user stop kiln`, then
#   docker exec -i kiln-pg psql -U kiln -d kiln -v ON_ERROR_STOP=1 < work-ledger.sql
# and start kiln again.
# Linux (bigboy) only: it uses GNU sha256sum and install.
# Exit codes: 0 installed (or, with --dry-run, would install), 2 usage, 3 refused.
set -euo pipefail
umask 077

usage() {
  echo "usage: $0 [--dry-run] <public key file>" >&2
  exit 2
}

refuse() {
  echo "refused: $*" >&2
  exit 3
}

DRY=no
[ "${1:-}" = --dry-run ] && { DRY=yes; shift; }
[ "$#" -eq 1 ] || usage
readonly PUB=$1
readonly HERE=$(cd "$(dirname "$0")" && pwd -P)
readonly SOURCE=$HERE/kiln-ingest
readonly TOOLS=$HOME/tools
readonly KEYS=$HOME/.ssh/authorized_keys

[ -f "$SOURCE" ] || refuse "$SOURCE is missing"
[ -f "$PUB" ] || refuse "$PUB is not a file"
[ "$(wc -l < "$PUB" | tr -d ' ')" -le 1 ] || refuse "$PUB holds more than one line"
read -r TYPE BLOB COMMENT < "$PUB" || true
[ "${TYPE:-}" = ssh-ed25519 ] || refuse "$PUB is not an ssh-ed25519 public key"
[[ ${BLOB:-} =~ ^AAAAC3NzaC1lZDI1NTE5[A-Za-z0-9+/]+=*$ ]] || refuse "$PUB holds no ed25519 key blob"
if [ -f "$KEYS" ] && grep -qF -- "$BLOB" "$KEYS"; then
  refuse "this key is already in $KEYS; remove its line first to re-bind it"
fi

readonly LINE="restrict,command=\"exec ~/tools/kiln-ingest work\" ssh-ed25519 $BLOB kiln-work"
readonly STAMP=$(date -u +%Y%m%dT%H%M%SZ)

if [ "$DRY" = yes ]; then
  echo "would install $SOURCE as $TOOLS/kiln-ingest (mode 0755)"
  [ -f "$KEYS" ] && echo "would copy $KEYS to $KEYS.bak-$STAMP"
  echo "would append to $KEYS: $LINE"
  exit 0
fi

mkdir -p "$TOOLS" "$HOME/.ssh"
chmod 700 "$HOME/.ssh"
[ -f "$TOOLS/kiln-ingest" ] && echo "replacing $TOOLS/kiln-ingest (sha256 $(sha256sum "$TOOLS/kiln-ingest" | cut -c1-64))"
install -m 0755 "$SOURCE" "$TOOLS/kiln-ingest"
echo "installed $TOOLS/kiln-ingest (sha256 $(sha256sum "$TOOLS/kiln-ingest" | cut -c1-64))"
if [ -f "$KEYS" ]; then
  cp -p "$KEYS" "$KEYS.bak-$STAMP"
  echo "backed up $KEYS as $KEYS.bak-$STAMP"
fi
# a last line without its newline would otherwise run into the new one
if [ -s "$KEYS" ] && [ "$(tail -c 1 "$KEYS" | wc -l | tr -d ' ')" -eq 0 ]; then
  echo >> "$KEYS"
fi
printf '%s\n' "$LINE" >> "$KEYS"
chmod 600 "$KEYS"
echo "appended: $LINE"
