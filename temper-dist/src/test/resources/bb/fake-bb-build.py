#!/usr/bin/env python3
# Fake ~/tools/bb-build for BbMutateIT: argv = <checkout> <mvn args...>. Fabricates surefire XML per module of
# $HOME/scenario.json, in reactor order. A module is "mutated" when <module>/src/f.txt contains MUT. A mutated module's first
# testcase fails unless the module has a fault: zero (suite for the class, no testcases), otherclass (suite of another class),
# stale (writes nothing), inert (mutated, but its tests pass). A module with "breaks":[names] ALSO fails its first testcase when any
# listed module is mutated (a change in another file breaking this module's test: the #97 cross-file shape).
# A module's "failN":N makes its first N testcases fail instead of the first one (the #100 truncation shape).
# Top-level "ignoreFailures":true makes rc 0 although testcases fail (testFailureIgnore).
# More scenario keys: "refuseWhen":N makes the call exit 1 (bb-build refused, no build runs) when exactly N modules are mutated;
# "rcWhen":N forces rc 1 (reports untouched) when exactly N modules are mutated; fault "compileerr" (mutated, tests pass) puts a
# COMPILATION ERROR into .bb/build.log; fault "hang" (mutated) starts, then never ends (no rc) until its detached child exits;
# fault "bounded" (mutated) is stopped by bb-build's bound runner: rc 124, no results;
# fault "exit124" (mutated, tests pass) ends by ITSELF with rc 124 (a job whose own exit status is 124, no bound hit: its ticket carries job_end=);
# fault "nostart" never starts at all. A ticket file T1 is written under $HOME/work/queue (running/), as bb-build's does: "bounded" with
# bound_hit=, "exit124" with job_end=. A hung job is a detached child that sleeps 20 s, so bb-mutate must kill the build by its bound.
# Fault "boundeddone" is "bounded" in the production order: bb-build writes .bb/rc 124 while the ticket (with bound_hit=) is still in running/, then its
# waiter moves the ticket to done/ (state=done); the fake does both before it exits, so bb-mutate reads the ticket from done/ only.
# Top-level "mvnSecs":N makes .bb/start read N seconds before now (a fake clock: the control's Maven time is N s at once).
# A module with a failed or skipped dependency is skipped; without -fae every module after a failure is.
import json, os, sys, time

co, args = sys.argv[1], sys.argv[2:]
home = os.environ["HOME"]
sc = json.load(open(home + "/scenario.json"))
b = co + "/.bb"
os.makedirs(b, exist_ok=True)
time.sleep(0.05)
fae = "-fae" in args
mutated = [m["name"] for m in sc["modules"] if "MUT" in open(f"{co}/{m['name']}/src/f.txt").read()]
if sc.get("refuseWhen") == len(mutated):
    sys.stderr.write("bb-build: refused\n")
    sys.exit(1)
for f in ("rc", "end", "start"):  # as bb-build's call does: what .bb holds from here on is this build's
    if os.path.exists(b + "/" + f):
        os.remove(b + "/" + f)
faults = [m.get("fault") for m in sc["modules"] if m["name"] in mutated]
if "nostart" not in faults:
    open(b + "/start", "w").write(str(int(time.time()) - sc.get("mvnSecs", 0)) + "\n")
open(home + "/builds.log", "a").write("[" + ",".join(mutated) + "] " + str(fae).lower() + "\n")
open(home + "/bounds.log", "a").write(os.environ.get("BB_MUTANT_BOUND", "<unset>") + " " + os.environ.get("BB_ORDER_AT", "<unset>") + "\n")
open(home + "/via.log", "a").write(os.environ.get("BB_VIA", "<unset>") + "\n")
def ticket(line):
    d = os.environ.get("BB_TEST_QUEUE_ROOT", home + "/work/queue") + "/running"
    os.makedirs(d, exist_ok=True)
    open(d + "/T1", "w").write("id=T1\nstate=running\n" + line + "\n")

if "boundeddone" in faults:
    ticket("bound_hit=%d" % time.time())
    open(b + "/end", "w").write(str(int(time.time())) + "\n")
    open(b + "/rc", "w").write("124\n")
    q = os.environ.get("BB_TEST_QUEUE_ROOT", home + "/work/queue")
    os.makedirs(q + "/done", exist_ok=True)
    os.rename(q + "/running/T1", q + "/done/T1")  # the waiter's to_done, after the rc
    print("ticket=T1")
    sys.exit(0)
if "bounded" in faults:
    ticket("bound_hit=%d" % time.time())
    open(b + "/end", "w").write(str(int(time.time())) + "\n")
    open(b + "/rc", "w").write("124\n")
    print("ticket=T1")
    sys.exit(0)
if "hang" in faults or "nostart" in faults:
    print("ticket=T1", flush=True)
    if os.fork():
        os._exit(0)
    os.setsid()
    os.chdir("/")
    nul = os.open(os.devnull, os.O_RDWR)
    for fd in (0, 1, 2):
        os.dup2(nul, fd)
    time.sleep(20)
    os._exit(0)
open(b + "/build.log", "w").write("COMPILATION ERROR\n" if "compileerr" in faults else "BUILD SUCCESS\n")
bad, rc = set(), 0
for m in sc["modules"]:
    n = m["name"]
    if any(d in bad for d in m.get("deps", [])) or (bad and not fae):
        bad.add(n)
        continue
    mut, fault = n in mutated, m.get("fault")
    d = f"{co}/{n}/target/surefire-reports"
    os.makedirs(d, exist_ok=True)
    cls = m["class"]
    if mut and fault == "stale":
        continue
    if mut and fault == "zero":
        cases = []
    else:
        if mut and fault == "otherclass":
            cls = "Other" + cls
        cases = [(cls, c) for c in m["cases"]]
    fail = (mut and not fault) or any(x in mutated for x in m.get("breaks", []))
    body = "".join(f'<testcase classname="{c}" name="{t}">' + ("<failure message=\"x\"/>" if fail and i < m.get("failN", 1) else "") + "</testcase>"
                   for i, (c, t) in enumerate(cases))
    open(f"{d}/TEST-{cls}.xml", "w").write(f'<testsuite name="{cls}" tests="{len(cases)}">{body}</testsuite>')
    if fail:
        bad.add(n)
        rc = 1
if "exit124" in faults:
    ticket("job_end=%d" % time.time())
    rc = 124
open(b + "/end", "w").write(str(int(time.time())) + "\n")
open(b + "/rc", "w").write(f"{1 if sc.get('rcWhen') == len(mutated) else 0 if sc.get('ignoreFailures') else rc}\n")
print("ticket=T1")
