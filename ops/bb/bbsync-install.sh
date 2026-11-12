#!/bin/bash
# bbsync-install.sh -- operator wrapper for the post-#17 deploy: runs ops/bb/install-bb-fields.sh, as it is on origin/main, against
# the given targets (e.g. aether@cloudbb-3 aether@cloudbb-4), then prints each target's bb-queue header.
# Refuses unless origin/main contains PR #17's squash-merge commit 48219389 (its head 5d617ab is never an ancestor after a squash).
# Notes (does not refuse) when install-bb-fields.sh changed on main since #17: main's pinned file set is what gets installed.
# Runs from a fresh detached worktree at origin/main (~/temper-wt/bbsync-deploy, replaced each run) of the repo holding this script.
# The post-check ssh uses the bare host name, so it relies on ~/.ssh/config aliases (jump host included); a failed check only warns.
set -euo pipefail
[ $# -ge 1 ] || { echo "usage: $0 aether@<host> ..." >&2; exit 2; }
R=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
cd "$R" && git fetch -q origin
git merge-base --is-ancestor 482193892029f5be9315280e4e51eb30c18c68a2 origin/main || { echo "REFUSED: #17 merge 48219389 not in origin/main" >&2; exit 1; }
git diff --quiet 48219389 origin/main -- ops/bb/install-bb-fields.sh || echo "NOTE: install-bb-fields.sh changed on main since #17; installing main's pinned set" >&2
W=~/temper-wt/bbsync-deploy; [ "$R" -ef "$W" ] && { echo "REFUSED: run from a tree other than $W (it is replaced)" >&2; exit 2; }
[ -e "$W" ] && git worktree remove --force "$W"
git worktree prune
git worktree add -q --detach "$W" origin/main
echo "main=$(git -C "$W" rev-parse --short HEAD)"
rc=0; bash "$W/ops/bb/install-bb-fields.sh" "$@" || rc=$?
for t in "$@"; do h=${t#*@}; echo "== $h"; ssh -o BatchMode=yes "$h" '~/tools/bb-queue 2>&1 | head -4' || true; done
exit $rc
