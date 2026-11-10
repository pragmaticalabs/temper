#!/usr/bin/env python3
"""Posts the temper gate verdict of <job> as commit status `temper/gate` on PR <pr>'s head, or on branch <name>'s head
(a merged tip, gated after its PRs), with the maintainer-approved text (rollout item 4).
Dry run by default; --post sends. Refuses when the PR or branch head is not the job's SHA, when the job ran with tests
skipped, narrowed or under an option temper does not list (a `userProperties` entry with any `tests` mark: skipped,
narrowed or unknown, m4-design §5.1: such a run is no gate verdict), and for
states with no approved text.
usage: post-gate-status.py <job> (<pr> | --branch <name>) [--flake Class#method --rerun <job>] [--post]"""
import argparse, json, subprocess, sys

REPO = "pragmaticalabs/pragmatica"
CLI = ["java", "-jar", __import__("os").path.expanduser("~/.local/lib/temper/temper-cli.jar")]

def result(job):
    return json.loads(subprocess.run(CLI + ["result", str(job), "--json"], check=True, capture_output=True, text=True).stdout)

def refuse(why):
    print("REFUSED: " + why, file=sys.stderr); sys.exit(3)

def status(r, flake, rerun):
    job, verdict, counts = r["job"], r["verdict"], r["counts"]
    if verdict == "PASSED":
        if flake:
            refuse("a PASSED job needs no flake rerun")
        return "success", f"temper gate passed: {counts['testcases']} tests, {counts['failed']} failed, {counts['skipped']} skipped (job {job})"
    if verdict == "FAILED" and flake:
        rr = result(rerun)
        if rr["source"]["sha"] != r["source"]["sha"] or rr["verdict"] != "PASSED":
            refuse(f"rerun {rerun} is not a PASSED job on the same SHA")
        failing = {f["class"] + "#" + f["name"] for f in r["failed"]}
        if failing != {flake}:
            refuse(f"the gate's failing methods {sorted(failing)} are not exactly the flake {flake}")
        return "success", f"temper gate passed after one rerun of known flaky test {flake} (job {job}, rerun {rerun})"
    if verdict == "FAILED":
        failing = r["failed"]
        if not failing:
            refuse(f"FAILED with no failing test (reason {r['reason']}): no approved text yet")
        classes = {f["class"] for f in failing}
        return "failure", f"temper gate failed: {len(failing)} tests in {len(classes)} classes (job {job})"
    if verdict in ("REFUSED", "ERROR", "EMPTY", "VOID"):
        return "error", f"temper gate could not judge this head: {r['reason'] or verdict.lower()} (job {job})"
    if verdict in ("QUEUED", "RUNNING"):
        return "pending", f"temper gate running (job {job})"
    refuse(f"verdict {verdict} is not posted")

def main():
    a = argparse.ArgumentParser()
    a.add_argument("job", type=int); a.add_argument("pr", type=int, nargs="?"); a.add_argument("--branch")
    a.add_argument("--flake"); a.add_argument("--rerun", type=int); a.add_argument("--post", action="store_true")
    args = a.parse_args()
    if bool(args.flake) != bool(args.rerun):
        refuse("--flake and --rerun go together")
    if (args.pr is None) == (args.branch is None):
        refuse("give exactly one of <pr> and --branch")
    r = result(args.job)
    marked = [p["define"] for p in r.get("userProperties") or [] if p.get("tests")]
    if marked:
        refuse(f"job {args.job} ran with tests skipped or narrowed ({', '.join(marked)}): not a gate verdict")
    if args.branch:
        what = f"branch {args.branch}"
        head = subprocess.run(["gh", "api", f"repos/{REPO}/commits/heads/{args.branch}", "--jq", ".sha"],
                              check=True, capture_output=True, text=True).stdout.strip()
    else:
        what = f"PR #{args.pr}"
        head = subprocess.run(["gh", "pr", "view", str(args.pr), "-R", REPO, "--json", "headRefOid", "--jq", ".headRefOid"],
                              check=True, capture_output=True, text=True).stdout.strip()
    if head != r["source"]["sha"]:
        refuse(f"{what} head {head[:10]} is not job {args.job}'s SHA {r['source']['sha'][:10]}")
    state, description = status(r, args.flake, args.rerun)
    if len(description) > 140:
        refuse(f"description is {len(description)} chars, over 140")
    body = {"state": state, "context": "temper/gate", "description": description}
    print(json.dumps({"sha": head, **body}))
    if args.post:
        subprocess.run(["gh", "api", "-X", "POST", f"repos/{REPO}/statuses/{head}", "--input", "-"],
                       input=json.dumps(body), check=True, capture_output=True, text=True)
        print("POSTED")

main()
