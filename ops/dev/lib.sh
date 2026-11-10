# ops/dev/lib.sh -- shared checks of the ops/dev tools (sourced by preflight, wt-new, wt-prune, land-pr and
# hooks/pre-push; not run on its own).
# Bash 3.2 and BSD tools (macOS) as well as GNU (Linux).

# Free space required on the volume holding a tree, in GiB.
MIN_FREE_GIB=${TEMPER_MIN_FREE_GIB:-20}

existing_dir() {  # existing_dir <path>: the path, or its nearest existing ancestor
  local path=$1
  while [ ! -d "$path" ]; do path=$(dirname "$path"); done
  printf '%s\n' "$path"
}

free_kib() {  # free_kib <path>: free KiB on the volume holding <path> (or its nearest existing ancestor)
  df -Pk "$(existing_dir "$1")" | awk 'NR == 2 { print $4 }'
}

disk_ok() {  # disk_ok <path>: prints the verdict; 0 when at least MIN_FREE_GIB GiB are free
  case "$MIN_FREE_GIB" in ''|*[!0-9]*) echo "FAILED: disk: TEMPER_MIN_FREE_GIB '$MIN_FREE_GIB' is not a whole number"; return 1 ;; esac
  local free
  free=$(free_kib "$1")
  if [ "$free" -ge $((MIN_FREE_GIB * 1024 * 1024)) ]; then
    echo "ok: disk: $((free / 1024 / 1024)) GiB free on the volume holding $1 (need $MIN_FREE_GIB)"
  else
    echo "FAILED: disk: $((free / 1024 / 1024)) GiB free on the volume holding $1, need $MIN_FREE_GIB (TEMPER_MIN_FREE_GIB); run ops/dev/wt-prune"
    return 1
  fi
}

hardlinks_of() {  # hardlinks_of <dir>: the regular files under <dir> with more than one link
  find "$1" -type f -links +1
}

m2_ok() {  # m2_ok <tree>: .m2-local is a directory, not a symlink, holding no hard-linked file (a write through either
  local m2=$1/.m2-local linked  # reaches the seed; find does not descend a symlinked root, so that is checked first)
  [ ! -L "$m2" ] || { echo "FAILED: m2: $m2 is a symlink (to $(readlink "$m2")), a build would write into its target; seed it with cp -a"; return 1; }
  [ -d "$m2" ] || { echo "FAILED: m2: $m2 does not exist; seed it with cp -a (docs/developer-guide.md)"; return 1; }
  linked=$({ hardlinks_of "$m2" || true; } | head -n 3)
  [ -z "$linked" ] || {
    echo "FAILED: m2: $m2 has hard-linked files (cp -al?), a build would write into the seed:"
    printf '%s\n' "$linked" | sed 's/^/  /'
    return 1
  }
  echo "ok: m2: $m2 exists, no hard links"
}

module_targets() {  # module_targets <tree>: the Maven module target/ dirs of <tree> (beside a pom.xml), relative
  (cd "$1" && find . -path ./.m2-local -prune -o -type d -name target -exec test -f {}/../pom.xml \; -print -prune) \
    | sed 's|^\./||'
}

mtime_of() {  # mtime_of <path>: modification time in epoch seconds (GNU stat, else BSD stat)
  stat -c %Y "$1" 2> /dev/null || stat -f %m "$1"
}

# A build lock is the directory <tree>/.wp-logs/build.lock, made by mkdir; its `pid` file lists one pid per line (the
# build's, and maven's once it runs), and its optional `lstart` file the start time of each, line for line, as
# `LC_ALL=C TZ=UTC ps -o lstart=` printed it. A listed pid (> 0) is alive when `kill -0` finds it (EPERM included) and,
# when its lstart line holds a start time and ps reports one now, the process has that start (otherwise the pid was
# reused); an unknown start on either side counts as alive. The pid is written after the mkdir, so a
# directory without a usable pid is a lock being taken while it is younger than LOCK_TAKING_SECONDS, stale after.
# Siblings such as build.guard and build.new-* are not locks.
LOCK_TAKING_SECONDS=10

lock_pids() {  # lock_pids <lock>: "<line> <pid>" for every line of <lock>/pid (or of <lock>, a plain file) that is a pid
  local file=$1  # and nothing else: `host-1: 42` names no pid of this machine
  [ -d "$file" ] && file=$file/pid
  [ -f "$file" ] || return 0
  awk '/^[0-9]+$/ && $0 + 0 > 0 { print NR, $0 }' "$file"
}

started() {  # started <pid>: its start time as the lock records it, blanks squeezed
  LC_ALL=C TZ=UTC ps -o lstart= -p "$1" 2> /dev/null | tr -s ' ' | sed -e 's/^ //' -e 's/ $//'
}

LSTART_SHAPE='^[A-Z][a-z]{2} [A-Z][a-z]{2} [0-9]{1,2} [0-9]{2}:[0-9]{2}:[0-9]{2} [0-9]{4}$'

pid_alive() {  # pid_alive <lock> <line> <pid>: the process lives and, when the lock recorded its start, is that one
  local recorded now out  # kill -0, not ps (which can fail): EPERM is another user's live process
  out=$(LC_ALL=C kill -0 "$3" 2>&1) || case "$out" in *"not permitted"*) ;; *) return 1 ;; esac
  [ -f "$1/lstart" ] || return 0
  recorded=$(sed -n "$2p" "$1/lstart" | tr '\t\r' '  ' | tr -s ' ' | sed -e 's/^ //' -e 's/ $//')
  [[ "$recorded" =~ $LSTART_SHAPE ]] || return 0  # no usable start recorded: cannot tell reuse apart, so alive
  now=$(started "$3")
  [ -z "$now" ] || [ "$recorded" = "$now" ]  # ps failing now: start unknown, alive (fails closed)
}

lock_held() {  # lock_held <tree>: 0 when the build lock is held; prints why ("by live pid N" or "being taken: ...")
  local lock=$1/.wp-logs/build.lock pids line pid age
  [ -e "$lock" ] || return 1
  pids=$(lock_pids "$lock")
  while read -r line pid; do
    [ -n "$pid" ] || continue
    pid_alive "$lock" "$line" "$pid" && { echo "by live pid $pid"; return 0; }
  done <<< "$pids"
  [ -z "$pids" ] || return 1  # every listed pid is gone (or reused): stale
  age=$(( $(date +%s) - $(mtime_of "$lock") ))
  [ "$age" -lt "$LOCK_TAKING_SECONDS" ] || return 1
  echo "being taken (no pid yet, ${age}s old)"
}

lock_ok() {  # lock_ok <tree>
  local why
  if why=$(lock_held "$1"); then
    echo "FAILED: lock: $1/.wp-logs/build.lock is held $why"
    return 1
  fi
  echo "ok: lock: no live build lock in $1"
}

docker_socket() {  # docker_socket: the unix socket to ping when there is no docker CLI
  case "${DOCKER_HOST:-}" in
    unix://*) echo "${DOCKER_HOST#unix://}"; return ;;
    ?*) return ;;  # tcp:// and the like: no socket to ping
  esac
  local socket
  for socket in "$HOME/.colima/default/docker.sock" /var/run/docker.sock; do
    [ -S "$socket" ] && { echo "$socket"; return; }
  done
}

bounded() {  # bounded <seconds> <command...>: runs the command, killed after <seconds> (no coreutils timeout on macOS)
  local seconds=$1 pid watcher rc=0
  shift
  "$@" &
  pid=$!
  ( sleep "$seconds"; kill "$pid" 2> /dev/null ) > /dev/null 2>&1 &
  watcher=$!
  wait "$pid" || rc=$?
  kill "$watcher" 2> /dev/null || true
  wait "$watcher" 2> /dev/null || true
  return $rc
}

docker_ok() {  # docker_ok: the docker CLI is on PATH and `docker info` answers; without the CLI, says what the socket does
  local out server socket daemon
  if command -v docker > /dev/null 2>&1; then
    # The server's version proves the daemon answered: docker 28 exits 0 with --format even when it cannot connect.
    out=$(bounded 20 docker info --format 'server={{.ServerVersion}} {{.Name}}' 2>&1) || true
    server=$(printf '%s\n' "$out" | sed -n 's/^server=\([^ ][^ ]*\) \(.*\)$/\2 \1/p' | head -n 1)
    if [ -n "$server" ]; then
      echo "ok: docker: reachable via docker info ($server)"
      return 0
    fi
    echo "FAILED: docker: docker info failed: $(printf '%s' "$out" | tail -n 1); is colima running? (colima start)"
    return 1
  fi
  # Testcontainers needs only the socket, but PgDumpIT and the worker exec `docker`: without the CLI the build fails.
  socket=$(docker_socket)
  if [ -z "$socket" ]; then
    daemon="no unix socket either (DOCKER_HOST='${DOCKER_HOST:-}'); is colima running?"
  elif out=$(curl -s --max-time 10 --unix-socket "$socket" http://localhost/_ping 2>&1) && [ "$out" = OK ]; then
    daemon="the daemon answers on socket $socket; put the CLI on PATH (brew link docker)"
  else
    daemon="and socket $socket does not answer _ping; is colima running? (colima start)"
  fi
  echo "FAILED: docker: no docker CLI on PATH (temper's tests exec docker); $daemon"
  return 1
}

tc_property() {  # tc_property <name>: its value in ~/.testcontainers.properties, empty when unset
  [ -f "$HOME/.testcontainers.properties" ] || return 0
  awk -v key="$1" 'index($0, "=") {
      name = substr($0, 1, index($0, "=") - 1); gsub(/^[ \t]+|[ \t]+$/, "", name)
      if (name == key) { value = substr($0, index($0, "=") + 1); gsub(/^[ \t]+|[ \t\r]+$/, "", value) }
    } END { print value }' "$HOME/.testcontainers.properties"
}

tc_host() {  # tc_host: "<host> (<source>)", the docker host Testcontainers 1.21 picks, in its strategy order
  local value socket
  value=${TC_HOST:-$(tc_property tc.host)}
  [ -z "$value" ] || { echo "$value (tc.host)"; return; }
  [ -z "${DOCKER_HOST:-}" ] || { echo "$DOCKER_HOST (DOCKER_HOST)"; return; }
  value=$(tc_property docker.host)
  [ -z "$value" ] || { echo "$value (docker.host in ~/.testcontainers.properties)"; return; }
  for socket in /var/run/docker.sock "$HOME/.docker/run/docker.sock"; do
    [ -S "$socket" ] && { echo "unix://$socket (default socket)"; return; }
  done
  echo "none (no DOCKER_HOST, no docker.host, no default socket)"
}

cli_host() {  # cli_host: the docker host the CLI (and so `docker info`) uses: DOCKER_HOST, else the current context's
  [ -z "${DOCKER_HOST:-}" ] || { echo "$DOCKER_HOST"; return; }
  bounded 20 docker context inspect --format '{{.Endpoints.docker.Host}}' 2> /dev/null | head -n 1
}

real_path() {  # real_path <path>: <path> with every symlink resolved, its directories' and its own (no readlink -f on macOS)
  local path=$1 dir link hops=0
  while :; do
    dir=$(cd -P "$(dirname "$path")" 2> /dev/null && pwd) || break
    path=$dir/$(basename "$path")
    [ -L "$path" ] && [ "$hops" -lt 40 ] || break
    hops=$((hops + 1))
    link=$(readlink "$path")
    case "$link" in /*) path=$link ;; *) path=$dir/$link ;; esac
  done
  printf '%s\n' "$path"
}

host_key() {  # host_key <host>: a unix:// host with its socket path resolved; any other host as given
  case "$1" in
    unix://*) echo "unix://$(real_path "${1#unix://}")" ;;
    *) echo "$1" ;;
  esac
}

docker_host_ok() {  # docker_host_ok: Testcontainers talks to the daemon `docker info` checked (with the CLI on PATH)
  local tc cli
  tc=$(tc_host)
  if ! command -v docker > /dev/null 2>&1; then
    echo "ok: docker-host: Testcontainers will use $tc (no docker CLI to compare with)"
    return 0
  fi
  cli=$(cli_host || true)
  # Socket paths compare resolved: a symlink to colima's socket (or /var/run/docker.sock linked to it) is that daemon.
  if [ -n "$cli" ] && [ "$(host_key "${tc% (*}")" = "$(host_key "$cli")" ]; then
    echo "ok: docker-host: Testcontainers will use $tc, the host docker info used"
  else
    echo "FAILED: docker-host: Testcontainers will use $tc but docker info used '${cli:-unknown}'; set DOCKER_HOST (or docker.host in ~/.testcontainers.properties) to $cli (resolved: $(host_key "${tc% (*}") vs $(host_key "$cli"))"
    return 1
  fi
}

# The commit rule of docs/developer-guide.md §7, on a message as git stored it (used by hooks/pre-push and land-pr).
COMMIT_SUBJECT='^(feat|fix|refactor|chore|docs|test)(\([^()]+\))?: .+$'

message_fault() {  # message_fault <commit>: how its stored message breaks the one-line rule; empty when it keeps it
  local message line
  message=$(git cat-file commit "$1" | sed '1,/^$/d'; echo .)
  message=${message%.}
  line=${message%$'\n'}
  if [[ "$line" == *$'\r'* ]]; then
    echo "has a carriage return: '$(printf '%s' "$line" | tr -d '\r' | head -n 1)'"
  elif [[ "$line" == *$'\n'* ]]; then
    echo "has more than one line (a body or trailers): '$(printf '%s' "$line" | head -n 1)'"
  elif ! [[ "$line" =~ $COMMIT_SUBJECT ]]; then
    echo "does not start with a conventional prefix: '$line'"
  fi
}
