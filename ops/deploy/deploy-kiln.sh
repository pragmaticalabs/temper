#!/usr/bin/env bash
# deploy-kiln.sh -- the kiln unit on lib.sh's plan-fragment hooks (D3.4a).
# One file: ~/.kiln/dist/kiln-service.jar. Host bigboy. Rolled back: the jar. Not rolled back: kiln-pg's schema and rows
# (forward-only), the unit file, kiln.toml, the scripts and keys (kiln-tools).
set -euo pipefail
umask 022
# shellcheck source=/dev/null
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

DL_UNIT=kiln
DL_OS=Linux
DL_HOST=bigboy  # a host name of the bb host config (ops/bb/hosts.example), resolved at stage
DL_BUILD_ARGS='-B clean install -pl kiln-core,kiln-service -am'
DL_SERVICE=kiln
: "${DL_HEALTH_TIMEOUT:=120}"
: "${DL_SETTLE:=15}"
: "${DL_KILN_URL:=http://127.0.0.1:7171}"

KILN_MAIN=org.pragmatica.kiln.service.Main
KILN_PROPS=org/pragmatica/kiln/service/kiln-version.properties

unit_files() { echo "kiln-service.jar $HOME/.kiln/dist/kiln-service.jar 0644"; }

unit_stage_collect() {  # <src> <dir>: exactly one fat jar, and the repo's unit file for the drift report
  local jars=( "$1"/kiln-service/target/kiln-service-*-all.jar )
  if [ "${#jars[@]}" -ne 1 ] || [ ! -f "${jars[0]}" ]; then
    echo "refused: want exactly one $1/kiln-service/target/kiln-service-*-all.jar" >&2
    return 1
  fi
  cp "${jars[0]}" "$2/files/kiln-service.jar"
  if [ -f "$1/ops/kiln/kiln.service" ]; then cp "$1/ops/kiln/kiln.service" "$2/kiln.service.repo"; fi
}

unit_stage_verify() {  # <dir> <commit>: Main-Class and a clean judge; prints what the plan checks
  local jar=$1/files/kiln-service.jar main judge
  main=$(dl_jar_main "$jar")
  [ "$main" = "$KILN_MAIN" ] || { echo "refused: kiln-service.jar Main-Class is '$main', not $KILN_MAIN" >&2; return 1; }
  judge=$(dl_jar_prop "$jar" "$KILN_PROPS" judge)
  dl_clean_judge kiln-service.jar "$judge" "$2" || return 1
  echo "judge $judge"
  echo "main-class kiln-service.jar=$main"
}

unit_schema() {
  $DL_DOCKER exec kiln-pg psql -U kiln -d kiln -Atc 'SELECT max(version) FROM schema_migrations'
}

unit_preconditions() {
  local s
  unit_stage_verify "$DL_STAGE_DIR" "$DL_COMMIT" || return 1
  if [ ! -d "$HOME/.kiln/dist" ] || [ -L "$HOME/.kiln/dist" ]; then
    echo "refused: $HOME/.kiln/dist is not a directory (or is a link)" >&2
    return 1
  fi
  echo "not-linked $HOME/.kiln/dist"
  s=$(dl_read_schema)
  dl_is_int "$s" || { echo "refused: kiln-pg did not answer a schema version" >&2; return 1; }
  echo "schema V$s"
}

unit_plan_prepare() {  # kiln keeps running the old jar until the swap; a bad dump stops everything here
  echo "dump kiln-pg kiln <B>/kiln-pg.dump"
  echo "verify-dump <B>/kiln-pg.dump TABLE"
}

unit_probes() {  # <which>: the same probes for new and old
  echo "KH1 fresh-active,kiln"
  echo "KH2 200,/,body~<title>kiln:[[:space:]]dashboard</title>"
  echo "KH3 200,/api/v1/sources,body~^\\{\"sources\":\\["
  echo "KH4 421,/,host=evil.example"
}

unit_health() {  # <which>
  local ok=0
  dl_fresh_active kiln "$DL_PID_BEFORE" || { echo "probe kiln not freshly active"; ok=1; }
  dl_http 200 "$DL_KILN_URL/" --body '<title>kiln: dashboard</title>' || ok=1
  dl_http 200 "$DL_KILN_URL/api/v1/sources" --body '^\{"sources":\[' || ok=1
  dl_http 421 "$DL_KILN_URL/" --host evil.example || ok=1
  return "$ok"
}

unit_drift() {  # the installed unit file against the repo's at the staged commit: reported, never refused
  local live=$HOME/.config/systemd/user/kiln.service repo ls rs
  repo=$(cd "$(dirname "$0")" && pwd)/kiln.service.repo
  [ -f "$repo" ] || return 0
  ls=absent
  [ ! -f "$live" ] || ls=$(dl_sha256 "$live")
  rs=$(dl_sha256 "$repo")
  [ "$ls" = "$rs" ] || echo "drift $live live=$ls repo=$rs"
}

dl_main "$@"
