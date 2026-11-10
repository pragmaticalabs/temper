#!/usr/bin/env bash
# deploy-jars.sh -- put ONE build's controller and worker jars where the live controller reads them, and the pinned
# worker-launch where the Q-13 key's forced command runs it (the deploy runbook).
#
# Usage: ops/deploy-jars.sh <controller-jar> <worker-jar>
#   normally temper-dist/target/dist/temper-controller-<ver>-all.jar and .../temper-worker-<ver>-all.jar
#
# Both jars and the launcher are checked BEFORE anything is copied: each jar must carry its own Main-Class
# (org.pragmatica.temper.controller.Main, org.pragmatica.temper.worker.Main), both jars must name the same clean judge
# (m2-design §1.7, ruling R-M2-10: the `judge=` line of their version resources is one 40-hex commit, as
# ops/build-dist.sh writes it for a clean tree; a missing line, `unjudged` from a plain `mvn install`, a `-dirty` build,
# anything else, or two different judges is refused), and ops/worker-launch next to this script must have the pinned
# sha256 below. A thin module jar (temper-controller-<ver>.jar, temper-worker-<ver>.jar,
# no Main-Class since F5), the jars swapped, or a launcher that is not the pinned one is refused, and nothing is
# copied. So is a ~/.temper or ~/.temper/jobs that is a symbolic link (M1 pre-live verification N6): the launcher
# refuses evidence through it with 64, so every live job would end evidence=missing. Then each jar the deploy
# replaces, if it differs, is kept as <target>.bak-<its sha256> (N2: the rollback of the deploy runbook), and each jar is
# copied next to its target and renamed into place:
#   ~/.temper/dist/temper-controller.jar  (ops/temper-controller.service)
#   ~/.temper/dist/temper-worker.jar      ([worker] jar in controller.toml)
# and the sha256 of each and their judge are printed for the record. Then the launcher (M1 pre-live verification F1: the M0 launcher
# refuses the M1 `evidence` verb with 64, so every live job would end evidence=missing):
#   ~/.temper/bin/worker-launch           (the forced command of the Q-13 key in ~/.ssh/authorized_keys)
# the old one, if it differs, is kept as ~/.temper/bin/worker-launch.bak-<its sha256>; the new one is renamed into
# place (mode 700) and its sha256 checked again after the rename; both sha256s are printed for the record.
# This changes what the maintainer-approved Q-13 key runs: the pin is the maintainer-approved launcher, changed only with the
# maintainer's approval (the deploy runbook). Run it with the controller stopped; it does not reinstall the worker on the hosts
# (the deploy runbook, step 6).
#
# Exit codes: 0 deployed, 2 usage, 3 refused (a jar missing or without its Main-Class, a judge missing, unjudged, dirty,
# not 40 hex or not the same in both jars, the launcher not the pinned one, or a linked ~/.temper or ~/.temper/jobs;
# nothing copied), 4 a backup or copy failed (or the installed
# launcher's sha256 is not the pinned one): the state is partial, so do NOT start the controller; fix the cause and
# run the script again (the deploy runbook, step 4).
set -euo pipefail
umask 022

readonly CONTROLLER_MAIN=org.pragmatica.temper.controller.Main
readonly WORKER_MAIN=org.pragmatica.temper.worker.Main
readonly CONTROLLER_VERSION=org/pragmatica/temper/controller/controller-version.properties
readonly WORKER_VERSION=org/pragmatica/temper/worker/worker-version.properties
readonly DIST=$HOME/.temper/dist
readonly BIN=$HOME/.temper/bin
LAUNCHER=$(cd "$(dirname "$0")" && pwd)/worker-launch
readonly LAUNCHER
readonly LAUNCHER_COMMIT=3f9c081
readonly LAUNCHER_SHA256=c0fb07828176f219e7883c3d88f64f4210c097e89ab03b0003efcc952ad4976c

[ $# -eq 2 ] || { echo "usage: $0 <controller-jar> <worker-jar>" >&2; exit 2; }

sha256_of() {
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum < "$1" | cut -d' ' -f1
  else
    shasum -a 256 < "$1" | cut -d' ' -f1
  fi
}

main_class_of() {  # the Main-Class of a jar's manifest, empty when it has none
  unzip -p "$1" META-INF/MANIFEST.MF 2> /dev/null | tr -d '\r' | sed -n 's/^Main-Class: *//p' | head -n 1
}

checked() {  # checked <jar> <expected Main-Class>
  [ -f "$1" ] || { echo "refused: $1 is not a file" >&2; exit 3; }
  local found
  found=$(main_class_of "$1")
  [ "$found" = "$2" ] || {
    echo "refused: $1 has Main-Class '${found:-none}', expected $2 (a thin module jar? use the -all jar); nothing copied" >&2
    exit 3
  }
}

judge_of() {  # judge_of <jar> <version resource>: the judge= value of the resource (trimmed), empty when it has none
  { unzip -p "$1" "$2" 2> /dev/null | tr -d '\r' | sed -n 's/^[[:space:]]*judge=//p' | head -n 1 \
      | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'; } || true  # no resource: empty, refused below, not unzip's rc
}

clean_judge() {  # clean_judge <jar> <judge>: refuses a judge that is not one clean 40-hex commit (m2-design §1.7)
  case "$2" in
    '') echo "refused: $1 has no judge= line (built before the judge existed?); build with ops/build-dist.sh; nothing copied" >&2; exit 3 ;;
    unjudged) echo "refused: $1 is unjudged (a plain mvn build); build with ops/build-dist.sh; nothing copied" >&2; exit 3 ;;
    *-dirty) echo "refused: $1 has judge $2, built from a dirty tree; commit, then build with ops/build-dist.sh; nothing copied" >&2; exit 3 ;;
  esac
  [[ "$2" =~ ^[0-9a-f]{40}$ ]] || { echo "refused: $1 has judge '$2', not a 40-hex commit; nothing copied" >&2; exit 3; }
}

judged() {  # judged <controller-jar> <worker-jar>: both name the same clean judge; sets JUDGE
  local controller worker
  controller=$(judge_of "$1" "$CONTROLLER_VERSION")
  worker=$(judge_of "$2" "$WORKER_VERSION")
  clean_judge "$1" "$controller"
  clean_judge "$2" "$worker"
  [ "$controller" = "$worker" ] || {
    echo "refused: the controller jar has judge $controller, the worker jar $worker (two builds); nothing copied" >&2
    exit 3
  }
  JUDGE=$controller
}

not_linked() {  # refuses a ~/.temper or ~/.temper/jobs that is a symbolic link (N6)
  local path
  for path in "$HOME/.temper" "$HOME/.temper/jobs"; do
    [ ! -L "$path" ] || {
      echo "refused: $path is a symbolic link; the launcher refuses evidence through it (rc 64); nothing copied" >&2
      exit 3
    }
  done
}

kept() {  # kept <target> <jar>: the target, when it exists and differs from <jar>, copied to <target>.bak-<its sha256>
  [ -e "$1" ] || return 0
  local old
  old=$(sha256_of "$1")
  [ "$old" != "$(sha256_of "$2")" ] || return 0
  cp -p -- "$1" "$1.bak-$old" || { echo "FAILED: back up $1" >&2; exit 4; }
  echo "$1 was sha256 $old, kept as $1.bak-$old"
}

placed() {  # placed <jar> <target>
  cp -- "$1" "$2.partial" && mv -f -- "$2.partial" "$2" || { echo "FAILED: copy $1 -> $2" >&2; exit 4; }
  echo "$2 sha256 $(sha256_of "$2") from $1"
}

pinned() {  # pinned <launcher>: refuses a launcher whose sha256 is not the pinned one
  [ -f "$1" ] || { echo "refused: $1 is not a file; nothing copied" >&2; exit 3; }
  local found
  found=$(sha256_of "$1")
  [ "$found" = "$LAUNCHER_SHA256" ] || {
    echo "refused: $1 has sha256 $found, not the pinned $LAUNCHER_SHA256 ($LAUNCHER_COMMIT); nothing copied" >&2
    exit 3
  }
}

launcher_placed() {  # the pinned launcher into ~/.temper/bin/worker-launch, the old one kept, checked after
  local target=$BIN/worker-launch old=none
  mkdir -p "$BIN"
  if [ -e "$target" ]; then
    old=$(sha256_of "$target")
    if [ "$old" != "$LAUNCHER_SHA256" ]; then
      cp -p -- "$target" "$target.bak-$old" || { echo "FAILED: back up $target" >&2; exit 4; }
      echo "$target was sha256 $old, kept as $target.bak-$old"
    fi
  fi
  { cp -- "$LAUNCHER" "$target.partial" && chmod 700 "$target.partial" && mv -f -- "$target.partial" "$target"; } \
    || { echo "FAILED: copy $LAUNCHER -> $target" >&2; exit 4; }
  local now
  now=$(sha256_of "$target")
  [ "$now" = "$LAUNCHER_SHA256" ] || { echo "FAILED: $target has sha256 $now after the copy, not $LAUNCHER_SHA256" >&2; exit 4; }
  echo "$target sha256 $now ($LAUNCHER_COMMIT) from $LAUNCHER, was $old"
}

checked "$1" "$CONTROLLER_MAIN"
checked "$2" "$WORKER_MAIN"
judged "$1" "$2"
pinned "$LAUNCHER"
not_linked
mkdir -p "$DIST"
kept "$DIST/temper-controller.jar" "$1"
kept "$DIST/temper-worker.jar" "$2"
placed "$1" "$DIST/temper-controller.jar"
placed "$2" "$DIST/temper-worker.jar"
echo "judge $JUDGE (both jars)"
launcher_placed
