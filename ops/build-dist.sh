#!/usr/bin/env bash
# build-dist.sh -- the root build with its judge (m2-design §1.7, ruling R-M2-10): the git commit of the tree being
# built, filtered into controller-version.properties and worker-version.properties as `judge=`, so every job and result
# a deployed controller writes names the build that judged it.
#
# Usage: ops/build-dist.sh [mvn args...]   (default: install; e.g. ops/build-dist.sh install -DskipTests)
#
# The judge is `git rev-parse HEAD`, with `-dirty` appended when `git status --porcelain` reports anything (a tracked
# change or an untracked file that is not ignored). ops/deploy-jars.sh refuses a dirty judge, and a plain `mvn install`
# builds as `unjudged`, which it refuses too. `.mvn/maven.config` is skip-worktree, so its local repository path never
# makes a tree dirty; `.m2-local/` and `target/` are ignored.
#
# Honest limit: the judge is the commit seen when the build starts; an edit made while it runs is not detected.
#
# Exit codes: 2 usage (a temper.judge argument in any spelling: the judge comes from git, never from a hand-typed
# value) or no git commit; otherwise the exit code of mvn.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

# Any spelling of a hand-typed judge is refused: `-Dtemper.judge=x`, `-D temper.judge=x`, `--define=temper.judge=x`,
# `--define temper.judge=x`. Ours is also passed LAST, after "$@", because Maven keeps the last definition of a property:
# a spelling this check misses still cannot override the judge from git.
for arg in "$@"; do
  case "$arg" in
    -Dtemper.judge|-Dtemper.judge=*|temper.judge|temper.judge=*|--define=temper.judge|--define=temper.judge=*)
      echo "build-dist: refused: $arg; the judge comes from git" >&2
      exit 2
      ;;
  esac
done

commit=$(git rev-parse HEAD 2> /dev/null) || { echo "build-dist: $root is not a git work tree with a commit" >&2; exit 2; }
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo "build-dist: HEAD is '$commit', not a 40-hex commit" >&2; exit 2; }

if [ -n "$(git status --porcelain)" ]; then
  judge=$commit-dirty
else
  judge=$commit
fi

[ $# -gt 0 ] || set -- install
echo "build-dist: judge=$judge" >&2
exec mvn -B "$@" -Dtemper.judge="$judge"
