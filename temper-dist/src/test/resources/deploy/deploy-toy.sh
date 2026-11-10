#!/usr/bin/env bash
# deploy-toy.sh -- the toy unit of DeployLibIT: one file, one service,
# probes on a loopback stub. It exercises every action kind of the plan grammar (D3.10) and is not a real deploy unit.
set -euo pipefail
umask 022
# shellcheck source=/dev/null
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

DL_UNIT=toy
DL_OS=$(uname -s)
DL_HOST=bigboy  # a host name of the sandbox host config (TestHosts), resolved at stage
DL_BUILD_ARGS='-B clean install -pl toy'
DL_SERVICE=toy
: "${DL_HEALTH_TIMEOUT:=120}"
: "${DL_SETTLE:=15}"
: "${DL_TOY_URL:=http://127.0.0.1:7171}"

unit_files() { echo "toy.bin $HOME/.toy/dist/toy.bin 0644"; }

unit_stage_collect() {  # <src> <dir>
  cp "$1/toy.bin" "$2/files/toy.bin"
}

unit_schema() {
  local v
  v=$($DL_DOCKER exec toy-pg psql -U toy -d toy -Atc 'SELECT max(version) FROM schema_migrations') || { echo unknown; return 0; }
  echo "$v"
}

unit_preconditions() {
  echo "schema $(dl_schema_one "$(dl_read_schema)")"
  if $DL_SYSTEMCTL --user is-active toy-timer.timer > /dev/null 2>&1; then echo "alarm-timer active"; else echo "alarm-timer inactive"; fi
}

# DL_TOY_FAIL=<hook>[:<which>] makes that plan hook exit 1 (DeployLibIT P43: every plan hook's rc is checked).
toy_fail() {
  if [ "${DL_TOY_FAIL:-}" = "$1" ]; then echo "toy: $1 fails" >&2; return 1; fi
}

# The restart hook exists only under DL_TOY_FAIL; without it the toy runs lib.sh's default `restart $DL_SERVICE`.
if [ -n "${DL_TOY_FAIL:-}" ]; then
  unit_plan_restart() { toy_fail "restart:$1" || return 1; echo "restart toy"; }
fi

# DL_TOY_DRIFT=<token> makes the toy report one unit-file drift (D3.4a unit_drift: DeployLibIT drift tests).
if [ -n "${DL_TOY_DRIFT:-}" ]; then
  unit_drift() { echo "drift $HOME/.config/systemd/user/toy.service live=$DL_TOY_DRIFT repo=repo-unit-sha"; }
fi

unit_plan_prepare() {
  toy_fail prepare || return 1
  echo "stop toy"
  echo "dump toy-pg toy <B>/toy-pg.dump"
  echo "verify-dump <B>/toy-pg.dump TABLE"
}

unit_plan_after() { toy_fail "after:$1" || return 1; echo "start toy-timer.timer"; }

unit_probes() {  # <which>
  toy_fail "probes:$1" || return 1
  echo "H1 fresh-active,toy"
  echo "H2 200,/health,body~^ok,header~Toy-Version:^$(expected_version "$1")\$"
  echo "H3 200,/hosts,token-file"
}

expected_version() {  # the first 12 hex of the set the probes expect: the new set, or the old one in a rollback
  if [ "$1" = old ]; then echo "${DL_SET_OLD:0:12}"; else echo "${DL_SET_NEW:0:12}"; fi
}

# DL_TOY_HOLD=<file>: the rollback's health check (which=old) touches the file and waits in the background (DL_BG, as the settle
# does) until a signal ends it: the deterministic window after the health-failed record (DeployLibHardeningIT).
toy_hold() {
  if [ "$1" = old ] && [ -n "${DL_TOY_HOLD:-}" ]; then
    : > "$DL_TOY_HOLD"
    sleep 30 > /dev/null 2>&1 &
    DL_BG=$!
    wait "$DL_BG" || true
    DL_BG=
  fi
}

# DL_TOY_DROP_FD=1: the health check closes the install's saved streams (fds 5 and 6), as a bash 3.2 with a closed stdout never had them:
# dl_cleanup must still release the lock and exit 0 (DeployLibHardeningIT, F-A).
toy_drop_fd() {
  if [ -n "${DL_TOY_DROP_FD:-}" ]; then exec 5>&- 6>&-; fi
}

# DL_TOY_REC_HOLD=<file>: after an install record was really appended, touch the file and wait in the background (DL_BG): the
# deterministic window between the record and `trap - INT TERM` (DeployLibHardeningIT, F-E exit code).
if [ -n "${DL_TOY_REC_HOLD:-}" ]; then
  eval "$(declare -f dl_record | sed '1s/^dl_record/dl_record_real/')"
  dl_record() {
    dl_record_real "$@"
    case "$1" in
      *' event=install '*)
        : > "$DL_TOY_REC_HOLD"
        sleep 30 > /dev/null 2>&1 &
        DL_BG=$!
        wait "$DL_BG" || true
        DL_BG=
        ;;
    esac
  }
fi

# DL_TOY_MANUAL=<file>: a unit_manual step (runs after the success record, the traps cleared) that touches the file and waits.
if [ -n "${DL_TOY_MANUAL:-}" ]; then
  unit_manual() {
    : > "$DL_TOY_MANUAL"
    sleep 8 > /dev/null 2>&1 &
    wait $! || true
  }
fi

unit_health() {  # <which>
  local ok=0
  toy_hold "$1"
  toy_drop_fd
  dl_fresh_active toy "$DL_PID_BEFORE" || { echo "probe toy not freshly active"; ok=1; }
  dl_http 200 "$DL_TOY_URL/health" --body '^ok' --header Toy-Version "^$(expected_version "$1")\$" || ok=1
  dl_http 200 "$DL_TOY_URL/hosts" --token-file "$HOME/.toy/token" || ok=1
  return "$ok"
}

dl_main "$@"
