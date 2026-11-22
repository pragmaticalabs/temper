#!/usr/bin/env bash
# bb-install-hold.sh <host>... -- <install command> [<arg>...]
#   Freeze the bb queue of every <host> (an ssh destination, e.g. aether@bigboy-aether) without killing or cancelling any ticket, run
#   <install command> once, here, while every queue is held, then release every hold. Issue #93, CTO ruling 2026-11-22: the owner
#   script runs this file from a checkout at the merged sha, never from an installed copy. BbInstallHoldIT runs it for real.
# Mechanism: the queue's own lock. Every grant, enqueue and finalize takes ~/work/queue/.lock (flock), so a process holding it freezes
#   the queue without touching a ticket. Per host, in argument order:
#   1. poll (every POLL_SECS, up to DRAIN_SECS) until running/ and waiting/ are both empty; nothing is cancelled, nothing is signalled;
#   2. the host joins SPAWNED (so a release reaches it even if the spawn call fails), a WANT token is created there and a HOLDER is
#      started: it waits for the lock at most HOLD_SECS, exits at once if WANT is gone (a holder still blocked in flock after its hold
#      was given up exits when it gets the lock: the orphan-hold fix), writes HOLDF, and holds while WANT exists, is fresher than
#      HB_STALE s and HOLD_SECS has not passed; then it removes HOLDF and exits;
#   3. the host gets a heartbeat loop of its own: every HB_EVERY s it touches WANT. Hosts are refreshed independently, so a stalled or
#      failing call to one host delays no other host's refresh, and a failed call never ends a loop. (#93: the zsh loop this replaces
#      refreshed the hosts one after another with an unbounded ssh, and its subshell died under the inherited set -e at the first
#      ssh that failed; either stops every host after the one it stuck on);
#   4. re-count under the hold: not empty -> release that host and back to 1.
#   When every host is held, every HOLDF is checked again: if any is gone, the install command is NOT run and every hold is released.
#   After the install every HOLDF is checked again and each one that ended is named. Then every hold is released.
# Bounds: every ssh is `ssh -n -o BatchMode=yes -o ConnectTimeout=CONNECT_SECS -o ServerAliveInterval=5 -o ServerAliveCountMax=3`
#   under a wall limit of SSH_WALL s (perl alarm, SIGALRM: rc 142). A multiplexed client (ControlMaster) obeys only the wall limit;
#   the options bound its master. Required: HB_EVERY + SSH_WALL < HB_STALE, so a host whose calls succeed is refreshed in time.
# Guarantees: the install command starts only when every queue was drained and every HOLDF was present at the pre-install check; no
#   ticket is cancelled or killed. NOT guaranteed: that a hold lasts through the install. A host whose heartbeat calls keep failing for
#   HB_STALE s loses its hold mid-install; that is reported (WARNING, exit 10), not prevented. If this script dies, no heartbeat call
#   starts after the death, so every holder exits within SSH_WALL + HB_STALE (+1) s of it.
#   A drain timeout, a holder that did not take the lock in TAKE_SECS, or a hold gone before the install: every hold is released and
#   the install command is not run (exit 9).
# Exit: 0 installed and every hold lasted; 2 usage (before any ssh); 9 refused, the install command not run; 10 installed, but a hold
#   ended during the install (named on stderr); otherwise the install command's own nonzero status (holds released, lapses named).
# Environment (seconds): DRAIN_SECS 1800, POLL_SECS 15, TAKE_SECS 120, HB_EVERY 20, HB_STALE 120, SSH_WALL 30, CONNECT_SECS 10,
#   HOLD_SECS DRAIN_SECS * <hosts> + 900; BB_HOLD_SSH, the ssh program (tests point it at a shim).
set -euo pipefail
usage() { echo "usage: bb-install-hold.sh <host>... -- <install command> [<arg>...]${1:+ ($1)}" >&2; exit 2; }
HOSTS=()
while [ $# -gt 0 ] && [ "$1" != -- ]; do
  [[ $1 =~ ^[A-Za-z0-9][A-Za-z0-9@._-]*$ ]] || usage "bad host '$1'"
  for x in ${HOSTS[@]+"${HOSTS[@]}"}; do [ "$x" != "$1" ] || usage "host $1 given twice"; done
  HOSTS+=("$1"); shift
done
[ ${#HOSTS[@]} -gt 0 ] || usage "no host"
[ $# -gt 0 ] || usage "no -- before the install command"
shift; [ $# -gt 0 ] || usage "no install command after --"
DRAIN_SECS=${DRAIN_SECS:-1800}; POLL_SECS=${POLL_SECS:-15}; TAKE_SECS=${TAKE_SECS:-120}; HB_EVERY=${HB_EVERY:-20}
HB_STALE=${HB_STALE:-120}; SSH_WALL=${SSH_WALL:-30}; CONNECT_SECS=${CONNECT_SECS:-10}
case $DRAIN_SECS in ''|*[!0-9]*) usage "DRAIN_SECS must be a positive integer, not '$DRAIN_SECS'";; esac
HOLD_SECS=${HOLD_SECS:-$((DRAIN_SECS * ${#HOSTS[@]} + 900))}
for v in DRAIN_SECS POLL_SECS TAKE_SECS HB_EVERY HB_STALE SSH_WALL CONNECT_SECS HOLD_SECS; do
  [[ ${!v} =~ ^[1-9][0-9]*$ ]] || usage "$v must be a positive integer, not '${!v}'"
done
[ $((HB_EVERY + SSH_WALL)) -lt "$HB_STALE" ] || usage "HB_EVERY + SSH_WALL must be below HB_STALE"
SSH=${BB_HOLD_SSH:-ssh}
WANTF='~/work/.install-want'; HOLDF='~/work/.install-hold'
MAIN=$$
SPAWNED=(); HBPIDS=(); RELEASED=0

rsh() {  # rsh <host> <remote command>: one bounded ssh call
  perl -e 'alarm shift @ARGV; exec @ARGV or exit 127' "$SSH_WALL" "$SSH" -n -o BatchMode=yes -o ConnectTimeout="$CONNECT_SECS" \
    -o ServerAliveInterval=5 -o ServerAliveCountMax=3 "$1" "$2"
}
qcount() {  # qcount <host>: running+waiting tickets, or "unreachable"
  rsh "$1" 'find ~/work/queue/running ~/work/queue/waiting -mindepth 1 -maxdepth 1 -type f 2> /dev/null | wc -l | tr -d " "' 2> /dev/null \
    || echo unreachable
}
hb_loop() {  # hb_loop <host>, in the background: refresh WANT on one host until this script is gone or the loop is killed
  set +e
  while kill -0 "$MAIN" 2> /dev/null; do
    rsh "$1" "[ -e $WANTF ] && touch $WANTF" > /dev/null || echo "heartbeat: $1: refresh failed (rc $?)" >&2
    sleep "$HB_EVERY"
  done
}
spawn_holder() {  # spawn_holder <host>
  local i=${#SPAWNED[@]}
  SPAWNED[i]=$1
  rsh "$1" "rm -f $HOLDF; touch $WANTF; nohup bash -c 'exec 8> ~/work/queue/.lock; flock -w $HOLD_SECS 8 || exit 0; [ -e $WANTF ] || exit 0; echo \$\$ > $HOLDF; e=\$((\$(date +%s) + $HOLD_SECS)); while [ -e $WANTF ] && [ \$((\$(date +%s) - \$(stat -c %Y $WANTF))) -lt $HB_STALE ] && [ \$(date +%s) -lt \$e ]; do sleep 1; done; rm -f $HOLDF' > /dev/null 2>&1 < /dev/null &" \
    || echo "$1: the holder spawn call failed (rc $?)" >&2
  hb_loop "$1" > /dev/null &
  HBPIDS[i]=$!
}
wait_taken() {  # wait_taken <host>: 0 once the holder reports the lock taken, 1 after TAKE_SECS
  local end=$((SECONDS + TAKE_SECS))
  until rsh "$1" "[ -s $HOLDF ]" > /dev/null 2>&1; do [ $SECONDS -lt $end ] || return 1; sleep 1; done
}
held() { rsh "$1" "[ -s $HOLDF ]" > /dev/null 2>&1; }
release_host() {  # release_host <host>: remove WANT and HOLDF (its heartbeat loop is already stopped)
  rsh "$1" "rm -f $WANTF $HOLDF" > /dev/null \
    || echo "WARNING: could not release $1 (rc $?); its holder exits within ${HB_STALE}s once WANT goes stale" >&2
}
release_one() {  # release_one <host>: stop its heartbeat, release it, drop it from SPAWNED
  local i=0
  while [ $i -lt ${#SPAWNED[@]} ]; do
    if [ "${SPAWNED[i]}" = "$1" ]; then [ -z "${HBPIDS[i]:-}" ] || kill "${HBPIDS[i]}" 2> /dev/null || true; SPAWNED[i]=; fi
    i=$((i + 1))
  done
  release_host "$1"
}
release_all() {  # every heartbeat stopped first, then every spawned host released (also one whose holder is still blocked in flock)
  local i=0
  [ $RELEASED = 0 ] || return 0; RELEASED=1
  while [ $i -lt ${#HBPIDS[@]} ]; do [ -z "${HBPIDS[i]:-}" ] || kill "${HBPIDS[i]}" 2> /dev/null || true; i=$((i + 1)); done
  i=0
  while [ $i -lt ${#SPAWNED[@]} ]; do [ -z "${SPAWNED[i]}" ] || release_host "${SPAWNED[i]}"; i=$((i + 1)); done
}
spawned() { local h; for h in ${SPAWNED[@]+"${SPAWNED[@]}"}; do [ -z "$h" ] || echo "$h"; done; }

trap release_all EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for h in "${HOSTS[@]}"; do
  start=$SECONDS
  while :; do
    n=$(qcount "$h")
    if [ "$n" = 0 ]; then
      spawn_holder "$h"
      wait_taken "$h" || { echo "refused: $h: the holder did not take the queue lock in ${TAKE_SECS}s; every hold released; nothing run" >&2; exit 9; }
      n=$(qcount "$h")
      [ "$n" != 0 ] || { echo "held $h (queue empty, lock taken)"; break; }
      release_one "$h"; echo "$h: a ticket arrived before the lock (running+waiting=$n); draining again"
    fi
    [ $((SECONDS - start)) -lt "$DRAIN_SECS" ] \
      || { echo "refused: $h did not drain in ${DRAIN_SECS}s (running+waiting=$n); every hold released; nothing run" >&2; exit 9; }
    echo "$h: waiting for the queue to drain (running+waiting=$n)"; sleep "$POLL_SECS"
  done
done
gone=
for h in $(spawned); do held "$h" || gone="$gone $h"; done
[ -z "$gone" ] || { echo "refused: the hold on${gone} ended before the install started; every hold released; nothing run" >&2; exit 9; }
echo "every queue held (${HOSTS[*]}); running the install"
rc=0; "$@" || rc=$?
lapsed=
for h in $(spawned); do held "$h" || { lapsed="$lapsed $h"; echo "WARNING: $h's hold ended before the install finished" >&2; }; done
release_all; trap - EXIT
[ "$rc" = 0 ] || { echo "the install command exited $rc; every hold released" >&2; exit "$rc"; }
[ -z "$lapsed" ] || { echo "installed, but the hold on${lapsed} ended during the install; every hold released" >&2; exit 10; }
echo "installed; every hold lasted; every hold released"
