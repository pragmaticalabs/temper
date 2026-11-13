#!/usr/bin/env python3
# Fake ~/tools/bb-build for BbMutateIT: argv = <checkout> <mvn args...>. Fabricates surefire XML per module of
# $HOME/scenario.json, in reactor order. A module is "mutated" when <module>/src/f.txt contains MUT. A mutated module's first
# testcase fails unless the module has a fault: zero (suite for the class, no testcases), otherclass (suite of another class),
# stale (writes nothing). A module with a failed or skipped dependency is skipped; without -fae every module after a failure is.
import json, os, sys, time

co, args = sys.argv[1], sys.argv[2:]
home = os.environ["HOME"]
sc = json.load(open(home + "/scenario.json"))
b = co + "/.bb"
os.makedirs(b, exist_ok=True)
time.sleep(0.05)
open(b + "/start", "w").write(str(int(time.time())) + "\n")
fae = "-fae" in args
mutated = [m["name"] for m in sc["modules"] if "MUT" in open(f"{co}/{m['name']}/src/f.txt").read()]
open(home + "/builds.log", "a").write("[" + ",".join(mutated) + "] " + str(fae).lower() + "\n")
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
    fail = mut and not fault
    body = "".join(f'<testcase classname="{c}" name="{t}">' + ("<failure message=\"x\"/>" if fail and i == 0 else "") + "</testcase>"
                   for i, (c, t) in enumerate(cases))
    open(f"{d}/TEST-{cls}.xml", "w").write(f'<testsuite name="{cls}" tests="{len(cases)}">{body}</testsuite>')
    if fail:
        bad.add(n)
        rc = 1
open(b + "/end", "w").write(str(int(time.time())) + "\n")
open(b + "/rc", "w").write(f"{rc}\n")
print("ticket=T1")
