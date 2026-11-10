#!/usr/bin/env bash
# deploy-toy2.sh -- the two-file toy unit of DeployLibGapsIT: toy.bin and second.bin, one service, probes on the same loopback
# stub as deploy-toy.sh. Two files make the swap order, and so the rollback's reverse order, visible. The test stages it as
# deploy-toy.sh (DL_UNIT is toy: the stub, DeploySandbox.stageDir and the staged name all say toy). Not a real deploy unit.
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

unit_files() {
  echo "toy.bin $HOME/.toy/dist/toy.bin 0644"
  echo "second.bin $HOME/.toy/dist/second.bin 0644"
}

unit_stage_collect() {  # <src> <dir>
  cp "$1/toy.bin" "$2/files/toy.bin"
  cp "$1/second.bin" "$2/files/second.bin"
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

unit_plan_prepare() {
  echo "stop toy"
  echo "dump toy-pg toy <B>/toy-pg.dump"
  echo "verify-dump <B>/toy-pg.dump TABLE"
}

unit_plan_after() { echo "start toy-timer.timer"; }

unit_probes() {  # <which>
  echo "H1 fresh-active,toy"
  echo "H2 200,/health,body~^ok"
  echo "H3 200,/hosts,token-file"
}

expected_version() {  # the first 12 hex of the set the probes expect: the new set, or the old one in a rollback
  if [ "$1" = old ]; then echo "${DL_SET_OLD:0:12}"; else echo "${DL_SET_NEW:0:12}"; fi
}

unit_health() {  # <which>
  local ok=0
  dl_fresh_active toy "$DL_PID_BEFORE" || { echo "probe toy not freshly active"; ok=1; }
  dl_http 200 "$DL_TOY_URL/health" --body '^ok' || ok=1
  dl_http 200 "$DL_TOY_URL/hosts" --token-file "$HOME/.toy/token" || ok=1
  return "$ok"
}

dl_main "$@"
