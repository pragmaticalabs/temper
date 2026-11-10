#!/usr/bin/env bash
# temper Postgres (m0-design §5.1). Idempotent: starts the container if it is stopped, creates it if it is absent,
# and does nothing if it is running. Running it on bigboy is an owner-visible action: ask before the first run.
#
#   ops/pg-up.sh                      # container temper-pg on 127.0.0.1:55432, volume temper-pg
#   TEMPER_PG_PORT=55433 TEMPER_PG_NAME=temper-pg-acc TEMPER_PG_VOLUME=temper-pg-acc ops/pg-up.sh   # acceptance
#   TEMPER_PG_NAME=kiln-pg TEMPER_PG_VOLUME=kiln-pg TEMPER_PG_PORT=55442 TEMPER_PG_DB=kiln TEMPER_PG_USER=kiln \
#     TEMPER_PG_PASSWORD_FILE=~/.config/kiln/pg-password ops/pg-up.sh                              # kiln (kiln-f1-design §6)
#
# TEMPER_PG_DB and TEMPER_PG_USER name the database and its owner, both `temper` by default (Q-10). They apply only when
# the container is created: an existing container keeps the database it was created with.
#
# The password is read from a 0600 file (never from argv or the environment of the container command line):
# TEMPER_PG_PASSWORD_FILE, default ~/.config/temper/pg-password. This script never creates or prints it.
set -euo pipefail

name="${TEMPER_PG_NAME:-temper-pg}"
volume="${TEMPER_PG_VOLUME:-temper-pg}"
port="${TEMPER_PG_PORT:-55432}"
password_file="${TEMPER_PG_PASSWORD_FILE:-$HOME/.config/temper/pg-password}"
db="${TEMPER_PG_DB:-temper}"
user="${TEMPER_PG_USER:-temper}"
image="postgres:16"

die() { echo "pg-up: $*" >&2; exit 1; }

[[ "$port" =~ ^[0-9]{1,5}$ ]] || die "TEMPER_PG_PORT must be a port number, got '$port'"
[[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "invalid container name '$name'"
[[ "$volume" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "invalid volume name '$volume'"
[[ "$db" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || die "TEMPER_PG_DB must be a lower-case SQL identifier, got '$db'"
[[ "$user" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || die "TEMPER_PG_USER must be a lower-case SQL identifier, got '$user'"
[[ -f "$password_file" ]] || die "password file $password_file is missing (create it with mode 0600)"

perms="$(stat -c '%a' "$password_file" 2>/dev/null || stat -f '%Lp' "$password_file")"
[[ "$perms" == "600" || "$perms" == "400" ]] || die "password file $password_file has mode $perms; expected 0600"
[[ -s "$password_file" ]] || die "password file $password_file is empty"

state="$(docker inspect --format '{{.State.Status}}' "$name" 2>/dev/null || true)"

case "$state" in
  running)
    echo "pg-up: $name is running"
    ;;
  created|exited|paused)
    if [[ "$state" == "paused" ]]; then docker unpause "$name" >/dev/null; else docker start "$name" >/dev/null; fi
    echo "pg-up: $name started (was $state)"
    ;;
  "")
    docker run -d --name "$name" --restart unless-stopped \
      -v "$volume":/var/lib/postgresql/data \
      -p "127.0.0.1:${port}:5432" \
      -e POSTGRES_DB="$db" -e POSTGRES_USER="$user" -e POSTGRES_PASSWORD_FILE=/run/secrets/pw \
      -v "$password_file":/run/secrets/pw:ro \
      "$image" -c timezone=UTC >/dev/null
    echo "pg-up: $name created on 127.0.0.1:${port}"
    ;;
  *)
    die "$name is in state '$state'; inspect it by hand"
    ;;
esac

for _ in $(seq 1 60); do
  if docker exec "$name" pg_isready -U "$user" -d "$db" -q; then
    echo "pg-up: $name accepts connections"
    exit 0
  fi
  sleep 1
done
die "$name did not become ready within 60 s"
