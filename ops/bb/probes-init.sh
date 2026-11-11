#!/usr/bin/env bash
# probes-init.sh -- creates ~/work/probes.git on a bb build host: the per-host, NOT mirrored repo that holds probe and mutation
# commits, so they never have to be pushed to the public GitHub repo. ~/work/pragmatica.git is a mirror (+refs/*:refs/*) and
# bb-checkout fetches it with --prune, so a ref pushed there directly is pruned; this repo has no remote and nothing prunes it
# except bb-probe-reap.
#
# Push (from a lane's tree): git push <host>:work/probes.git <sha>:refs/probes/<BB_OWNER>/<name>
#   <BB_OWNER> is [A-Za-z0-9_]+ (the bb-checkout rule), <name> is [A-Za-z0-9._-]+. A pre-receive hook rejects every other ref
#   (branches, tags, other namespaces, a bad owner or name), deletes of refs/probes/<owner>/<name> included in the allowed ones.
#   Check out with: bb-checkout <owner>-<dir> <sha>   or   bb-checkout <owner>-<dir> probes/<owner>/<name>
#
# Idempotent: re-running changes nothing and destroys nothing (an existing repo keeps its refs, objects and reflogs; the hook
# file is rewritten only when its bytes differ). Prints one line: `probes-init: created|unchanged|updated <path>`.
set -euo pipefail

REPO=${BB_PROBES_REPO:-$HOME/work/probes.git}
state=unchanged

if [ ! -e "$REPO" ]; then
  mkdir -p "$(dirname "$REPO")"
  git init -q --bare "$REPO"
  state=created
fi
[ "$(git -C "$REPO" rev-parse --is-bare-repository 2> /dev/null)" = true ] \
  || { echo "probes-init: $REPO exists and is not a bare repository; nothing changed" >&2; exit 1; }
if [ -n "$(git -C "$REPO" config --get-regexp '^remote\.' 2> /dev/null || true)" ]; then
  echo "probes-init: $REPO has a remote configured; it is not a probes repo; nothing changed" >&2; exit 1
fi

# the reaper ages a ref by its reflog creation time, so every ref update must write a reflog (a bare repo's default is off)
[ "$(git -C "$REPO" config --get core.logAllRefUpdates || true)" = always ] || { git -C "$REPO" config core.logAllRefUpdates always; [ "$state" = created ] || state=updated; }

HOOK=$(cat <<'HOOK_EOF'
#!/usr/bin/env bash
# pre-receive of ~/work/probes.git (installed by probes-init.sh): only refs/probes/<owner>/<name> may be created, moved or deleted.
export LC_ALL=C
bad=0
while read -r old new ref; do
  if [[ ! $ref =~ ^refs/probes/[A-Za-z0-9_]+/[A-Za-z0-9._-]+$ ]]; then
    echo "probes.git: rejected $ref: only refs/probes/<owner>/<name> (owner [A-Za-z0-9_]+, name [A-Za-z0-9._-]+) is accepted" >&2
    bad=1
  fi
done
exit $bad
HOOK_EOF
)
mkdir -p "$REPO/hooks"
if [ ! -f "$REPO/hooks/pre-receive" ] || [ "$(cat "$REPO/hooks/pre-receive")" != "$HOOK" ]; then
  tmp=$(mktemp "$REPO/hooks/.pre-receive.XXXXXX")
  printf '%s\n' "$HOOK" > "$tmp"
  chmod 0755 "$tmp"
  mv -f "$tmp" "$REPO/hooks/pre-receive"
  [ "$state" = created ] || state=updated
fi
echo "probes-init: $state $REPO"
