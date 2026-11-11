#!/usr/bin/env python3
"""Fixture tests for capwatch. Interval 60 s, so sample k is at t=+60k s."""
import os, subprocess, sys, tempfile, json
HERE = os.path.dirname(os.path.abspath(__file__)); CW = os.path.join(HERE, 'capwatch')
Q_EMPTY = "RUNNING (reactors on host: 0; cap 5 unless a ticket overrides; port group held by: )\nTICKET OWNER PRIO AGE CHECKOUT ARGS\n\nWAITING (grant order)\nPOS  TICKET OWNER PRIO WAITED CHECKOUT ARGS\n\nFINISHED (most recent 10)\n"
def q_wait(w): return Q_EMPTY.replace("CHECKOUT ARGS\n\nFINISHED", "CHECKOUT ARGS\n  1  123-45  me  0  %s  co  -B\n\nFINISHED" % w)
def host(name, load5=1.0, cores=16, state='UP', link='UP', foreign=2, mem_kb=8 * 1024 * 1024, running=1):
    return dict(name=name, state=state, link=link, cap=5, active=0, facts=dict(load1=load5, load5=load5, cores=cores, foreignReactors=foreign, bbRunning=running, bbWaiting=0, memAvailableKb=mem_kb, freeWorkspaceBytes=1, at='x'))
# a sample = dict(host=(controller-dict, queue-text|None))
def run(samples, *flags):
    d = tempfile.mkdtemp()
    for i, s in enumerate(samples):
        json.dump([c for c, _ in s.values()], open(os.path.join(d, "%03d.json" % i), 'w'))
        for n, (_, q) in s.items():
            if q is not None: open(os.path.join(d, "%03d.%s.queue" % (i, n)), 'w').write(q)
    r = subprocess.run([sys.executable, CW, '--fixture', d, '--fixture-hosts', ','.join(samples[0]), '--interval', '60', *flags], capture_output=True, text=True)
    return r.returncode, r.stdout.strip(), r.stderr
fails = 0; total = 0
def check(name, cond, info=''):
    global fails, total; total += 1
    if not cond: fails += 1
    print(("ok   " if cond else "FAIL ") + name + ('' if cond else '  -> ' + info))
def fires_at(out, kind, t):  # event line present with t
    return out.startswith(kind) and "[t=+%ds]" % t in out
def one(load=1.0, q=Q_EMPTY, **kw): return {'h': (host('h', load5=load, **kw), q)}
HOT, COLD, MID = 16 * 1.6, 16 * 0.5, 16 * 1.0   # load5 on 16 cores
# --- SATURATED load: below start (OK), then hot from sample 1; fires at sample 1+15 (t=960), not at 14 (t=900-60)
s = [one(MID)] + [one(HOT)] * 15           # hot at samples 1..15 -> 14 min elapsed at sample 15
rc, out, _ = run(s); check("SAT-load: not one sample before (14m elapsed)", out == "FIXTURE-END no event", out)
rc, out, _ = run(s + [one(HOT)]); check("SAT-load: fires at exactly 15m (t=960)", rc == 0 and fires_at(out, "SATURATED h (load)", 960), out)
# exactly 1.5/core is not > 1.5
rc, out, _ = run([one(MID)] + [one(24.0)] * 20); check("SAT-load: load/core == 1.5 does not count", out == "FIXTURE-END no event", out)
# dip resets the timer
s = [one(MID)] + [one(HOT)] * 10 + [one(MID)] + [one(HOT)] * 15
rc, out, _ = run(s); check("SAT-load: dip resets timer (no event 14m after dip)", out == "FIXTURE-END no event", out)
rc, out, _ = run(s + [one(HOT)]); check("SAT-load: after dip fires 15m after restart (t=1620)", fires_at(out, "SATURATED h (load)", 1620), out)
# --- SATURATED wait > 30 min: immediate
rc, out, _ = run([one(MID), one(MID, q_wait('0h30m'))]); check("SAT-wait: 30m is not > 30m", out == "FIXTURE-END no event", out)
rc, out, _ = run([one(MID), one(MID, q_wait('0h31m'))]); check("SAT-wait: 31m fires at once (t=60)", fires_at(out, "SATURATED h (wait)", 60) and 'oldest-wait=0h31m' in out, out)
rc, out, _ = run([one(MID), one(MID, q_wait('1h15m'))]); check("SAT-wait: 1h15m parsed", fires_at(out, "SATURATED h (wait)", 60), out)
# --- IDLE: load/core<0.6, no waiting, 10 min
s = [one(MID)] + [one(COLD)] * 10
rc, out, _ = run(s); check("IDLE: not one sample before (9m)", out == "FIXTURE-END no event", out)
rc, out, _ = run(s + [one(COLD)]); check("IDLE: fires at exactly 10m (t=660)", fires_at(out, "IDLE h", 660) and 'free-slots=4/5' in out and 'port-group=free' in out, out)
rc, out, _ = run([one(MID)] + [one(COLD)] * 5 + [one(MID)] + [one(COLD)] * 10); check("IDLE: dip resets timer", out == "FIXTURE-END no event", out)
rc, out, _ = run([one(MID)] + [one(COLD, q_wait('0h01m'))] * 15); check("IDLE: a WAITING ticket blocks", out == "FIXTURE-END no event", out)
rc, out, _ = run([one(MID)] + [one(9.6)] * 15); check("IDLE: load/core == 0.6 does not count", out == "FIXTURE-END no event", out)
# --- BIGBOY-FREE
def bb(foreign=0, mem_gib=13): return {'bigboy': (host('bigboy', foreign=foreign, mem_kb=int(mem_gib * 1024 * 1024), load5=8), Q_EMPTY)}
s = [bb(2)] + [bb()] * 10
rc, out, _ = run(s); check("BIGBOY-FREE: not one sample before (9m)", out == "FIXTURE-END no event", out)
rc, out, _ = run(s + [bb()]); check("BIGBOY-FREE: fires at exactly 10m (t=660)", fires_at(out, "BIGBOY-FREE", 660), out)
rc, out, _ = run([bb(2)] + [bb(mem_gib=12)] * 15); check("BIGBOY-FREE: memAvailable == 12 GiB does not count", out == "FIXTURE-END no event", out)
rc, out, _ = run([bb(2)] + [bb()] * 5 + [bb(1)] + [bb()] * 10); check("BIGBOY-FREE: dip resets timer", out == "FIXTURE-END no event", out)
# --- LINK-LOST: 3 consecutive samples
down = lambda **k: one(MID, **k)
s = [one(MID), down(state='DOWN'), down(state='DOWN')]
rc, out, _ = run(s); check("LINK-LOST: 2 samples do not fire", out == "FIXTURE-END no event", out)
rc, out, _ = run(s + [down(state='DOWN')]); check("LINK-LOST: 3rd sample fires (t=180)", fires_at(out, "LINK-LOST h", 180), out)
rc, out, _ = run([one(MID), down(link='DOWN'), down(link='DOWN'), one(MID), down(link='DOWN'), down(link='DOWN')]); check("LINK-LOST: a good sample resets the count", out == "FIXTURE-END no event", out)
rc, out, _ = run([one(MID), down(link='DOWN'), down(link='DOWN'), down(link='DOWN')]); check("LINK-LOST: link != UP fires", fires_at(out, "LINK-LOST h", 180), out)
# unreachable host (no queue) while controller says UP: warning, no event
rc, out, err = run([one(MID)] + [one(MID, q=None)] * 5); check("unreachable but controller UP: warning only", out == "FIXTURE-END no event" and 'unreachable' in err, out + err)
# --- startup rule
s = [one(HOT, q=q_wait('1h00m'))] * 30
rc, out, _ = run(s); check("startup: already-true SAT is silent", out == "FIXTURE-END no event", out)
rc, out, _ = run(s, '--report-initial'); check("startup: --report-initial reports at t=0", rc == 0 and out.startswith("SATURATED h") and "[t=+0s]" in out, out)
rc, out, _ = run([down(state='DOWN')] * 6); check("startup: already-lost link silent", out == "FIXTURE-END no event", out)
# pre-existing condition, ends, comes back: fires on the new episode
s = [one(COLD)] + [one(MID)] + [one(COLD)] * 11
rc, out, _ = run(s); check("startup: episode after a clear fires (IDLE t=720)", fires_at(out, "IDLE h", 720), out)
# --once
d = tempfile.mkdtemp(); json.dump([host('h', load5=HOT)], open(d + '/000.json', 'w')); open(d + '/000.h.queue', 'w').write(Q_EMPTY)
r = subprocess.run([sys.executable, CW, '--fixture', d, '--fixture-hosts', 'h', '--once'], capture_output=True, text=True)
check("--once prints classification and exits 0", r.returncode == 0 and 'SATURATED-LOAD (instant)' in r.stdout, r.stdout)
# --- JAVA-STALE: a service on a deleted JDK, sustained 10 min
def run_stale(stale_by_sample):
    d = tempfile.mkdtemp()
    for i, st in enumerate(stale_by_sample):
        json.dump([host('h', load5=MID)], open(os.path.join(d, "%03d.json" % i), 'w'))
        open(os.path.join(d, "%03d.h.queue" % i), 'w').write(Q_EMPTY)
        if st: open(os.path.join(d, "%03d.h.stale" % i), 'w').write("\n".join(st) + "\n")
    r = subprocess.run([sys.executable, CW, '--fixture', d, '--fixture-hosts', 'h', '--interval', '60'], capture_output=True, text=True)
    return r.returncode, r.stdout.strip()
S = ['temper-controller']
rc, out = run_stale([[]] + [S] * 10); check("JAVA-STALE: not one sample before (9m)", out == "FIXTURE-END no event", out)
rc, out = run_stale([[]] + [S] * 11); check("JAVA-STALE: fires at 10m (t=660) naming the unit", fires_at(out, "JAVA-STALE h", 660) and 'temper-controller' in out, out)
rc, out = run_stale([[]] + [S] * 5 + [[]] + [S] * 10); check("JAVA-STALE: a restart (clear) resets the window", out == "FIXTURE-END no event", out)
rc, out = run_stale([[]] * 20); check("JAVA-STALE: no stale services, no event", out == "FIXTURE-END no event", out)

# --- --pick: ratio waiting/max(cap-running,1), then load; ties within 0.2 load/core are random
def q_rw(running=0, waiting=0):
    r = "".join("  %d-1  me  0  1h  co  -B\n" % i for i in range(running)); w = "".join("  %d  9-%d  me  0  0h01m  co  -B\n" % (i + 1, i) for i in range(waiting))
    return ("RUNNING (reactors on host: 0; cap 5 unless a ticket overrides; port group held by: )\nTICKET OWNER PRIO AGE CHECKOUT ARGS\n" + r +
            "\nWAITING (grant order)\nPOS  TICKET OWNER PRIO WAITED CHECKOUT ARGS\n" + w + "\nFINISHED (most recent 10)\n")
def hp(name, lpc=0.5, running=0, waiting=0, **kw): return (name, (host(name, load5=16 * lpc, running=None, **kw), q_rw(running, waiting)))
def pick(hosts, *flags): return run([dict(hosts)], '--pick', *flags)
def seeds(hosts, n=40): return {pick(hosts, '--seed', str(s))[1] for s in range(n)}
rc, out, err = pick([hp('a', 1.2, 3, 4), hp('b', 0.1, 0, 0), hp('c', 1.0, 2, 2)], '--seed', '1'); check("PICK: idle host among loaded ones", (rc, out) == (0, 'b') and 'picked b' in err, out + err)
rc, out, _ = pick([hp('lowload', 0.2, 5, 3), hp('hot', 1.0, 1, 0)]); check("PICK: ratio beats load (3/1 loses to 0)", (rc, out) == (0, 'hot'), out)
rc, out, _ = pick([hp('sat', 1.6, 0, 0), hp('lost', 0.1, state='DOWN'), hp('lk', 0.1, link='DOWN'), hp('ok', 1.4, 4, 9)]); check("PICK: saturated and lost hosts never picked", (rc, out) == (0, 'ok'), out)
rc, out, _ = pick([hp('sat', 1.6, 0, 0), hp('lost', 0.1, state='DOWN')]); check("PICK: nothing eligible -> exit 3, empty stdout", (rc, out) == (3, ''), out)
rc, out, _ = pick([hp('sat', 1.5, 0, 0)]); check("PICK: load/core == sat-load is still eligible", (rc, out) == (0, 'sat'), out)
D = [hp('a', 0.5), hp('b', 0.6), hp('far', 0.9)]
got = seeds(D); check("PICK: tie within 0.2 load/core: both picked across seeds, far never", got == {'a', 'b'}, str(got))
check("PICK: fixed seed is deterministic", len({pick(D, '--seed', '7')[1] for _ in range(5)}) == 1)
got = seeds([hp('a', 0.5), hp('b', 0.8)]); check("PICK: load gap > 0.2 is not a tie", got == {'a'}, str(got))
got = seeds([hp('a', 0.5), hp('b', 0.5, 5, 1)], 20); check("PICK: unequal ratio is not a tie (running=5 waiting=1 -> 1/1)", got == {'a'}, str(got))
rc, out, _ = pick([hp('d1', 0.1), hp('x', 1.0, 5, 6)], '--seed', '1'); check("PICK: explicit-pick host (config not consulted) is eligible", (rc, out) == (0, 'd1'), out)
rc, out, _ = run([{'a': (host('a', load5=1), None), 'b': hp('b', 1.0, 5, 5)[1]}], '--pick'); check("PICK: unreadable queue is ineligible", (rc, out) == (0, 'b'), out)
# v-t16 HIGH + MEDIUM: a FULL host (queue shows 5 running = cap) with an empty wait queue and lower load, whose controller facts
# claim bbRunning=0, must lose to a host with a free slot -- red if free slots are not ranked first, red if running is read from
# the controller's bbRunning instead of the queue
full = ('full', (host('full', load5=16 * 0.1, running=0), q_rw(5, 0)))
got = seeds([full, hp('roomy', 0.5, 1, 0)], 20); check("PICK: full host with empty queue loses to a host with a free slot", got == {'roomy'}, str(got))
rc, out, err = pick([full], '--seed', '1'); check("PICK: only full hosts -> still picks one, says no free slot", (rc, out) == (0, 'full') and 'no host has a free slot' in err, out + err)

# --- --pick --group: free port-group slot required (headers: old 'held by', new 'slots/group tickets running', transitional 'slots/held by')
def gq(hdr): return q_rw().replace("port group held by: )", hdr + ")")
def gh(name, hdr, lpc=0.5): return (name, (host(name, load5=16 * lpc, running=None), gq(hdr)))
def gpick(hosts, *f): return run([dict(hosts)], '--pick', '--group', *f)
rc, out, _ = gpick([gh('full', 'port-group slots: 2; group tickets running: a[slot 1] b[slot 2]', 0.1), gh('ok', 'port-group slots: 2; group tickets running: a[slot 1]', 0.9)]); check("GROUP: skips a host with full slots", (rc, out) == (0, 'ok'), out)
rc, out, _ = gpick([gh('hx', 'port-group slots: 2; group tickets running: a[host]', 0.1), gh('ok', 'port-group slots: 2; group tickets running: ', 0.9)]); check("GROUP: skips a host with a [host] ticket", (rc, out) == (0, 'ok'), out)
rc, out, _ = gpick([gh('half', 'port-group slots: 2; group tickets running: a[slot 2]')]); check("GROUP: 1 of 2 slots free is pickable", (rc, out) == (0, 'half'), out)
rc, out, _ = gpick([gh('old', 'port group held by: ')]); check("GROUP: old format, empty holder = 1 free", (rc, out) == (0, 'old'), out)
rc, out, _ = gpick([gh('oldheld', 'port group held by: 123-4')]); check("GROUP: old format, held = none free -> exit 3", (rc, out) == (3, ''), out)
rc, out, _ = gpick([gh('t', 'port-group slots: 2; port group held by: a[slot 1] b[slot 2]', 0.1), gh('t2', 'port-group slots: 2; port group held by: a[host]'), gh('t3', 'port-group slots: 2; port group held by: a[slot 1]', 0.9)]); check("GROUP: transitional header form (slots + held by)", (rc, out) == (0, 't3'), out)
rc, out, _ = pick([gh('full', 'port-group slots: 1; group tickets running: a[slot 1]', 0.1)]); check("GROUP: plain --pick ignores the port group", (rc, out) == (0, 'full'), out)

print("\n%d checks, %d failed" % (total, fails)); sys.exit(1 if fails else 0)
