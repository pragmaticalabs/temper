# bb-queue-lib.sh -- shared by bb-build and bb-queue (2026-09-28). Sourced, not executed.
# Queue layout under $Q: waiting/<id>, running/<id>, done/<id> (key=value lines; the LAST value of a key wins),
# .lock (flock; every state change happens under it). A ticket's liveness is its waiter process, found by
# cmdline ("bb-build __run <id>"), so no pid bookkeeping and no pid-reuse hazard.
# BB_TEST_* variables exist only so the mechanism can be tested in isolation; never set them for a real build.
Q=${BB_TEST_QUEUE_ROOT:-$HOME/work/queue}
PRIO=${BB_TEST_PRIORITIES:-$HOME/work/priorities}
PAT=${BB_TEST_REACTOR_PATTERN:-[c]lassworlds.launcher.Launcher}
PGM=${BB_TEST_PORT_MODULES:-$HOME/work/port-group-modules}
PGS=${BB_TEST_PG_SLOTS_FILE:-$HOME/work/port-group-slots}   # number of concurrent ISOLATED port-group tickets (default 3)
# backfill starvation bound (2026-09-29, "backfill"): a ticket blocked by its own cap for this long stops all backfill
STARVE=${BB_STARVE_SECS:-1200}
# LOAD ADMISSION (2026-11-11, CTO ruling): a ticket is granted only while load5/cores < LOAD_MAX (in addition to its cap). Load
# from processes outside the queue must not block it forever: once the oldest grantable ticket has waited more than LOAD_STARVE
# seconds AND fewer than LOAD_STARVE_REACTORS reactors run, that ticket is granted regardless of load. Read by each waiter, so the
# values in force for a ticket are its enqueuer's environment.
LOAD_MAX=${BB_LOAD_MAX:-1.2}
LOAD_STARVE=${BB_LOAD_STARVE_SECS:-1200}
LOAD_STARVE_REACTORS=${BB_LOAD_STARVE_REACTORS:-2}
LOADAVG=${BB_TEST_LOADAVG:-/proc/loadavg}   # TEST ONLY: a file in /proc/loadavg format, read instead
# MEMORY FLOOR (issue #53, CTO ruling 2026-11-14): a ticket is granted only while MemAvailable >= mem_floor_gb GiB, because memory
# used outside the queue is invisible to the load gate (2026-11-13, cloudbb-4: hand-started TLC beside queued reactors, OOM kills).
# The floor is the per-host file $MEMFLOOR (one positive integer, read at every pick), else BB_MEM_FLOOR_GB, else 8. Default 8 GB:
# the largest single grant is one maven JVM (~2.4 GB) plus one surefire fork (~3.6 GB), about 6 GB, plus headroom; re-measure it
# if the fork heap changes. The load gate's starvation override never bypasses this floor while any reactor runs (see pick).
MEMFLOOR=${BB_TEST_MEM_FLOOR_FILE:-$HOME/work/mem-floor-gb}
MEMINFO=${BB_TEST_MEMINFO:-/proc/meminfo}   # TEST ONLY: a file in /proc/meminfo format, read instead
# OFF-QUEUE JVM HEADROOM (issue #56, 2026-11-18): MemAvailable misses heap a running JVM may still claim (three hand-started TLC JVMs at
# -Xmx 8+10+8G on 30G cloudbb-3; suite forks were then killed with 137). mem_held subtracts, for every java process that is neither a
# reactor ($PAT) nor a descendant of one (its surefire/failsafe forks), max(0, Xmx - RSS) from MemAvailable before the floor test. One
# ps pass per call. A java without -Xmx is skipped (its default max heap is not on the command line). RSS also counts non-heap memory,
# so the headroom is an underestimate, never an overestimate: only words before the main class or -jar are read as JVM options, so an application argument such as `-Xmx50g` after them never counts.
PSLIST=${BB_TEST_PS:-}   # TEST ONLY: a file of "pid ppid rss_kb args..." lines, read instead of ps
# HOST REACTOR CAP (issue #56, CTO request 2026-11-18): a ticket is granted only while running reactors < min(its own cap, host_cap).
# host_cap is the first line of 1..999 of the per-host file $MAXREACT (read at every pick), else 3 (the CTO's value for the 30G
# hosts). There is no environment fallback on purpose: pick runs with the ENQUEUER's environment, so an env value would let a submitter
# lift the host's bound. No override (load or memory starvation) passes it.
MAXREACT=${BB_TEST_MAX_REACTORS_FILE:-$HOME/work/max-reactors}
# TICKET LOG (2026-11-11): one TSV line per ticket when it reaches done/ (see ticket_log).
TLOG=${BB_TEST_TICKET_LOG:-$HOME/work/bb-ticket-log.tsv}
# LIB_READONLY: set by bb-queue itself for `offq` (what bb-tickets calls), a reader that must not create a queue directory.
[ -n "${LIB_READONLY:-}" ] || mkdir -p "$Q/waiting" "$Q/running" "$Q/done"

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
  # An isolated ticket's container processes (tini, maven, forks) have their cwd in the checkout, so the kill set holds them and the
  # waiter tree together: all die in one round and the waiter never writes the container's 137 as the build's rc. The docker kill
  # comes AFTER, as a mop-up (2026-11-11: killing the container first let the waiter record rc=137 and cancel reported "too late").
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
  timeout 20 docker kill "bb-$1" > /dev/null 2>&1 || true   # isolated ticket: the container (named bb-<id> by bb-netns-run), if still up
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

# Port group (2026-09-29, isolated slots 2026-11-11): tickets with group=1 bind FIXED test ports. Two classes:
#  - ISOLATED (iso=1, written by bb-build): the maven run executes in its own docker network namespace (bb-netns-run), so
#    identical fixed ports cannot collide; up to pg_slots() (file $PGS, default 3) of them run at once, each holding a slot
#    number (group_slot=K) from grant to the end of its maven.
#  - HOST (group=1 without iso=1: bb-hold containers, tickets of waiters started before 2026-11-11, BB_PG_ISOLATE=0): bind
#    the host's ports, so a host ticket runs only while NO group ticket of either class runs (the old exclusive rule), and a
#    waiting host ticket stops later isolated tickets from being granted, so it cannot starve.
# reap keeps a ticket in running/ while its waiter OR its maven reactor lives, so a kill -9 frees its slot exactly when its
# processes are gone. Tickets written before the group existed have no group= line and count as non-group.
pg_slots() { local n; n=$(sed -n 's/^[[:space:]]*\([1-9][0-9]*\)[[:space:]]*$/\1/p' "$PGS" 2>/dev/null | head -1); echo "${n:-3}"; }
# group_holder: running group tickets, "<id>[slot K|host]" space separated (empty = none).
group_holder() {
  local f s; for f in "$Q"/running/*; do
    [ -f "$f" ] && [ "$(field "$f" group)" = 1 ] || continue
    if [ "$(field "$f" iso)" = 1 ]; then s="slot $(field "$f" group_slot)"; else s=host; fi
    printf '%s[%s] ' "${f##*/}" "$s"
  done; return 0
}
# pg_free_slot: lowest slot number in 1..pg_slots not held by a running isolated ticket (0 if none).
pg_free_slot() {
  local n i used=" " f; n=$(pg_slots)
  for f in "$Q"/running/*; do [ -f "$f" ] && [ "$(field "$f" iso)" = 1 ] && used="$used$(field "$f" group_slot) "; done
  for i in $(seq "$n"); do case "$used" in *" $i "*) ;; *) echo "$i"; return 0;; esac; done
  echo 0
}
# grantable (under lock, after reap): order, minus the group tickets that may not start now.
grantable() {
  local id f n ni=0 ng=0 drain=0; n=$(pg_slots)
  for f in "$Q"/running/*; do
    [ -f "$f" ] && [ "$(field "$f" group)" = 1 ] || continue
    ng=$((ng + 1)); [ "$(field "$f" iso)" = 1 ] && ni=$((ni + 1))
  done
  for id in $(order); do
    f=$Q/waiting/$id
    if [ "$(field "$f" group)" = 1 ]; then
      if [ "$(field "$f" iso)" = 1 ]; then
        [ "$drain" = 1 ] && continue
        [ "$ni" -ge "$n" ] && continue
      else
        drain=1
        [ "$ng" -gt 0 ] && continue
      fi
    fi
    echo "$id"
  done
}

# write_rc <bb dir> <rc>: the checkout's CONVENIENCE copy of a run's exit code (2026-11-12, bbrc1). Written whole under a temp name,
# made read-only (444) and renamed into place, so a poller never sees a half-written or writable file. An existing 444 file is
# replaced by rename/rm (directory permission), never by a `>` redirection, which a 444 file refuses. The VERDICT is the ticket's
# rc= line (the build process's exit status), not this file; see bb-wait and bb-temper wait/report.
write_rc() {
  local f=$1/rc t=$1/rc.new.$$
  printf '%s\n' "$2" > "$t" && chmod 444 "$t" && mv -f "$t" "$f"
}

# never_started <co> <rc> <reason>: make .bb describe a run that never reached maven, so pollers of .bb/rc wake
# and bb-report counts nothing (run.marker is touched, so no older XML is "newer than marker").
never_started() {
  local B=$1/.bb now; now=$(date +%s)
  mkdir -p "$B"; touch "$B/run.marker"; echo "$now" > "$B/start"; echo "$now" > "$B/end"; rm -f "$B/ticket"   # no run owns this rc (#80)
  echo "bb-build: maven never started: $3" > "$B/build.log"; rm -f "$B/queued"; write_rc "$B" "$2"
}

# reap (under lock): tickets whose waiter is gone, or (waiting) whose checkout directory is gone. Waiting -> abandoned (rc 9 written for the poller).
# Running -> orphaned (no rc, whatever .bb/rc holds). A running ticket whose waiter died but whose maven still runs in
# its checkout stays running (it still holds its reactor slot and, if group=1, the port group) until maven exits.
reap() {
  local f id co p
  for f in "$Q"/waiting/*; do
    [ -f "$f" ] || continue; id=${f##*/}; co=$(field "$f" co)
    # checkout removed under a live waiter (bb-temper rm, a deleted tree): nothing can ever be built; stop the waiter by PID.
    # Not never_started: that would recreate the vanished directory.
    if [ -n "$co" ] && [ ! -d "$co" ]; then
      for p in $(waiter_pids "$id"); do kill -TERM "$p" 2>/dev/null; done
      printf 'state=abandoned\nended=%s\n' "$(date +%s)" >> "$f"; to_done "$f"; continue
    fi
    waiter_alive "$id" && continue
    [ "$(cat "$co/.bb/queued" 2>/dev/null)" = "$id" ] && never_started "$co" 9 "ticket $id abandoned (waiter process gone)"
    printf 'state=abandoned\nended=%s\n' "$(date +%s)" >> "$f"; to_done "$f"
  done
  for f in "$Q"/running/*; do
    [ -f "$f" ] || continue; id=${f##*/}; waiter_alive "$id" && continue
    co=$(field "$f" co); co_reactor_alive "$co" && continue
    # No numeric rc here, ever: the ticket's rc= is the build's exit status taken by its waiter, and this waiter is dead. The checkout's
    # .bb/rc is a copy a lane can rewrite, so it is not a verdict source (2026-11-13, vt42 finding 1); bb-wait exits 70 on orphaned.
    printf 'state=orphaned\nended=%s\n' "$(date +%s)" >> "$f"
    to_done "$f"
  done
}

# pick [<caller id>] (under lock, after reap; caller defaults to the global $ID of bb-build/bb-hold): the ONE waiting ticket to
# grant now, or nothing. BACKFILL (2026-09-29): walk the
# grantable order (priority desc, FIFO; port-group tickets already filtered while the group is held) and grant the
# first ticket whose OWN cap (bounded by the HOST CAP, issue #56) is satisfied, so a head that cannot start (e.g. BB_MAX_REACTORS=1 waiting for an empty
# host) no longer blocks everything behind it. STARVATION BOUND: each ticket skipped for its cap gets blocked_since=
# (first time only); once any skipped ticket has been blocked for STARVE seconds, nothing behind it is granted, so the
# host drains and it runs.
pick() {
  local me=${1-${ID-}} r id f c hc bs now lh mh cand="" oldest="" ocr=0 cr; r=$(reactors); now=$(date +%s)
  hc=$(host_cap)
  for id in $(grantable); do
    f=$Q/waiting/$id; c=$(field "$f" cap); case "$c" in ''|*[!0-9]*) c=3;; esac; [ "$c" -le "$hc" ] || c=$hc
    cr=$(created_s "$f"); if [ -z "$oldest" ] || [ "$cr" -lt "$ocr" ]; then oldest=$id; ocr=$cr; fi
    [ -z "$cand" ] || continue
    [ "$r" -lt "$c" ] && { cand=$id; continue; }
    bs=$(field "$f" blocked_since); case "$bs" in ''|*[!0-9]*) bs=$now; echo "blocked_since=$now" >> "$f";; esac
    [ $((now - bs)) -ge "$STARVE" ] && return 0
  done
  [ -n "$cand" ] || return 0
  lh=$(load_held); mh=$(mem_held)
  [ -z "$lh" ] && [ -z "$mh" ] && { echo "$cand"; return 0; }
  # A GATE is held. Starvation bound: the OLDEST grantable ticket goes once it has waited > LOAD_STARVE s and its own cap allows a
  # start, provided every held gate may be overridden: the LOAD GATE while fewer than LOAD_STARVE_REACTORS reactors run, the MEMORY
  # FLOOR only while NO ticket of any kind runs (no reactor, and nothing in running/: a bb-hold's containers hold memory without a
  # launcher; granting into low memory beside either reproduces the OOM; with nothing running, an outside job must not wedge the
  # queue). Each override is recorded on the ticket (load_override=, mem_override=), once, by the oldest ticket's own waiter.
  [ $((now - ocr)) -gt "$LOAD_STARVE" ] || return 0
  [ -z "$lh" ] || [ "$r" -lt "$LOAD_STARVE_REACTORS" ] || return 0
  [ -z "$mh" ] || { [ "$r" -eq 0 ] && [ -z "$(ls "$Q/running" 2>/dev/null)" ]; } || return 0
  # The HOST CAP needs no check here: a candidate exists only while reactors < min(its cap, host cap), so r < host cap already holds.
  f=$Q/waiting/$oldest; c=$(field "$f" cap); case "$c" in ''|*[!0-9]*) c=3;; esac
  [ "$r" -lt "$c" ] || return 0
  if [ "$me" = "$oldest" ]; then   # record once: only the oldest's own waiter writes it, not every waiter that polls
    [ -z "$lh" ] || echo "load_override=$now waited=$((now - ocr))s reactors=$r $lh" >> "$f"
    [ -z "$mh" ] || echo "mem_override=$now waited=$((now - ocr))s reactors=$r $(mem_available_gb) $mh" >> "$f"
  fi
  echo "$oldest"
  return 0
}
# created_s <ticket>: enqueue time in epoch seconds (created= is nanoseconds); 0 if unreadable.
created_s() { local c; c=$(field "$1" created); case "$c" in ''|*[!0-9]*) echo 0;; *) echo $((c / 1000000000));; esac; }
# load_held: nothing when load5/cores < LOAD_MAX, else the reason ("held: load 1.60/core (ceiling 1.2; load5 25.6, 16 cores)").
# An unreadable or malformed load source never holds the queue.
load_held() {
  local cores; cores=$(nproc 2>/dev/null); case "$cores" in ''|*[!0-9]*|0) cores=1;; esac
  awk -v c="$cores" -v m="$LOAD_MAX" 'NR == 1 { if ($2 ~ /^[0-9]+(\.[0-9]+)?$/ && $2 / c >= m + 0) printf "held: load %.2f/core (ceiling %s; load5 %s, %d cores)\n", $2 / c, m, $2, c; exit }' "$LOADAVG" 2>/dev/null || true
}
# mem_floor_gb: the memory floor in GiB: the first line of $MEMFLOOR that is a positive integer (others are skipped), else BB_MEM_FLOOR_GB if it is one, else 8.
mem_floor_gb() {
  local n; n=$(sed -n 's/^[[:space:]]*\([1-9][0-9]*\)[[:space:]]*$/\1/p' "$MEMFLOOR" 2>/dev/null | head -1)
  [ -n "$n" ] || case "${BB_MEM_FLOOR_GB:-}" in ''|0*|*[!0-9]*) ;; *) n=$BB_MEM_FLOOR_GB;; esac
  echo "${n:-8}"
}
# host_cap: the first line of $MAXREACT that is an integer of 1..999 (others are skipped, so 0, 1000 and 99999999999999999999 are malformed:
# an INT64-overflowing value would make every `[ -lt ]` fail and wedge the queue), else 3.
host_cap() {
  local n; n=$(sed -n 's/^[[:space:]]*\([1-9][0-9]\{0,2\}\)[[:space:]]*$/\1/p' "$MAXREACT" 2>/dev/null | head -1)
  echo "${n:-3}"
}
# ps_list: "pid ppid rss_kb args..." for every process on the host (all users: an off-queue JVM need not be ours), or $PSLIST.
ps_list() { if [ -n "$PSLIST" ]; then cat "$PSLIST" 2>/dev/null; else ps -e -ww -o pid= -o ppid= -o rss= -o args= 2>/dev/null; fi; true; }
# offqueue_jvms: one line "pid headroom_kb xmx_kb rss_kb" per java process (argv[0] is java or ends in /java) that is neither a reactor
# nor below one (a reactor is $PAT or a real maven launcher, also under a test's own $PAT: no maven reactor or fork is ever off-queue)
# and carries -Xmx<n>[kmgt] among its JVM options, i.e. before the main class, `-jar <jar>` or `-m <module>` (the last one wins, as in the JVM; a bare byte count is accepted too); headroom = max(0, Xmx - RSS).
offqueue_jvms() {
  ps_list | awk -v pat="$PAT" -v mvn='[c]lassworlds[.]launcher[.]Launcher' '
    $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { n++; pid[n] = $1; par[$1] = $2; rss[$1] = $3 + 0; a = $0
      sub(/^[[:space:]]*[0-9]+[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]*/, "", a); args[$1] = a; if (a ~ pat || a ~ mvn) re[$1] = 1 }
    END { for (i = 1; i <= n; i++) { p = pid[i]; split(args[p], w, " "); if (w[1] != "java" && w[1] !~ /\/java$/) continue
            for (q = p; q in par && !(q in re) && !(q in seen); q = par[q]) seen[q] = 1
            delete seen; if (q in re) continue
            x = -1; for (j = 2; j in w; j++) { o = w[j]
              if (o !~ /^-/ || o == "-jar" || o == "-m" || o == "--module" || o ~ /^--module=/) break
              if (o ~ /^(-cp|-classpath|-p|--class-path|--module-path|--upgrade-module-path|--add-modules|--limit-modules|--add-opens|--add-exports|--add-reads|--patch-module)$/) { j++; continue }
              if (o ~ /^-Xmx[0-9]+[kKmMgGtT]?$/) { v = substr(o, 5); u = tolower(substr(v, length(v)))
                if (u ~ /[kmgt]/) { v = substr(v, 1, length(v) - 1) + 0; x = (u == "k") ? v : (u == "m") ? v * 1024 : (u == "g") ? v * 1048576 : v * 1073741824 }
                else x = int(v / 1024) } }
            if (x < 0) continue
            h = x - rss[p]; if (h < 0) h = 0; printf "%s %.0f %.0f %.0f\n", p, h, x, rss[p] } }'
}
# offqueue_headroom_kb: the sum of offqueue_jvms headroom, in kB (0 when there is none).
offqueue_headroom_kb() { offqueue_jvms | awk '{ s += $2 } END { printf "%.0f\n", s }'; }
# OFF-QUEUE REACTORS (CTO ruling 2026-11-20; incident cloudbb-5 2026-11-20: a hand-run mvn in ~/v3455d held the host cap for ~45 min).
# offqueue_reactors: one line per maven launcher ($PAT or a real classworlds launcher) the queue did not start, "pid<TAB>startS<TAB>owner<TAB>cwd"
# (startS: epoch seconds; cwd in full, bytes outside 0x20-0x7e shown as ?). A launcher is the queue's own when a `bb-build __run ` process is among
# its ancestors, or when its cgroup holds a running container named temper-* (a temper CI job, as HostReactors.foreign reads it) or bb-*
# (bb-netns-run's isolated build: its tree ends at containerd-shim, not at a waiter). Observation only: it never signals anything, and the cap
# does not read it. A docker that cannot answer leaves a containerised launcher listed (over-reports, never hides). Reads $PROCROOT, the ps list
# (PSLIST seam) and, only when a candidate sits in a container, one `docker ps`.
PROCROOT=${BB_TEST_PROC:-/proc}
DOCKERPS=${BB_TEST_DOCKER_PS:-}   # TEST ONLY: a file of "<64-hex id> <name>" lines, read instead of `docker ps`
CO_ROOT=${BB_TEST_CO_ROOT:-$HOME/work/co}
# offq_owner <cwd>: the checkout prefix (~/work/co/v3436i-head -> v3436i), else "unowned".
offq_owner() {
  local r o=unowned
  case "$1" in "$CO_ROOT"/?*) r=${1#"$CO_ROOT"/}; r=${r%%/*}; o=${r%%-*}; [ -n "$o" ] || o=unowned;; esac
  printf '%s\n' "$o"
}
offqueue_reactors() {
  local pid ids="" cg cid hit st t0 clk cwd
  for pid in $(ps_list | awk -v pat="$PAT" -v mvn='[c]lassworlds[.]launcher[.]Launcher' '
      $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { n++; ord[n] = $1; par[$1] = $2; a = $0; sub(/^[[:space:]]*[0-9]+[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]*/, "", a); args[$1] = a }
      END { for (i = 1; i <= n; i++) { p = ord[i]; if (args[p] !~ pat && args[p] !~ mvn) continue
              own = 0; delete seen; for (q = par[p]; q in par && !(q in seen); q = par[q]) { seen[q] = 1; if (args[q] ~ /bb-build __run /) { own = 1; break } }
              if (!own) print p } }'); do
    cg=$(cat "$PROCROOT/$pid/cgroup" 2>/dev/null) || continue
    hit=0
    for cid in $(printf '%s\n' "$cg" | grep -Eo '[0-9a-f]{64}' | sort -u); do
      [ -n "$ids" ] || ids=$( { if [ -n "$DOCKERPS" ]; then cat "$DOCKERPS" 2>/dev/null; else timeout 5 docker ps --no-trunc --format '{{.ID}} {{.Names}}' 2>/dev/null; fi; } | awk '$1 ~ /^[0-9a-f]{64}$/ && $2 ~ /^(temper|bb)-/ { print $1 }'; echo none)
      case $'\n'"$ids"$'\n' in *$'\n'"$cid"$'\n'*) hit=1;; esac
    done
    [ "$hit" = 0 ] || continue
    cwd=$(readlink "$PROCROOT/$pid/cwd" 2>/dev/null | tr -c '\040-\176\n' '?') && [ -n "$cwd" ] || continue
    st=$(sed 's/^.*) //' "$PROCROOT/$pid/stat" 2>/dev/null | cut -d' ' -f20); t0=$(sed -n 's/^btime //p' "$PROCROOT/stat" 2>/dev/null)
    clk=$(getconf CLK_TCK 2>/dev/null || echo 100)
    case "$st$t0" in ''|*[!0-9]*) continue;; esac
    printf '%s\t%s\t%s\t%s\n' "$pid" $((t0 + st / clk)) "$(offq_owner "$cwd")" "$cwd"
  done
}
# mem_available_gb: "MemAvailable=<x.x>GB" from $MEMINFO, or nothing when it is unreadable or malformed.
mem_available_gb() {
  awk '$1 == "MemAvailable:" { if ($2 ~ /^[0-9]+$/ && $3 == "kB") printf "MemAvailable=%.1fGB\n", $2 / 1048576; exit }' "$MEMINFO" 2>/dev/null || true
}
# mem_held: nothing when MemAvailable minus the off-queue JVM headroom is >= the floor, else the reason ("held: MemAvailable 5.1 GB < floor
# 8 GB", or with headroom "held: MemAvailable 20.0 GB - off-queue JVM headroom 18.0 GB = 2.0 GB < floor 8 GB"). An unreadable or
# malformed meminfo (no MemAvailable line, a non-numeric value, a unit other than kB) never holds the queue.
mem_held() {
  awk -v fl="$(mem_floor_gb)" -v hr="$(offqueue_headroom_kb)" '$1 == "MemAvailable:" { if ($2 ~ /^[0-9]+$/ && $3 == "kB" && ($2 - hr) / 1048576 < fl + 0) {
      if (hr > 0) printf "held: MemAvailable %.1f GB - off-queue JVM headroom %.1f GB = %.1f GB < floor %d GB\n", $2 / 1048576, hr / 1048576, ($2 - hr) / 1048576, fl
      else printf "held: MemAvailable %.1f GB < floor %d GB\n", $2 / 1048576, fl }; exit }' "$MEMINFO" 2>/dev/null || true
}

# to_done <ticket file>: move a ticket that has just got its terminal state lines to done/, then log it. Caller holds the queue lock.
to_done() { local id=${1##*/}; mv "$1" "$Q/done/$id" && ticket_log "$Q/done/$id"; return 0; }
# utc <epoch>: ISO-8601 UTC, or '-' for an empty/zero value.
utc() { case "$1" in ''|0|*[!0-9]*) echo -;; *) date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ;; esac; }
# ticket_log <done file>: append ONE line to $TLOG (append-only, never rewritten): a single printf of < 4000 bytes (below PIPE_BUF) to
# an O_APPEND fd, issued under the queue lock. A header line is written first if the file is empty. Columns, tab separated:
#   host ticket owner checkout port_group group_id enqueued granted ended wait_s run_s rc state args
# times ISO-8601 UTC; group_id = isolated slot number, "host" (host-exclusive group ticket) or '-'; wait_s = granted-enqueued (never
# granted: ended-enqueued); run_s = ended-granted; rc '-' when the ticket has none (abandoned, expired, orphaned); state = the
# ticket's last state=; args with tabs/newlines folded to spaces and cut so the line fits.
ticket_log() {
  local f=$1 cr st en g gid rc ws=- rs=- line a
  cr=$(created_s "$f"); st=$(field "$f" started); en=$(field "$f" ended); g=$(field "$f" group); rc=$(field "$f" rc)
  case "$st" in *[!0-9]*) st="";; esac; case "$en" in *[!0-9]*) en="";; esac
  gid=-; if [ "$g" = 1 ]; then if [ "$(field "$f" iso)" = 1 ]; then gid=$(field "$f" group_slot); gid=${gid:--}; else gid=host; fi; fi
  if [ "$cr" != 0 ]; then if [ -n "$st" ]; then ws=$((st - cr)); elif [ -n "$en" ]; then ws=$((en - cr)); fi; fi
  if [ -n "$st" ] && [ -n "$en" ]; then rs=$((en - st)); fi
  a=$(field "$f" args | tr '\t\n\r' '   ')
  line=$(printf '%s\t' "$(hostname -s 2>/dev/null || hostname)" "${f##*/}" "$(field "$f" owner)" "$(field "$f" co)" "${g:-0}" "$gid" \
    "$(utc "$cr")" "$(utc "$st")" "$(utc "$en")" "$ws" "$rs" "${rc:--}" "$(field "$f" state)")
  a=${a:0:$((3990 - ${#line}))}
  [ -s "$TLOG" ] || printf 'host\tticket\towner\tcheckout\tport_group\tgroup_id\tenqueued\tgranted\tended\twait_s\trun_s\trc\tstate\targs\n' >> "$TLOG"
  printf '%s%s\n' "$line" "$a" >> "$TLOG"
}
