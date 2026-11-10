# bb-queue-lib.sh -- shared by bb-build and bb-queue (2026-09-28). Sourced, not executed.
# Queue layout under $Q: waiting/<id>, running/<id>, done/<id> (key=value lines; the LAST value of a key wins),
# .lock (flock; every state change happens under it). A ticket's liveness is its waiter process, found by
# cmdline ("bb-build __run <id>"), so no pid bookkeeping and no pid-reuse hazard.
# BB_TEST_* variables exist only so the mechanism can be tested in isolation; never set them for a real build.
Q=${BB_TEST_QUEUE_ROOT:-$HOME/work/queue}
PRIO=${BB_TEST_PRIORITIES:-$HOME/work/priorities}
PAT=${BB_TEST_REACTOR_PATTERN:-[c]lassworlds.launcher.Launcher}
PGM=${BB_TEST_PORT_MODULES:-$HOME/work/port-group-modules}
# backfill starvation bound (2026-09-29, "backfill"): a ticket blocked by its own cap for this long stops all backfill
STARVE=${BB_STARVE_SECS:-1200}
mkdir -p "$Q/waiting" "$Q/running" "$Q/done"

field() { sed -n "s/^$2=//p" "$1" 2>/dev/null | tail -1; }
reactors() { pgrep -u "$USER" -fc "$PAT" || true; }
waiter_alive() { pgrep -u "$USER" -f -- "bb-build __run $1( |\$)" > /dev/null; }
# co_reactor_alive <co>: a maven reactor is running with its cwd in <co> OR BELOW it (it outlives a kill -9 of its waiter).
# "Or below" (2026-11-09): a surefire/failsafe fork runs in a submodule directory, so an exact match missed a build whose
# classworlds launcher was gone but whose forks were not, and reap moved a still-running ticket (and freed its port group).
co_reactor_alive() { local p; for p in $(pgrep -u "$USER" -f "$PAT"); do under "$(readlink "/proc/$p/cwd" 2>/dev/null)" "$1" && return 0; done; return 1; }
# under <dir> <co>: <dir> is <co> or a directory below it.
under() { [ -n "$1" ] && [ -n "$2" ] && case "$1" in "$2"|"$2"/*) return 0;; esac; return 1; }
# waiter_pids <id>: the exact PIDs of a ticket's waiter processes (bb-build __run <id>).
waiter_pids() { pgrep -u "$USER" -f -- "bb-build __run $1( |\$)" || true; }
# tree_of <pid>...: those PIDs plus all their descendants (this user's processes), one per line.
tree_of() {
  [ $# -gt 0 ] || return 0
  ps -u "$USER" -o pid= -o ppid= | awk -v roots="$*" '
    BEGIN { n = split(roots, r, " "); for (i = 1; i <= n; i++) keep[r[i]] = 1 }
    { par[$1] = $2 }
    END { do { grew = 0; for (k in par) if (!(k in keep) && (par[k] in keep)) { keep[k] = 1; grew = 1 } } while (grew)
          for (k in keep) if (k in par) print k }'
}
# cwd_below <co>: PIDs of this user's processes whose cwd is <co> or below (surefire forks live in submodules).
cwd_below() {
  local d; for d in /proc/[0-9]*; do
    under "$(readlink "$d/cwd" 2>/dev/null)" "$1" && echo "${d#/proc/}"
  done
}
# proc_live <pid>: the process exists and is not a zombie (a zombie whose parent never reaps it is already dead for our purposes).
proc_live() { local st; st=$(sed 's/^.*) //; s/ .*//' "/proc/$1/stat" 2>/dev/null) || return 1; [ -n "$st" ] && [ "$st" != Z ]; }
# pstart <pid>: the process start time (clock ticks since boot, /proc/<pid>/stat field 22; parsed after the LAST ")" because comm may
# hold spaces and parentheses). With proc_live it makes a PID an identity: a recycled PID has another start time.
pstart() { sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | cut -d' ' -f20; }
# pstart_now <pid>: pstart at signal time. BB_TEST_RESTART_PID=<pid> makes that one pid read as "reused" (the test seam for a recycled PID).
pstart_now() { if [ -n "${BB_TEST_RESTART_PID:-}" ] && [ "$1" = "$BB_TEST_RESTART_PID" ]; then echo reused; else pstart "$1"; fi; }
# still_ours <pid:start:kind> <co>: the collected process is the same process (start time unchanged) and still qualifies: kind c (found by
# its cwd) needs the cwd still at or below <co>; kind w (waiter tree) and d (below a cwd process) are the same tree member as collected.
still_ours() {
  local p=${1%%:*} r=${1#*:} s k
  s=${r%%:*}; k=${r#*:}
  proc_live "$p" && [ "$(pstart_now "$p")" = "$s" ] || return 1
  [ "$k" != c ] || under "$(readlink "/proc/$p/cwd" 2>/dev/null)" "$2"
}
# kill_set <id> <co>: the processes to stop for a RUNNING ticket, one "pid:starttime:kind" per line: the waiter(s) and everything below
# them (w), every process whose cwd is the checkout or below (c) and everything below those (d). This shell, its ancestors and its
# descendants are never in it (the caller may stand inside the checkout). Never a pattern kill: identities are collected, then signalled.
kill_set() {
  local self=$$ anc="" p wt cw; p=$$
  while [ -n "$p" ] && [ "$p" -gt 1 ]; do anc="$anc $p"; p=$(ps -o ppid= -p "$p" 2>/dev/null | tr -d ' '); done
  wt=" $(tree_of $(waiter_pids "$1") | tr '\n' ' ')"; cw=" $(cwd_below "$2" | tr '\n' ' ')"
  { tree_of $(waiter_pids "$1") $(cwd_below "$2"); } | sort -un | grep -vxF -f <({ for p in $anc; do echo "$p"; done; tree_of $self; }) \
    | while read -r p; do
        proc_live "$p" || continue
        case "$wt" in *" $p "*) echo "$p:$(pstart "$p"):w";; *) case "$cw" in *" $p "*) echo "$p:$(pstart "$p"):c";; *) echo "$p:$(pstart "$p"):d";; esac;; esac
      done || true
}
# stop_ticket_processes <id> <co>: TERM the kill set, KILL what survives the grace, repeat (a dying parent can have spawned);
# succeed only when nothing that is still ours is left. Before EVERY signal the identity is re-checked (still_ours): a PID whose start
# time changed or that no longer qualifies is never signalled, and it is not a survivor either: our process is gone, the PID is someone
# else's now. Prints two lines on stdout: the PIDs it signalled, and "notours=<pids>" (identity changed, not signalled), space separated.
stop_ticket_processes() {
  local all="" no="" set e p round i left
  for round in 1 2 3; do
    set=$(kill_set "$1" "$2"); [ -n "$set" ] || break
    for e in $set; do
      if still_ours "$e" "$2"; then kill -TERM "${e%%:*}" 2>/dev/null; all="$all ${e%%:*}"
      elif proc_live "${e%%:*}"; then no="$no ${e%%:*}"; fi
    done
    for i in 1 2 3 4 5 6 7 8 9 10; do
      left=""; for e in $set; do still_ours "$e" "$2" && left="$left $e"; done
      [ -n "$left" ] || break; sleep 0.5
    done
    for e in $left; do still_ours "$e" "$2" && kill -KILL "${e%%:*}" 2>/dev/null; done
    sleep 0.2
  done
  printf '%s\n' "$(printf '%s\n' $all | sort -un | tr '\n' ' ' | sed 's/ $//')"
  printf 'notours=%s\n' "$(printf '%s\n' $no | sort -un | tr '\n' ' ' | sed 's/ $//')"
  for e in $(kill_set "$1" "$2"); do still_ours "$e" "$2" && return 1; done
  return 0
}

# prio <owner> <sha>: highest matching line of $PRIO. A key matches if it equals the owner exactly, or if it is
# 7-40 lowercase hex chars and a prefix of the SHA. No match (or no file) = 0.
prio() {
  awk -v o="$1" -v s="$2" '
    /^[[:space:]]*(#|$)/ { next }
    NF >= 2 && $2 ~ /^-?[0-9]+$/ {
      m = ($1 == o) || ($1 ~ /^[0-9a-f]+$/ && length($1) >= 7 && length($1) <= 40 && index(s, $1) == 1)
      if (m && (best == "" || $2 + 0 > best + 0)) best = $2
    }
    END { print (best == "" ? 0 : best) }' "$PRIO" 2>/dev/null || echo 0
}

# order: waiting ticket ids, grant order first (priority desc, then enqueue time asc).
order() {
  local f
  for f in "$Q"/waiting/*; do
    [ -f "$f" ] || continue
    printf '%s %s %s\n' "$(prio "$(field "$f" owner)" "$(field "$f" sha)")" "$(field "$f" created | grep -x '[0-9][0-9]*' || echo 0)" "${f##*/}"
  done | sort -k1,1nr -k2,2n -k3,3 | awk '{print $3}'
}

# Port group (2026-09-29): tickets with group=1 bind FIXED test ports, so at most one of them runs at a time; other
# tickets are unaffected. The group is held by any running/ ticket with group=1 -- and reap keeps a ticket in running/
# while its waiter OR its maven reactor lives, so a kill -9 releases the group exactly when the ports are free.
group_holder() { local f; for f in "$Q"/running/*; do [ -f "$f" ] && [ "$(field "$f" group)" = 1 ] && { echo "${f##*/}"; return 0; }; done; return 0; }
# grantable (under lock, after reap): order, minus group tickets while the group is held. Tickets written before the
# group existed have no group= line and count as non-group.
grantable() {
  local id held; held=$(group_holder)
  for id in $(order); do
    [ -n "$held" ] && [ "$(field "$Q/waiting/$id" group)" = 1 ] && continue
    echo "$id"
  done
}

# never_started <co> <rc> <reason>: make .bb describe a run that never reached maven, so pollers of .bb/rc wake
# and bb-report counts nothing (run.marker is touched, so no older XML is "newer than marker").
never_started() {
  local B=$1/.bb now; now=$(date +%s)
  mkdir -p "$B"; touch "$B/run.marker"; echo "$now" > "$B/start"; echo "$now" > "$B/end"
  echo "bb-build: maven never started: $3" > "$B/build.log"; rm -f "$B/queued"; echo "$2" > "$B/rc"
}

# reap (under lock): tickets whose waiter is gone, or (waiting) whose checkout directory is gone. Waiting -> abandoned (rc 9 written for the poller).
# Running -> finished if maven wrote rc, else orphaned. A running ticket whose waiter died but whose maven still runs in
# its checkout stays running (it still holds its reactor slot and, if group=1, the port group) until maven exits.
reap() {
  local f id co p
  for f in "$Q"/waiting/*; do
    [ -f "$f" ] || continue; id=${f##*/}; co=$(field "$f" co)
    # checkout removed under a live waiter (bb-temper rm, a deleted tree): nothing can ever be built; stop the waiter by PID.
    # Not never_started: that would recreate the vanished directory.
    if [ -n "$co" ] && [ ! -d "$co" ]; then
      for p in $(waiter_pids "$id"); do kill -TERM "$p" 2>/dev/null; done
      printf 'state=abandoned\nended=%s\n' "$(date +%s)" >> "$f"; mv "$f" "$Q/done/$id"; continue
    fi
    waiter_alive "$id" && continue
    [ "$(cat "$co/.bb/queued" 2>/dev/null)" = "$id" ] && never_started "$co" 9 "ticket $id abandoned (waiter process gone)"
    printf 'state=abandoned\nended=%s\n' "$(date +%s)" >> "$f"; mv "$f" "$Q/done/$id"
  done
  for f in "$Q"/running/*; do
    [ -f "$f" ] || continue; id=${f##*/}; waiter_alive "$id" && continue
    co=$(field "$f" co); co_reactor_alive "$co" && continue
    if [ -f "$co/.bb/rc" ] && [ "$(cat "$co/.bb/start" 2>/dev/null)" = "$(field "$f" started)" ]; then
      printf 'state=finished\nrc=%s\nended=%s\n' "$(cat "$co/.bb/rc")" "$(cat "$co/.bb/end" 2>/dev/null)" >> "$f"
    else
      printf 'state=orphaned\nended=%s\n' "$(date +%s)" >> "$f"
    fi
    mv "$f" "$Q/done/$id"
  done
}

# pick (under lock, after reap): the ONE waiting ticket to grant now, or nothing. BACKFILL (2026-09-29): walk the
# grantable order (priority desc, FIFO; port-group tickets already filtered while the group is held) and grant the
# first ticket whose OWN cap is satisfied, so a head that cannot start (e.g. BB_MAX_REACTORS=1 waiting for an empty
# host) no longer blocks everything behind it. STARVATION BOUND: each ticket skipped for its cap gets blocked_since=
# (first time only); once any skipped ticket has been blocked for STARVE seconds, nothing behind it is granted, so the
# host drains and it runs.
pick() {
  local r id f c bs now; r=$(reactors); now=$(date +%s)
  for id in $(grantable); do
    f=$Q/waiting/$id; c=$(field "$f" cap); case "$c" in ''|*[!0-9]*) c=3;; esac
    [ "$r" -lt "$c" ] && { echo "$id"; return 0; }
    bs=$(field "$f" blocked_since); case "$bs" in ''|*[!0-9]*) bs=$now; echo "blocked_since=$now" >> "$f";; esac
    [ $((now - bs)) -ge "$STARVE" ] && return 0
  done
  return 0
}
