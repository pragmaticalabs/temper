#!/usr/bin/env bash
# seed-host.sh -- create the worker host's third-party Maven seed, ~/.temper/seed-m2 (m0-design §7.3, A13, R-M2).
#
# Owner-run at host registration, ON the worker host. It READS ~/work/m2-seed (Q-10: reading is allowed, writing
# under ~/work never is) and COPIES it -- GNU `cp -a --no-preserve=links`, never `-l`/hard links, and without
# reproducing links internal to the source (plain `cp -a` would) -- into a sibling of the target, verifies
# that no copied file has a link count above 1 and no symbolic link exists (the A13 check, `find -type f -links +1`
# and `find -type l` must be empty -- the worker's own check refuses both), and only then
# renames it into place. Every job's ws/.m2-local is in turn a copy of seed-m2, made by the worker.
#
# TEMPER_WORKER_HOME (as in ops/worker-launch) replaces ~/.temper: it seeds the acceptance host tier's own home.
# It must be an absolute path, as worker-launch and the worker require; a relative one is refused (exit 3).
#
# Usage: ops/seed-host.sh [--source DIR] [--replace]
#   --source DIR  the seed to copy (default ~/work/m2-seed)
#   --replace     replace an existing ~/.temper/seed-m2 (the old one is removed only after the new one verified);
#                 refused while a temper worker runs, because workers copy the seed into new workspaces
# Exit codes: 0 seeded, 2 usage, 3 refused (target exists, source missing or inside ~/.temper), 4 the copy failed
# the link-count check (nothing replaced).
set -euo pipefail
umask 077

SOURCE=$HOME/work/m2-seed
REPLACE=no
while [ $# -gt 0 ]; do
  case "$1" in
    --source) [ $# -ge 2 ] || { echo "usage: $0 [--source DIR] [--replace]" >&2; exit 2; }; SOURCE=$2; shift 2 ;;
    --replace) REPLACE=yes; shift ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "usage: $0 [--source DIR] [--replace]" >&2; exit 2 ;;
  esac
done

readonly TEMPER_HOME=${TEMPER_WORKER_HOME:-$HOME/.temper}
readonly TARGET=$TEMPER_HOME/seed-m2
readonly PARTIAL=$TEMPER_HOME/seed-m2.partial
readonly OLD=$TEMPER_HOME/seed-m2.old

[[ $TEMPER_HOME == /* ]] || { echo "refused: TEMPER_WORKER_HOME must be an absolute path" >&2; exit 3; }

[ -d "$SOURCE" ] || { echo "refused: seed source $SOURCE is not a directory" >&2; exit 3; }
SOURCE_REAL=$(cd "$SOURCE" && pwd -P)
mkdir -p "$TEMPER_HOME"
TEMPER_REAL=$(cd "$TEMPER_HOME" && pwd -P)
case "$SOURCE_REAL/" in
  "$TEMPER_REAL"/*) echo "refused: the source $SOURCE_REAL is inside $TEMPER_REAL" >&2; exit 3 ;;
esac
if [ -e "$TARGET" ] && [ "$REPLACE" != yes ]; then
  echo "refused: $TARGET exists (use --replace to replace it)" >&2
  exit 3
fi
# A worker copies seed-m2 into each new workspace; swapping it under a running worker could hand that copy a mix
# of old and new trees. Replacing is therefore refused while a temper worker of this user runs -- and also when
# that cannot be told: pgrep missing (rc 127) or failing (any rc other than 0 "found" / 1 "none") must not read
# as "none"; the `*)` branch refuses both.
if [ "$REPLACE" = yes ]; then
  running=0
  pgrep -u "$(id -u)" -f '\.temper/run/worker-.*\.jar' > /dev/null 2>&1 || running=$?
  case "$running" in
    0) echo "refused: a temper worker is running; stop the controller (which stops the worker) before --replace" >&2
       exit 3 ;;
    1) ;;
    *) echo "refused: pgrep failed (rc $running), cannot check for a running worker" >&2; exit 3 ;;
  esac
fi

rm -rf -- "$PARTIAL"
echo "copying $SOURCE_REAL -> $PARTIAL (cp -a --no-preserve=links, no hard links)"
mkdir -p "$PARTIAL"
cp -a --no-preserve=links -- "$SOURCE_REAL/." "$PARTIAL/"

linked=$( { find "$PARTIAL" -type f -links +1; find "$PARTIAL" -type l; } | head -n 5)
if [ -n "$linked" ]; then
  echo "FAILED: the copy holds hard-linked files (nlink > 1) or symbolic links, e.g.:" >&2
  echo "$linked" >&2
  rm -rf -- "$PARTIAL"
  exit 4
fi

files=$(find "$PARTIAL" -type f | wc -l | tr -d ' ')
if [ -e "$TARGET" ]; then
  rm -rf -- "$OLD"
  mv -- "$TARGET" "$OLD"
fi
mv -- "$PARTIAL" "$TARGET"
rm -rf -- "$OLD"
echo "seeded $TARGET: $files files, every one with link count 1"
