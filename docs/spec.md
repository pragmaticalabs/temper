# temper — specification (first cut)

**Status.** SPEC v0.2, **signed off by the maintainer 2026-10-13** (each milestone exit gate gets an independent verifier, never the author; results are recorded in the milestone notes). Derived from `docs/design.md` rev 2. The design keeps the
rationale; this file is the contract. v0.2 folds in the maintainer's review of v0.1 (16 items; see §15).

**Who rules on what.**
- **Requirements:** the maintainer session (primary user).
- **Scope, stack, repo, spend and the shared `~/.m2`:** the owner.

**Conventions.**
- "MUST" means a test pins it.
- Every guarantee names its mechanism.
- Anything marked **[proposal]** is temper's choice and may be challenged.

---

## 0. Glossary

| Term | Meaning |
|---|---|
| job | what a client submitted: source + recipe + params. Immutable once accepted |
| unit | one container execution belonging to a job (build, `rest`, `ember-k`, control, mutant-i, rerun) |
| source | `Pushed{ref,sha}` · `Bundle{sha,blob}` · `Patch{baseSha,diff}` |
| recipe | `Gate` · `ModuleTest` · `Mutate` · `Mvn` (first cut); `Ci` (phase 2) |
| manifest | the set of test classes a job is expected to run, computed after compilation |
| evidence | the files that back a result: rc/start/end/marker, args, testcase XML (gz), log tail, image id |
| verdict | the terminal job outcome (§5.3) |
| UNKNOWN | a job or unit state: running, start not recorded (§6.4) |
| host | a build machine reached by ssh, running one worker process per controller connection |
| subscriber | a named, durable event cursor (an agent session, a Monitor) |

---

## 1. Components and repo layout

```
temper/                          pragmaticalabs/temper (public)
  pom.xml                        parent; Java 25; Pragmatica 1.0.0-rc4 from ~/.m2 via .m2-local seed
  .mvn/maven.config              -Dmaven.repo.local=.m2-local (skip-worktree, per global rules)
  temper-domain/                 values, recipes, verdict rules, planner, refusals, manifest, report parser
                                 (pure: no I/O)
  temper-protocol/               controller⇄worker frames, codec (length-prefixed JSON)
  temper-store/                  Postgres schema (migrations), repositories (postgres-async)
  temper-controller/             HTTP API, WebSocket events, scheduler, host links, reconciler, GC
  temper-worker/                 frame loop, git/docker/fs adapters, unit runner, report collector
  temper-cli/                    `temper` command; thin HTTP/WebSocket client
  docs/                          design.md, spec.md
```

- **Coding standard:** JBCT. All code goes through `jbct-coder`; `jbct-reviewer` reviews it.
  - Every I/O boundary is an interface that returns `Promise<T>`.
  - Domain logic is pure, so it is testable without hosts.
- **Where things run:**
  - The controller is one JVM on bigboy, plus Postgres 16 in docker on bigboy
    (`postgres:16` is already present).
  - The worker is one JVM per (controller, host) link, started over ssh.
  - The CLI is a JVM fat jar on the Mac with a `~/.local/bin/temper` wrapper.

---

## 2. Job model

### 2.1 Submit request

```json
{
  "submittedBy": "v2174",                    // owner tag: [A-Za-z0-9_]+ (same rule as BB_OWNER)
  "priority": -20,                           // optional; default from priority rules (§6.3)
  "keep": false,                             // pin: exempt from supersede-cancel and GC
  "source": { "pushed": { "ref": "rewrite/deployment-fsm" } }
          | { "pushed": { "sha": "0cf563192a…" } }
          | { "bundle": { "blobId": "…", "sha": "…" } }        // blob uploaded first (§7.1)
          | { "patch":  { "baseSha": "…", "diff": "<unified diff>" } },
  "recipe": { "gate": {} }
          | { "moduleTest": { "pl": ["aether/ember"], "test": "Foo*,Bar", "itTest": null } }
          | { "mutate": { "spec": { …bb-mutate spec… } } }
          | { "mvn": { "args": ["-pl","core","verify"] } }
}
```

### 2.2 Source rules

- **`Pushed`**
  - A ref is resolved with `git ls-remote origin <ref>` **inside the submit call**, and the
    resolved SHA is stored.
  - A bare SHA must be contained in a remote ref. The check is `ls-remote` plus a fetch, then
    `git branch -r --contains`, run against the controller's mirror.
  - The remote refs that count are branch heads (`refs/heads/*`) and **same-repo**
    pull-request heads (`refs/pull/<N>/head`; maintainer M1-3, R-M2-7). `refs/pull/*/merge` and tags
    do not count.
  - Pull-request heads are consulted only when no branch head contains the SHA. GitHub publishes
    fork-PR heads under the same ref names, and fork PRs are a non-goal (§13). So for each such
    `N`, the controller asks the GitHub API (`GET /repos/{owner}/{repo}/pulls/{N}`,
    unauthenticated). A PR is same-repo iff `head.repo` is non-null and its `full_name` equals
    `base.repo.full_name`. A deleted fork (null `head.repo`) is a fork.
  - A ref given in full outside `refs/heads` and `refs/tags` (e.g. `refs/pull/7/head`) resolves
    through `ls-remote` and must then pass the bare-SHA rule.
  - If either check fails, the submit is `REFUSED(source)`.
  - Every PR head containing the SHA is a candidate. Candidates are judged oldest first, by PR
    number as an integer, and the first same-repo PR accepts.
  - Fork PRs are usually newer, but an older fork can be force-pushed. So neither a fork answer
    nor a per-PR failure stops the walk.
  - A judged origin is kept per controller process, so each PR costs one API call per process.
    A restart costs those calls again. Failures are not kept.
  - If every candidate was judged a fork, the submit is `REFUSED(fork-pr)`.
  - The check fails closed. These all give `REFUSED(pr-origin-unverified)`, never an accept:
    - an origin URL that is not github.com;
    - an exchange not complete within 10 s (headers and body together);
    - a body over 1 MiB;
    - any status but 200 (the rate limit is 403 or 429);
    - an unreadable answer.
  - Per-PR failures are 404, 410, 422, an unreadable or incomplete answer, and a declared body
    over 1 MiB. They are recorded, and the walk goes on.
  - Every other failure stops the walk to save the API budget. This covers the rate limit (403,
    429), a 5xx, no answer and a non-github origin.
  - Candidates not reached are named in the detail as not checked, never counted as forks.
  - A same-repo PR still accepts, whatever failures came before it. A resubmit may succeed.
  - Rate-limit exhaustion (60 unauthenticated calls per hour) refuses with
    `pr-origin-unverified`. It recovers once the budget resets, and the origins judged so far are
    kept.
- **`Bundle`**
  - The bundle must contain `sha`.
  - The result is tagged `unpushed`.
- **`Patch`**
  - `git apply --index --check` must succeed against `baseSha`, which must satisfy the Pushed
    rule. Otherwise the submit is `REFUSED(patch)`.
  - The result is tagged `unpushed` and records `sha256(diff)`.
- **Evidence rule:** `unpushed` results never satisfy `temper gate` (§8) and never count as PR
  evidence.

### 2.3 Dedup

```
dedupKey = sha256(sourceIdentity ‖ canonicalRecipeJson ‖ runnerVersion)
```

- `sourceIdentity` is the sha, or `baseSha+diffHash`, or `bundleSha`.
- `runnerVersion` is the controller's whole build version (`<project.version>+<judge12>[-dirty]` or
  `<project.version>+unjudged`, D-6), not the bare `project.version` (owner decision D-6 A1). A verdict of another
  temper build is never reused: after a deploy, each re-requested (commit, recipe) runs once more. A `-dirty` or
  `+unjudged` build never shares a key with the clean build of its commit. The runner's docker tag stays the bare
  `project.version`, and the supersede key (§2.4) has no runner component.
- A submit whose `dedupKey` matches a job in state **QUEUED, UNKNOWN, RUNNING, PASSED, FAILED or EMPTY**
  returns that job's id with `deduplicated: true`.
- A match against a **VOID, ERROR or CANCELLED** job does not deduplicate; a new job runs. Those
  states describe the environment, not the code.
- `--fresh` bypasses dedup. It is recorded on the job, and is intended for flake re-checks
  requested by humans.

### 2.4 Supersede (maintainer §11.1)

When a `Pushed{ref}` job is accepted for ref R at SHA S, every earlier non-terminal job with the
same (R, recipe) and a different SHA is handled as follows:

| State | Action |
|---|---|
| QUEUED | → CANCELLED(superseded) |
| RUNNING, not `keep` | → SUPERSEDED → CANCELLED |
| RUNNING, `keep` | runs on; marked `superseded` |

Jobs submitted by SHA, Bundle or Patch are never superseded.

### 2.5 Runner-level refusals

These apply to every unit, whatever its job form. A test pins each one.

There are two kinds:
- **Structural refusals** (R-ENV, R-M2, R-SHELL, R-DISK) are code.
- **Tree-specific refusals** (R-1553, R-FAILSAFE) are **data**. Each is a row in the `refusals`
  table with `(code, matcher, issue, reason, until, expired_at)`, managed with
  `temper refusal add|list|expire`, so a refusal doesn't outlive its issue. Both rows are seeded at
  install.

| Code | Rule |
|---|---|
| R-1553 | If the tree's `StreamPublishReshuffleTest` is not `@Disabled("#1553")`, the unit args must exclude it (`-Dtest=!…`/`-Dit.test=!…`). Otherwise the unit is refused |
| R-FAILSAFE | A forge-tests selection with `-Dtest` but no `-Dit.test` is refused |
| R-ENV | The container environment is an explicit allow-list: `JAVA_HOME`, `MAVEN_OPTS`, `TESTCONTAINERS_HOST_OVERRIDE`, `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX`, `HOME`, `PATH`, `TZ=UTC`. Nothing else is passed. `*_TOKEN` cannot be set by any request field |
| R-M2 | `~/.m2` is never mounted. `-Dmaven.repo.local` is forced to the unit workspace; a user-supplied value is refused ; the workspace repo is seeded by **copy, never hardlink** (`find <repo> -type f -links +1` must be empty before any unit runs), because an in-place install through a hardlink writes the seed's original (maintainer, 2026-10-13) |
| R-SHELL | Commands are argv arrays executed by `docker run` with no shell; request fields never reach `sh -c` |
| R-DISK | A unit does not start if free space on the docker root or the workspace filesystem is below the floor (default 50 GiB **[proposal]**). It becomes VOID(disk) |

A refusal at submit makes the job `REFUSED(<code>)`. A refusal at unit start (R-DISK) makes the
unit, and therefore the job, `VOID(disk)`.

---

## 3. Recipes

### 3.1 Mvn and ModuleTest

- **Mvn** is one unit running `mvn <args>`.
- **ModuleTest** is one unit running `mvn -pl <pl> -am install -DskipTests` followed by
  `mvn -pl <pl> test -Dtest=<test>` (or `verify -Dit.test=` when `itTest` is set). The manifest
  covers `pl`, filtered by the selection.
- **Port group** (until the spike passes): a unit needs it if its reactor, run through the ported
  `bb-portgroup.py` closure rules, touches the port-group modules and runs tests (§6.2).

### 3.2 Gate

The Gate is the root `clean install`: default reactor, no `-pl`, no `-DskipTests` (maintainer).

- **Unsharded (default until the spike passes and §11 E1 passes).**
  - One unit runs `mvn clean install`.
  - It needs the port group.
- **Sharded** (behind the flag `gate.sharded`, per host). A **[proposal]**, maintainer-accepted:

  | Step | Unit(s) | Command | Notes |
  |---|---|---|---|
  | 1 | `build` | `mvn clean install -DskipTests` | The workspace is copied into the container FS. It emits the manifest |
  | 2 | freeze | `docker commit` → `temper/job-<id>:frozen` | labels `temper.job=<id>`. The image id is recorded in the evidence. `.m2-local` is inside the layer |
  | 3 | `rest` | `mvn -o install -Dember.skipTests=true` | full lifecycle, so all gate plugins execute |
  | 3 | `ember-1..N` | `mvn -o test -pl aether/ember -Dtest=<classes_k>` | each in its own container and network |
  | 4 | merge | — | the coverage check (§5.2) |

  - The planner uses LPT bin-packing (deterministic ties by name).
  - `N` is `gate.emberShards` (default 4 **[proposal]**).
  - Weights are the median of the last 5 passing durations per class. A class with no history
    gets the max weight. The seed is `tools/ember-shard-weights.txt` read from the job's tree.

### 3.3 Mutate

The spec format is bb-mutate's (`build{pl,tests,extra}`, `mutations[{id,file,kind,old,new,expect,zone}]`).

1. **Validate at submit.** Each mutation must match exactly once in the tree at the SHA, and its
   replacement must change the text. Otherwise the job is `REFUSED(mutation:<id>)`.
2. **`build` unit + freeze.** As in §3.2.
3. **`control` unit.** Runs `mvn -o test -pl <pl> -Dtest=<tests>` on the frozen image.
   - It must have rc 0 and testcases > 0, and every `expect` must be present.
   - If not, the job is `FAILED(control)` and no mutant runs.
4. **`mutant-<id>` units, in parallel.** Each runs from the frozen image:
   1. Apply the edit in the unit's CoW layer.
   2. Run `mvn -o -pl <pl> -am install -DskipTests`. The **owning module** of the mutated file
      (its nearest `pom.xml`) and everything between it and `<pl>` is recompiled. This also covers
      constants inlined into intermediate modules. A mutation in `core` with tests in
      `aether/node` is therefore really compiled into what the tests load.
   3. **Effect check:** at least one `.class` file under the owning module's `target/classes` must
      differ in sha256 from the frozen image's copy. If none differs, the outcome is
      **`NOT-EFFECTIVE`** and the tests don't run. That outcome is never SURVIVED.
   4. Run `mvn -o -pl <pl> test -Dtest=<tests>`.
5. **Per-mutant outcome:**
   - `RED{failedTests, expectHit: bool}`;
   - `SURVIVED`: the effect check passed and all tests are green;
   - `NOT-EFFECTIVE`: no `.class` changed;
   - `APPLY-INVALID`;
   - `COMPILE-ERROR`;
   - `TIMEOUT`;
   - `NO-XML`;
   - `NOT-RUN`.

   The job verdict is `PASSED` when the control was green and every mutant has an outcome.
   Mutant outcomes are data, not a pass/fail on the job.

---

## 4. Units and the worker host

### 4.1 Host layout

```
~/.temper/
  worker-<ver>.jar
  mirror.git                       bare mirror of origin (separate from bb-*'s ~/work/pragmatica.git)
  seed-m2/                         third-party seed (copied from ~/work/m2-seed at host registration)
  jobs/<jobId>/
    ws/                            checkout + .m2-local (unsharded units bind-mount this at the same path)
    units/<unitId>/
      .temper/ args  start  end  rc  marker  image  container
      reports/                     collected surefire/failsafe XML
      log                          full mvn log (stdout+stderr)
```

### 4.2 Container contract

```
docker run -d --name temper-<job>-<unit> \
  --label temper.job=<job> --label temper.unit=<unit> \
  --network temper-<job>-<unit>            (a per-unit bridge network, created and removed by the worker)
  --user <builder uid>:<builder gid> --group-add <docker gid> \
  -v /var/run/docker.sock:/var/run/docker.sock \
  [-v <ws>:<ws>]                           (unsharded units only; identical path inside and outside)
  -e TESTCONTAINERS_HOST_OVERRIDE=<bridge gateway> -e <allow-list> \
  --memory <unit.memory> --cpu-shares <weight> \
  temper/runner:<ver> mvn <argv>
```

- **Runner image:** see §4.6. The worker builds it on first use from a Dockerfile shipped inside
  the worker jar.
- **The socket mount adds no privilege:** test code already runs as `builder`, a docker-group
  member, on the bare host today.
- **Detached containers:** they survive ssh loss, worker exit and controller restart.

### 4.3 Unit timeouts

Every unit has a wall-clock bound; a submit can override it per recipe.

| Unit | Default |
|---|---|
| unsharded Gate | 3 h |
| `rest` | 2 h |
| `ember-k` | 90 min |
| `build` | 45 min |
| module-test / Mvn | 60 min |
| control, mutant | 30 min |
| rerun | derived at submit (below) |

The rerun unit's bound is derived at submit (maintainer ruling, 2026-10-20): `max(30 min, Σ class durations × 1.5 +
install allowance + 5 min)`, capped at 6 h and rounded up to the minute, over the classes the unit runs (a `@Nested`
target counts as its outermost class, once). A class's duration is its slowest stored run
over the last 5 terminal jobs that ran it, rerun jobs excluded (nested classes included); without history, its
weight in the tree's `tools/ember-shard-weights.txt`; without either, 10 min. The install allowance is
`[units] rerunInstallSeconds` (default 300). The record prints the derivation (`rerun-bound=48m (Σ 25.1m ×1.5 + install
5m + slack 5m)`); an explicit `--unit-timeout` overrides it and enters the dedup key, the derived bound does not.

On expiry, the worker:
1. captures a thread dump of every JVM in the container with
   `docker exec <c> jcmd <pid> Thread.print`, saving one file per pid into the evidence;
2. then runs `docker kill`;
3. records the unit as `FAILED(timeout)`.

The timeout runs in the worker host's own timer, which is restored from `.temper/start` on
re-attach, so an ssh drop doesn't reset it.

### 4.4 Run marker and completion

- **Before `docker run`:** the worker writes `.temper/marker` holding the epoch nanoseconds as
  text, plus `.temper/args`.
- **On container exit:** the worker writes `rc`, `end` and `container`, then collects reports.
- **Report filter:** only XML files whose mtime is newer than the `marker` epoch, read as text,
  count. This is not compared against the marker file's own mtime, which closes the copied-marker
  gap.

### 4.5 Contention facts (in evidence)

**At unit start and end,** the worker records `.temper/facts-{start,end}.json`:
- `load1`, `load5`;
- host reactors (§6.1);
- bb-* running and waiting tickets;
- `MemAvailable`;
- free disk;
- the number of temper units running on the host.

**At unit end,** it also records the container cgroup's `memory.peak` and `cpu.stat`.

**Rules:**
- Any timing claim (P1, shard weights) is valid only together with these facts.
- Weights are learned only from units whose start `load1` is ≤ the host's core count
  **[proposal]**.

### 4.6 Runner image

`temper/runner:<ver>` is built from `eclipse-temurin:25-noble`, **pinned by digest**, plus Maven
3.9.12, **verified by its published sha512** at build time. The runner image digest is recorded in
every unit's evidence, alongside the frozen image id when there is one.

---

## 5. Results

### 5.1 Testcase extraction

- XML is parsed with a namespace-unaware StAX reader. Each `<testcase>` element produces one row:
  `(class, name, outcome ∈ {passed, failed, error, skipped}, timeSeconds, unitId)`.
- The `tests=` attribute sum is stored as `testsAttr` for comparison only. It is never used for a
  verdict.

### 5.2 Manifest coverage

**Selection comes from each module's effective POM, not from a default rule.**
- The `build` unit (or the first unit of an unsharded job, in a prior `-o` pass) runs
  `mvn -o help:effective-pom -Dverbose=false -Doutput=…`.
- For each module, temper reads the surefire **and** failsafe configuration bound in the lifecycle
  the recipe reaches: `includes`, `excludes`, `groups`, `excludedGroups`, `skip`/`skipTests`/
  `skipITs` and the module's `-D…skipTests` properties (e.g. `ember.skipTests`). Command-line
  `-Dtest`/`-Dit.test` narrow the selection.

**Candidates are compiled test classes**, read with the JDK `java.lang.classfile` API (no
dependency). A class is a candidate if it is non-abstract, its top-level name matches the
include/exclude patterns, and it declares at least one method annotated with a JUnit 5 test
annotation (`@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate`) or a
JUnit 4 `@Test`.
- **Groups:** a class is excluded if a class-level `@Tag` is in `excludedGroups`, or if every test
  method's effective tags are excluded. A class with some excluded methods remains in the manifest.

**Nested classes:** the manifest unit is **every class that directly declares test methods**,
top-level or `@Nested`. A nested class is named as the report names it, `Outer$Inner`.
- It is selected if its outermost class matches the include patterns.
- An `Outer` that only contains `@Nested` classes is not itself a manifest entry. Its nested
  classes are.
- Both cases have fixture tests (`EmberConfigBasePortTest$Defaults`-style).

**Coverage:**
- `reported` = the set of `classname` attributes of `<testcase>` rows.
- `missing = manifest − reported` → `FAILED(missing-classes)`, which lists them.
- A class reported by more than one non-rerun unit of the same job → `ERROR(plan)`.
- `extra = reported − manifest` is recorded as `manifest-drift`.

**Rule disagreements are a defect in temper:** `missing` or `extra` on a run that was otherwise
green is a manifest-rule disagreement. **M1 exit criterion:** zero disagreements on every T1 SHA.

### 5.3 Verdict

The rules are evaluated in order, and the first match wins.

| Verdict | Condition |
|---|---|
| VOID(reason) | the host was compromised during a unit: disk floor, OOM-kill of a sibling, sockwatch kill, docker daemon restart |
| ERROR(reason) | lost or host-lost (§6.4), report parse failure, plan error, infrastructure failure before mvn ran |
| CANCELLED | cancelled by request or by supersede |
| FAILED(timeout) | any unit was killed by its timeout (§4.3) |
| EMPTY | **any unit** that requested tests **exited rc 0** and reported 0 testcases. This holds for every recipe, `Mvn` included: "requested tests" means the unit's lifecycle reaches `test` and tests aren't skipped. rc ≠ 0 with 0 testcases is FAILED(rc) (m0-design Q-8) |
| FAILED(reason) | any unit rc ≠ 0, any failed or error testcase, missing classes, control failure |
| PASSED | everything else |

**VOID doesn't hide reds.** A VOID or ERROR result still lists every failed or error testcase that
was observed, under `observedFailures`, and the one-line output carries `observed-failures=N`.

**Annotations, which never change the verdict:**
- `KNOWN(#n)` on failures that match the registry;
- `rerun PASSED|FAILED` (§9);
- `unpushed`;
- `superseded`;
- `evidence=missing`.

### 5.4 Result record (immutable, R3)

```json
{ "job": 4711, "verdict": "FAILED", "reason": "tests",
  "source": {"sha": "0cf563192a…", "ref": "release-1.0.0-rc4"}, "recipe": {"gate": {}},
  "annotations": ["KNOWN(#1679)"],
  "counts": {"testcases": 16234, "passed": 16190, "failed": 1, "error": 0, "skipped": 43,
             "testsAttr": 13755, "classes": 2101, "manifest": 2101, "missing": 0},
  "failed": [{"class": "…EmberAmnesiacRestartTest", "name": "…", "known": "#1679"}],
  "observedFailures": 1,
  "units": [{"id": "build", "rc": 0, "host": "bigboy", "image": "sha256:…", "start": "…", "end": "…"}],
  "evidence": {"stored": true, "path": "…", "bytes": 1234567},
  "timings": {"queued": "PT2M", "run": "PT36M"} }
```

Rows are written once; there is no UPDATE path for terminal results. A DB trigger enforces this.

**Durability [maintainer item 14]:**
- Every result record is also written as `result.json` into the job's evidence directory on the
  controller, so results survive a database loss.
- Postgres runs on a named docker volume (`temper-pg`).
- A nightly `pg_dump` writes into the evidence store, retained for 14 days.

---

## 6. Scheduling

### 6.1 Grant

- The scheduler loop runs every 2 s and on every event. For each host it grants the
  highest-priority QUEUED unit whose host constraints hold:
  - **cap:** host reactors < `host.cap` (default 5). **Host reactors** = the classworlds Launcher
    processes whose `/proc/<pid>/cgroup` is *not* inside a temper container cgroup (bb-*, bare
    `mvn`, foreign containers), **plus** temper's running units. Processes inside temper
    containers are visible in the host's `ps`, so counting them directly would count every temper
    unit twice;
  - **port group:** a unit that needs it holds a `bb-hold` grant (§6.2);
  - **memory:** sum of the memory of running units + `unit.memory` ≤ `host.memoryBudget`;
  - **disk:** above the floor.
- **Order:** priority descending, then submit time ascending.
- **Backfill:** a blocked head doesn't block units that fit. The 1200 s starvation bound from bb-*
  is ported: once a unit has been blocked for more than 1200 s, the host stops granting other
  units until it fits.
- **Lease:** a granted unit gets a lease row (`unit_id, host, lease_until`), refreshed by worker
  heartbeats every 10 s. The claim uses `SELECT … FOR UPDATE SKIP LOCKED`, so **at most one
  controller grant per unit**. That holds for a single controller; a second controller is not
  supported in the first cut.

### 6.2 Port group (phase A)

**Contract [maintainer delivers]:**

```
bb-hold <owner> <prio> --container <name> -- <argv>
```

It:
1. enqueues a normal `[port-group]` ticket;
2. blocks until the ticket is granted, then prints `granted <ticket>` to stderr;
3. runs `<argv>` (the worker's `docker run -d --name <name> …`);
4. runs `docker wait <name>`;
5. releases the ticket when the container exits, or on a signal;
6. exits with the container's rc.

**How the worker uses it:**
- The worker launches `bb-hold` **detached** (`setsid`), with its pid written to
  `.temper/bbhold.pid` and its stderr to `.temper/bbhold.err`. It therefore survives an ssh drop and
  the worker's own exit.
- The unit becomes RUNNING when `granted <ticket>` appears. The ticket id is stored in
  `.temper/bbhold.ticket`.
- **On re-attach,** the worker reads the pid, the ticket and the container state:
  - live container and live bb-hold → RUNNING;
  - exited container → collect (bb-hold releases by itself);
  - bb-hold dead while the container lives → the port group has been released under a running
    test. The unit is marked `VOID(port-group-lost)` and the container is killed.

**Ownership:** bb-* stays the only writer of `~/work/queue`. temper never touches `~/work/queue`,
`~/work/priorities` or the shared `.aether-suite-lock`.

**After the spike passes,** per host, the flag `host.portIsolated=true` turns port-group holding off
for temper units.

### 6.3 Priority

- **Phase A default: -20.** That is below the rewrite (-10) and rc4 (10).
- Trust-gate and spike runs use a priority the maintainer sets per window, passed via `--prio`.
- **Phase B:** temper owns priorities. It ports the `~/work/priorities` semantics (owner tag or
  SHA prefix of 7–40 hex → int, highest match wins, re-read on every grant) into a `priorities`
  table, which the maintainer edits with `temper prio set|list`.

### 6.4 Run, re-attach and loss

| Situation | Behaviour | Guarantee |
|---|---|---|
| ssh drops | the container keeps running; the controller reconnects with backoff (1 s → 60 s) and starts a fresh worker | the unit isn't re-run; its state is recovered from disk |
| reconnect | the worker reports `facts` (containers with temper labels, unit dirs with or without rc), and the controller reconciles | a unit with an rc → collect; one with a live container → RUNNING; one with neither → ERROR(lost) |
| controller restart | as reconnect, for every host | — |
| start not recorded | the worker reported the start (`UnitStarted`) but the start write is not recorded: the unit and its job show UNKNOWN once the write has been in flight 1 s, or at its first failed attempt | never QUEUED while it runs (maintainer, 2026-10-14); it completes from its exit facts |
| lease expiry without reconnect | the unit stays RUNNING(unknown) until the host is reachable or declared LOST; before that it is never re-granted elsewhere | **at most once, with visible loss**, for a unit whose host stays connected or returns within `hostLostAfter` (`[scheduler] hostLostSeconds`, default 10 min): ERROR(lost) is reported, never retried silently |
| host LOST (amended M4, ruling R-M4-3; m4-design §2) | the host was down for `hostLostAfter` or failed host-key verification. Each active unit is settled: CANCELLING → CANCELLED; a unit with external side effects → ERROR(host-lost); a unit already requeued once → ERROR(host-lost); otherwise the attempt ends ERROR(host-lost) and the unit is requeued as a new attempt. A returning host discards superseded attempts before it takes work | a unit on a LOST host runs **at most 1 + 1 times** and its **result is stored at most once** (the store accepts a result only for the unit's current attempt on its current host); a unit with external side effects runs at most once and ends ERROR(host-lost) instead. Every lost attempt is visible (`lostAttempts`, a `unit-lost` event) |
| cancel | the controller sends `cancel{unit}`; the worker runs `docker kill <container>` first. bb-hold's `docker wait` then returns and it releases the ticket by itself; the worker reaps it and records rc 137 | kill the build before the driver (incident rule) |
| timeout | §4.3 | thread dumps are captured before the kill |

---

## 7. Controller HTTP API

- **Base:** `http://bigboy:7070/api/v1` **[proposal]**, bound to the LAN interface only.
- **Auth:** a bearer token from `~/.config/temper/token` (0600) on clients. One token for the
  first cut, so no other LAN device can submit.

| Method | Path | Body / query | Returns |
|---|---|---|---|
| POST | `/blobs` | bundle bytes | `{blobId}` |
| POST | `/jobs` | §2.1 | `{job, state, deduplicated}`, or 422 `{refused: code, detail}` |
| GET | `/jobs/{id}` | — | job + units + current state |
| GET | `/jobs/{id}/result` | — | §5.4 (404 until terminal) |
| GET | `/jobs/{id}/testcases` | `?outcome=` | rows |
| POST | `/jobs/{id}/cancel` | — | `{state}` |
| GET | `/jobs/{id}/evidence` | — | tar.gz stream |
| GET | `/jobs/{id}/log` | `?unit=&tail=` | text |
| GET | `/diff` | `?a=&b=` or `?a=&baseSha=` | §8 diff |
| GET | `/gate/{sha}` | — | §8 gate answer |
| GET | `/jobs` | `?state=&submittedBy=&sha=&ref=&limit=&before=` | list, newest first; `before=<id>` is the next page |
| PUT | `/subscribers/{name}/selectors` | `{add: [...], remove: [...]}` | `{name, selectors, cursor}` (§8.3) |
| WS | `/subscribers/{name}/events` | — | the event stream (§8.3) |
| GET/POST/DELETE | `/known` | registry entries | — |
| GET/PUT | `/priorities` | phase B | — |
| GET | `/hosts` | — | host facts, cap and running units |

---

## 8. CLI and agent surface (R3–R6)

### 8.1 Output and exit codes (stable; a test pins them)

- **Default output:** one line per command, of the form `<verb> <job> <STATE|VERDICT> key=value …`.
- **`--json`:** the full object.

| Exit | Meaning |
|---|---|
| 0 | PASSED · accepted (submit) |
| 1 | FAILED |
| 2 | REFUSED |
| 3 | EMPTY (also the gate answer) |
| 4 | VOID |
| 5 | ERROR |
| 6 | CANCELLED |
| 7 | wait timeout |
| 8 | NONE (gate: no gate at that SHA) |
| 9 | RUNNING / QUEUED (gate, non-blocking) |
| 10 | deduplicated (submit; the job id is still printed) |
| 11 | CONFLICT (gate: the newest judge's Gates at that SHA disagree; m2-design §1.5, R-M2-9) |
| 13 | version skew: the controller's `Temper-Version` names another judged build than the CLI's, or the controller refused the CLI's write with 412 before acting (D-6); no override. Exception: `alarm-check` reports a skew as `check-blind` firing, exit 64 |
| 64 | usage error |
| 69 | controller unreachable |

### 8.2 Commands

```
temper submit gate|module-test|mutate|mvn  [--ref R | --sha S | --bundle F | --patch F --base S]
              [--pl …] [--test …] [--it-test …] [--spec F] [--prio N] [--keep] [--fresh] [-- mvn args]
temper wait <job> [--timeout 6h]
temper result <job> [--json]
temper diff <jobA> <jobB> | temper diff <job> --base <sha>
temper gate <sha> [--wait] [--timeout 6h] [--json]
temper watch --terminal --subscriber <name>
temper subscribe <job|sha|ref> --subscriber <name>   |   temper unsubscribe …
temper jobs (--sha S | --ref R) [--json]
temper cancel <job>
temper known add <testId> --issue N --reason "…" --until "<condition>" | list [--ledger] | expire <id>
temper refusal add|list|expire …
temper evidence <job> -o <dir>
temper log <job> [--unit U] [--tail N]
temper hosts | temper prio set|list (phase B)
```

- **`diff`** prints `added`, `removed`, `newly-failing` and `newly-passing` sections, each a list
  of `class#name`, followed by the counts line. With `--base`, the other side is the Gate that `gate <sha>` answers
  about (amended in M2: its answering Gate, or for RUNNING its `previous`; m2-design §3.1); for NONE or CONFLICT
  there is none and the exit code is 8.
  - Exit codes (m2-design §3.4, R-M2-9, R-M2-13): 0 nothing newly failing, no added failing test, and side B ran
    tests; 1 otherwise (an EMPTY B included); 8 no base, a conflicting base, a side pruned by retention, or a side not
    judged (VOID, ERROR, CANCELLED: `side not judged: <verdict>`); 9 a side not terminal.
  - A test is `(class, name)`, its rows folded (failing if any row fails). Skipped → failing is newly failing;
    passed → skipped, skipped → passed and failing → skipped are `skip-changed=N`, in no section and never in the exit
    code (R-M2-9 on Q-M2-8, R-M2-13).
- **`gate <sha>`** prints one line: `gate <sha> PASSED|FAILED|EMPTY|CONFLICT|NONE|RUNNING job=<id> judge=<j> testcases=N failed=M`.
  - The answer comes from the non-superseded Gates whose source was `Pushed` at exactly `<sha>`, and only from
    those of the **newest judge** (the temper build that judged them; m2-design §1.1, §1.7, R-M2-10). Older
    judges' verdicts are listed as `older=`; VOID, ERROR and CANCELLED never answer (`unjudged=`); a Gate judged
    across a deploy is listed as `judge-changed=` and left out (R-M2-11).
  - **Conflicts (amended in M2, R-M2-5):** if the newest judge's Gates disagree in verdict, the answer is
    `CONFLICT jobs=<id>:<VERDICT>,…` with exit 11, never the latest one. `<sha>` may be a 7-40 hex prefix
    (ambiguous: exit 64); `--wait` takes `--timeout` (default 6 h, exit 7).

### 8.3 Events and subscriptions

- **Event:** `{seq, at, job, kind ∈ {queued, started, unit-finished, finished, cancelled, superseded}, verdict?, line}`.
- **Subscriber:** `(name, selectors[], cursor)`. A selector is a job, a sha or a ref; an empty
  selector set means all jobs.
  - Selectors are typed: `job:<id>`, `sha:<40 hex>`, `ref:<name>` (m2-design §2.2).
  - `PUT /subscribers/{name}/selectors` with `{add: [...], remove: [...]}` creates the subscriber when absent and answers
    `{name, selectors, cursor}`: the resulting set, sorted (empty: all jobs), and the cursor.
  - A poll that delivers nothing advances the cursor over what it read, unless the selectors changed while it read
    (R-M2-12, skip-ack); the cursor never passes a matching, unacknowledged event.
- **`watch --terminal`:**
  - delivers only `finished` and `cancelled` events matching the selectors, one line each, e.g.
    `finished 4711 FAILED sha=0cf5631 gate testcases=16234 failed=1 KNOWN(#1679)`;
  - the CLI acks each line after printing it, and the controller advances the cursor on the ack;
  - on reconnect, delivery resumes at cursor + 1.
- **Guarantee:** each matching event is printed at least once. A crash between print and ack can
  repeat one line, and the line carries `seq` so a consumer can drop the duplicate. If retention
  passed the cursor, the controller emits `gap from=<seq> to=<seq>` instead of skipping silently.
- **Intended use:** a Claude Monitor runs `temper watch --terminal --subscriber cto` as its
  command, so each finished job wakes the main thread.

---

## 9. Flakes (R7)

- **Registry:** `known(id, testId (class#name or class), issue, reason, until, createdBy, expiredAt)`.
  - A failed testcase matching an active entry gets `KNOWN(#issue)`.
  - The verdict stays FAILED.
  - The `until` text is shown, and the maintainer expires the entry.
- **Isolated re-run (Gate only):**
  - **Trigger:** a Gate job FAILED because of test failures, and the failing classes not covered
    by `KNOWN` number ≤ 3 **[proposal]**.
  - **Action:** one rerun unit per failing class, from the frozen image (sharded) or a fresh
    checkout (unsharded).
  - **Annotation:** `rerun PASSED` or `rerun FAILED` per class. The verdict stays FAILED.
  - **Evidence first:** the evidence of the failing unit is collected and stored before the rerun
    starts.
- **Ledger:** a view over testcase rows: per testId, the count of runs, failures, reruns that
  passed and VOIDs over the last 30 days. Exposed via `temper known list --ledger` **[proposal]**.

---

## 10. Store (Postgres 16)

Tables:

```
jobs(id bigserial pk, submitted_by, submitted_at, priority, keep, source jsonb, source_sha,
     ref, recipe jsonb, dedup_key, state, superseded_by, fresh, unpushed bool)
units(id, job_id fk, name, host, state, args jsonb, needs_port_group, memory_mb,
      lease_until, started_at, ended_at, rc, image_id, container_id)
results(job_id pk fk, verdict, reason, annotations text[], counts jsonb, record jsonb, created_at)  -- write-once
testcases(job_id, unit_id, class, name, outcome, time_s, rerun bool)                               -- write-once
manifests(job_id, module, class)
events(seq bigserial pk, at, job_id, kind, payload jsonb)
subscribers(name pk, cursor bigint, selectors jsonb)
known(id, test_id, issue, reason, until_text, created_by, created_at, expired_at)
refusals(code pk, matcher jsonb, issue, reason, until_text, expired_at)
unit_facts(unit_id, phase {start,end}, facts jsonb)
hosts(name pk, address, host_key, cap, memory_budget_mb, port_isolated bool, state, facts jsonb)
priorities(pattern pk, value)                                                                         -- phase B
blobs(id, sha256, bytes, path, created_at)
```

- **Write-once enforcement:** triggers reject UPDATE and DELETE on `results` and `testcases`,
  except DELETE by the retention job.
- **Migrations** are versioned SQL files in `temper-store`, applied at controller start.
- **Durability:** see §5.4 (`result.json` in evidence, `temper-pg` volume, nightly `pg_dump`).

---

## 11. Acceptance criteria (gates per milestone)

| ID | Criterion |
|---|---|
| A1 | Every refusal in §2.5 has a test that submits the offending form and asserts the code. **Mutation probe:** deleting each check turns its test red |
| A2 | Verdict rules (§5.3), unit-tested on fixture report sets: EMPTY, missing classes, duplicated class, `tests=` undercount (`@Nested`), stale XML older than the marker, and a copied marker file with a newer mtime than its text epoch |
| A3 | Exit-code table (§8.1) pinned by CLI tests |
| A4 | Re-attach: kill the worker's ssh mid-unit, restart the controller; the unit completes and is collected, not re-run. A removed container plus no rc → ERROR(lost) |
| A5 | Events: kill `watch` between print and ack; on restart the line is repeated with the same seq, and nothing is skipped |
| A6 | **Mutate dependency probe:** a mutation in a dependency module (e.g. `core`) with tests in a dependent (`<pl>` ≠ the owning module) yields RED. The same mutation with recompilation of the owning module disabled (a test hook) yields `NOT-EFFECTIVE`, never SURVIVED |
| A7 | **Cap counting:** with one temper unit running and no bb-* reactors, host reactors == 1 |
| A8 | **Per-unit EMPTY:** a 2-unit job in which one unit exits rc 0 with 0 testcases is EMPTY, even though the other unit reported tests |
| A9 | **Dedup:** resubmitting after VOID, ERROR or CANCELLED runs a new job; after PASSED, FAILED or EMPTY it returns the old id |
| A10 | **Timeout:** a unit with a sleeping test and a short bound ends as `FAILED(timeout)`, with one thread dump per JVM in the evidence |
| A11 | **bb-hold re-attach:** drop ssh while a port-group unit runs; the ticket stays held until the container exits. Killing bb-hold alone gives `VOID(port-group-lost)` |
| A13 | **Seed isolation:** after workspace preparation, no file in the unit's `.m2-local` has a link count > 1; a test that seeds with `cp -al` fails the check |
| A12 | **Manifest fixtures:** module-specific includes/excludes/groups; `@Nested`-only outer; mixed tagged methods; failsafe `*IT` in a module with failsafe bound |
| T1 | **Trust gate (maintainer).** Gate on ≥3 SHAs already built by bb-*, including ≥1 red. testcase count, failed set and EMPTY detection equal bb-report's exactly. **Zero manifest disagreements** (§5.2). Per-module test wall time and `memory.peak` are recorded for sizing (§12.1). Recorded in the T1 verification record. Rules (maintainer, 2026-10-14): the planted class (the test the red SHA deliberately breaks) is stored with the run's evidence, and a re-run judges against it; a SHA whose failed sets disagree is FAIL; an agreed red from a class other than the planted one is explained and not counted; a fork crash on either side makes the SHA not comparable (T2's subject); ≥1 comparable SHA must be a planted red |
| T2 | **Crashed-fork case:** a deliberately crashed fork (e.g. `System.exit` in one test class on a scratch branch) → `FAILED(missing-classes)` naming that class |
| S1 | **Spike (§12 of the design).** Two concurrent fixed-port Forge runs in containers are green; the bare-host negative control collides; Testcontainers Postgres resolves the gateway. Run on bigboy and cloudbb-1 |
| E1 | **Sharded equivalence:** sharded vs unsharded Gate on the same SHA agree on the set of `(class, name, outcome)` and on the gate plugins executed |
| P1 | Sharded Gate wall time on bigboy is ≤ 40 min (maintainer target). It is measured on a quiet host, and its contention facts (§4.5) are reported with it |

---

## 12. Milestones

| M | Content | Exit gate |
|---|---|---|
| S1 ∥ M0/M1 | **Port-isolation spike, run in parallel** with M0/M1 [maintainer item 12]. It is docker plumbing only and needs no temper code. It runs in a maintainer window, with bigboy free of the #2173 Ember runs | S1 |
| M0 | Skeleton: modules; Postgres (named volume) + migrations; worker over ssh to localhost on bigboy; `Mvn` + `ModuleTest` unsharded; `Pushed` source; results, testcases, events; per-unit EMPTY; timeouts; contention facts; runner image; CLI `submit`/`wait`/`result`/`watch`/`cancel`/`log` | A1–A5, A7–A10, A13 |
| M1 | Gate unsharded via `bb-hold` (maintainer delivers on request); manifest from the effective POM; evidence store + `result.json` + `pg_dump`; retention GC; disk guard; refusals as data | A11, A12, **T1, T2** → the maintainer may rely on temper |
| M2 | `diff`, `gate` (with conflicts), `subscribe`; known registry; rerun; ledger; `Bundle` + `Patch` | CLI tests; one real `diff` cross-checked by hand |
| M5 | Sharded Gate + freeze, sized from T1 data (§12.1); parallel Mutate | S1 passed, E1, P1, A6; Mutate on a bb-mutate spec whose bb-mutate results are known gives the same RED/SURVIVED |
| M4 | Multi-host: cloudbb-1/2 (after the egress-IP pre-flight; until 2026-10-19); `bb-build` shim + priorities port (phase B) | per-host S1; the shim is accepted by the maintainer |
| — | Phase 2: `Ci` recipe, head pollers, GitHub statuses, status page, MCP | separate spec |

### 12.1 Sizing before P1 is promised [maintainer item 11]

- bigboy has 31 GB of RAM, and about 19 GB was used by bb-* work on 2026-10-12.
- **After T1, temper reports:**
  - per-module test wall time;
  - per-unit `memory.peak`;
  - the derived `gate.emberShards` and `unit.memory` that fit within the host memory budget while
    one bb-* reactor runs.
- **If `rest` alone exceeds 40 min,** the report says P1 needs `rest` sharded too, before M5
  starts.
- `gate.emberShards` has no default until that report exists.

## 13. Non-goals (first cut)

- Fork PRs.
- Arbitrary shell.
- Creating or deleting cloud servers (spend is the owner's).
- A public endpoint.
- More than one controller.
- Exactly-once execution (amended M4, ruling R-M4-3): a unit on a host that stays connected or returns within
  `hostLostAfter` runs at most once; a unit on a LOST host runs at most 1 + 1 times, its result is stored at most once,
  and a unit with external side effects runs at most once and ends ERROR(host-lost) instead (§6.4).
- Adjudicating flakes.

## 14. Open items

1. **Egress-IP pre-flight** before M4. The operator egress IP rotates; the cloudbb firewall admits only the operator's listed egress ranges (2026-10-13). Verify that bigboy's egress falls inside them.
2. **`bb-hold`**: the maintainer delivers it; temper requests it at the start of M1.
3. **S1 window:** temper asks the maintainer when the spike scripts are ready.
4. **rc4 binaries:** the maintainer installs the rc4 tip into `~/.m2` under the owner's marker. temper's
   `.m2-local` is seeded from it.
5. **Accepted proposals [maintainer item 16]:**
   - the R-DISK floor of 50 GiB;
   - rerun only at ≤ 3 failing classes;
   - port 7070;
   - a single token;
   - the temurin base.

   `gate.emberShards` comes from §12.1.

## 15. v0.2 change log (maintainer review of v0.1, 16 items)

| Item | Where |
|---|---|
| 1 Mutate on a dependency module; NOT-EFFECTIVE | §3.3, A6 |
| 2 Cap double-count | §6.1, A7 |
| 3 bb-hold `--container` + `docker wait`, detached, re-attach | §6.2, §6.4, A11 |
| 4 Unit timeouts + thread dumps | §4.3, §5.3, A10 |
| 5 Manifest from the effective POM; `@Nested`; M1 exit | §5.2, A12, T1 |
| 6 EMPTY per unit | §5.3, A8 |
| 7 Dedup excludes VOID/ERROR/CANCELLED | §2.3, A9 |
| 8 VOID keeps observed failures | §5.3, §5.4 |
| 9 Gate conflicts | §8.2 |
| 10 Contention facts | §4.5 |
| 11 Sizing before P1 | §12.1 |
| 12 S1 in parallel with M0/M1 | §12 |
| 13 Refusals as data | §2.5, §10 |
| 14 result.json, pg_dump, named volume | §5.4, §10 |
| 15 Maven checksum, runner image digest | §4.6 |
| 16 Proposals accepted | §14 |
