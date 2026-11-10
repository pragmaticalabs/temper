# temper — design draft (v1, for discussion)

Status: DRAFT rev 2, 2026-10-12. This is input for the spec, not the spec itself.

**Authority.**
- The owner rules on scope, stack, repo and spend.
- The maintainer session is temper's primary user and **sets requirements**. The owner said so on
  2026-10-12: "this is a tool for you, feel free to adjust it for your needs".

**Markers.**
- **[owner]** is an owner ruling.
- **[maintainer]** is a maintainer requirement.
- **[proposal]** is temper's design choice, open to challenge.
- Guesses are marked as guesses.

Rev 1 folds in maintainer requirements R1–R7 and changes C1–C3. It drops the yield rule (old D13), and
it removes GitHub statuses and the status page from the first cut.

## 1. Why

The bottleneck is agents carrying build results around, not compute (owner, 2026-10-12). The
inventory measured it:

- CI alerts take 17–26 minutes to reach the maintainer.
- Sessions have repeatedly stalled for 3 hours or more with everyone waiting.
- About 35 of roughly 65 maintainer turns were "no action" status checks.
- Agents launch builds by ssh with hand-written driver scripts (hundreds are on bigboy), then poll
  `.bb/rc`, then relay the result.
- Authors and verifiers reconcile test counts by hand ("the author claims 70 new tests").

GitHub Actions has no required checks, doesn't run on `rewrite/*`, and on the Free plan jobs wait
in GitHub's queue for 42 minutes at p90.

A root gate on a cloud host took 94 minutes in a smoke run. Ember's fixed observation windows
dominate it, with single test cases of 230 s and 600 s.

## 2. Scope of the first cut

**The core [owner]:** one point for submitting jobs; temper runs them and reports results.

**The first cut [maintainer]:**
- **Job types:** submit Gate, ModuleTest, Mutate and Mvn jobs from Pushed, Bundle or Patch sources.
- **Hosts:** a list of hosts (bigboy, plus cloudbb-1 and cloudbb-2 while they live).
- **Sharded Gate on one host (R1).** This depends on the isolation spike passing; until then the
  Gate runs unsharded.
- **Parallel Mutate (R2).**
- **Results:** immutable, citable results with temper's own counts (R3); per-testcase storage and
  diffs (R4); one query that answers the merge question (R5).
- **Output agents can consume:** typed exit codes, `--json`, a terminal-events watch and
  subscriptions (R6).
- **Flakes:** a known-failure registry, one isolated re-run, and a flake ledger (R7).
- **The bb-build shim (C1),** which collapses bb-* into temper's queue.

**Out of the first cut [maintainer]:**
- GitHub commit statuses;
- the status page (the CLI is the interface; the owner can port-forward later);
- head pollers;
- merge trains;
- MCP.

**Before the maintainer relies on temper: the trust gate (§13).**

## 3. Decisions

| # | Decision | Source | What drives it |
|---|---|---|---|
| D1 | Java/JBCT end to end, host side included; bb-* semantics are ported, not reused as scripts | owner | one stack, typed and testable; the scripts drifted |
| D2 | Postgres is the store | owner | durable leases (`SKIP LOCKED`), queryable per-testcase history |
| D3 | Controller–worker link: the controller starts the worker over SSH and speaks a protocol over stdin/stdout | owner idea, accepted with conditions (§7) | no new ports, works through NAT and the firewall, reuses ssh keys, the controller deploys the worker |
| D4 | Job = named recipe or raw `mvn <args>`; no arbitrary shell | maintainer | shell is how a token or a spend reaches a command |
| D5 | Refusals are a property of the runner, not of a recipe | maintainer | no form of job can skip them |
| D6 | First-cut submitters: agents and the owner via CLI and HTTP. Later: a head poller (`release-*`, `rewrite/*`), same-repo PRs, MCP | maintainer | the result recorded against its SHA removes most relaying already |
| D7 | Bundle and Patch results are tagged `unpushed` and never count as PR gate evidence | maintainer | evidence requires the SHA to be on origin per `ls-remote` |
| D8 | **Hosts are a list from day one:** bigboy plus cloudbb-1 and cloudbb-2 (alive until 2026-10-19, as dev and real-load workers). Spend stays with the owner via the existing watchdog; temper never creates or deletes servers | maintainer (C3) | the ssh-stdio worker makes another host almost free |
| D9 | One container per job unit. Dropping the port group needs the isolation spike, including a bare-host negative control that actually collides | maintainer + research | per-container loopback removes fixed-port collisions, if proven |
| D10 | Not on Aether for now; build slice-shaped so it can move later | research | slices have no process API, no streamed responses and a 15 s timeout; UDP full mesh fails across NAT; circularity |
| D11 | Repo: `pragmaticalabs/temper`, private | owner | — |
| D12 | Controller runs on bigboy, with Postgres in docker there | owner | one host; reachable from the Mac over the LAN |
| D13 | ~~Yield to bb-*~~ **Replaced:** one queue. Until the shim lands, temper holds bb-*'s port group like any bb-* ticket at a priority the maintainer sets. Then `bb-build` becomes a shim that submits to temper, and the semantics of `~/work/priorities` are ported (owner tag or SHA prefix → int) | maintainer (C1) | the yield rule starved temper: a gate waited 1 h 10 min behind priority-10 tickets on 2026-10-12 |
| D14 | Pragmatica 1.0.0-rc4 from the shared local `~/.m2`. Refresh path is option (b): the owner creates the `~/.m2/ALLOW_AGENT_WRITE` marker (it expires after 60 min) and the maintainer session installs the rc4 tip. temper's own tree builds against its `.m2-local` seeded from `~/.m2`. On 2026-10-12 the shared rc4 jars date from 2026-10-07, while the rc4 tip `0cf563192a` is from 2026-10-12 | owner | track the current line; the shared repo is the owner's and is written only under the marker |

## 4. Architecture

```
 agents / owner                    bigboy                                              each host (bigboy, cloudbb-1/2)
 ┌─────────────┐  HTTP/JSON  ┌──────────────────────────┐  ssh -T, stdio protocol  ┌────────────────────────┐
 │ temper CLI  │────────────▶│ controller (JVM)         │─────────────────────────▶│ worker (JVM)           │
 │ submit wait │◀─WebSocket──│ api · scheduler · store  │◀─────────────────────────│ exec · fs · docker     │
 │ watch result│   events    │ planner · reconciler     │                          └───────────┬────────────┘
 │ diff gate   │             └───────────┬──────────────┘                                      │ docker run
 └─────────────┘                         │                                       ┌─────────────▼────────────┐
 bb-build shim ──(C1)──▶ same API  ┌─────▼─────┐                                 │ unit containers          │
                                   │ Postgres  │                                 │ build · shard-k · mutant │
                                   └───────────┘                                 └──────────────────────────┘
```

**Job and unit.** A *job* is what was submitted. A *unit* is one container execution that belongs
to it. An unsharded Gate is one unit. A sharded Gate is one build unit followed by N shard units. A
Mutate job is one build unit, one control unit and M mutant units. The scheduler grants units; the
cap and the port group apply per unit.

bigboy reaches its own worker over ssh to localhost, which is the same code path as for a remote
host.

## 5. Job model

```
Job {
  id, submittedBy (owner tag, like BB_OWNER), submittedAt, priority, keep: bool
  source:
    Pushed{ref, sha}       sha resolved via ls-remote at submit
    Bundle{sha, blob}      a whole unpushed branch     → tag unpushed
    Patch{baseSha, diff}   mutations, verifier probes  → tag unpushed   (C2)
  recipe:
    Gate                                   §5.2 (root clean install)
    Ci                                     phase 2, GitHub Actions' composition (§5.2)
    ModuleTest{pl, tests?, itTests?}
    Mutate{spec}                           bb-mutate spec format (§5.3)
    Mvn{args}                              escape hatch; same refusals
  dedupKey: hash(source identity, recipe, params, effective maven.config)
}
```

- **The SHA is fixed once at submit.** A job never follows a moving ref. A later push creates a new
  job, and older jobs for the same ref are superseded (§11.1).
- **Dedup.** A second submit of an identical job returns the existing job id, whether it is running
  or finished. This is today's rc 7/rc 8 behaviour, applied automatically.
- **Patch.** The diff is applied with `git apply --index` on a fresh checkout of `baseSha`. A
  refused or partial apply makes the job `REFUSED(patch)`, never a run against an unpatched tree.
  The result records `baseSha` plus a hash of the diff.

### 5.1 Runner-level refusals (D5)

Every unit passes these whatever its job form:

- **#1553:** `StreamPublishReshuffleTest` must be excluded or `@Disabled`.
- **forge-tests:** a run with `-Dtest` but no `-Dit.test` filter is refused.
- **Environment allow-list:** the container gets a fixed allow-list of environment variables, so
  `HCLOUD_TOKEN`, `HETZNER_TOKEN` and any other `*_TOKEN` can never reach it. The host's
  environment is not inherited.
- **No `~/.m2`:** it is not mounted. Each job's local repo lives inside its workspace, seeded from
  the third-party seed.
- **No shell:** argv arrays only.

### 5.2 Gate (R1)

**Definition [maintainer]:** the root `clean install`. That means the default reactor, with no `-pl` and no
`-DskipTests`. It is what the maintainer gates rewrite-branch merges on, and what the trust gate compares
against bb-report. forge-tests sit outside the default reactor, so they are not part of the Gate.

**Unsharded mode** (until the spike passes) is one unit running `clean install`. It holds the port
group through `bb-hold` (§9).

**Sharded mode** (after the spike) shards **Ember only** [maintainer]. **[proposal]** for how:
1. **Build unit.**
   - The workspace (checkout plus `.m2-local`) is copied *into* the container filesystem, not
     bind-mounted, because `docker commit` doesn't capture bind mounts.
   - It runs `clean install -DskipTests`. Test classes are compiled; only their execution is
     skipped.
   - It emits the **expected test manifest**: every test class the reactor will run, per module, by
     surefire's include rule (`ember-shard.py`'s `TEST_NAME`, generalised), minus `*IT` and the
     excluded groups.
2. **Freeze [maintainer conditions].**
   - `docker commit` produces a per-job image.
   - `.m2-local` is part of the frozen layer, never a bind mount of a shared repo.
   - The image is labelled with the job id and its id is recorded in the evidence.
   - It is garbage-collected 24 h after the job ends, unless the job is pinned.
   - Shards start from the image, so each gets a copy-on-write copy of the built tree: no
     hardlinks, and no shard can write into another shard's `target/`.
   - No rc4 test bind-mounts files into Testcontainers (`git grep` for
     `withFileSystemBind|withCopyFileToContainer|MountableFile|withClasspathResourceMapping` finds
     nothing), so a tree that lives inside the image is safe.
3. **Units from the frozen image.**
   - **`rest` unit:** `install -o -Dember.skipTests=true`. This runs the full lifecycle so that
     every lifecycle-bound gate still executes: `dead-surface-gate`, `cli-docs-gate`,
     `generated-project-gate`, `test-plan-gate`. Running only `surefire:test` goals would silently
     drop those gates from the Gate's scope. Compilation is up to date, so it's effectively a
     no-op.
   - **`ember-k` units, k = 1..N:** `test -o -pl aether/ember -Dtest=<shard k classes>`, the same
     invocation shape as GitHub Actions' ember shards.
4. **Plan.** `ember-shard.py`'s algorithm (longest first, deterministic ties), with **weights from
   temper's own per-class durations** (median of the last k passing runs). A class with no history
   gets the largest known weight. The seed weights are `tools/ember-shard-weights.txt`.
5. **Merge.** The union of all unit reports must cover the manifest **exactly**, with every class
   reported by exactly one unit.
   - A missing class is `FAILED(missing-classes)`.
   - A duplicated class is `ERROR(plan)`.
   - A crashed shard or a planner that drops a class therefore can't produce a green gate.
6. **Equivalence check before sharded mode becomes the default [proposal].** On the same SHA, run
   both sharded and unsharded. They must agree on testcase identity and outcome, and the gate
   plugins must have run. Record the comparison next to the trust gate's.

**Target [maintainer]:** about 30–40 minutes on one host, against 94 minutes unsharded. The `rest` unit
and the longest Ember class (`EmberNodeReplacementTest`, about 624 s) set the floor.

**Later, a separate recipe `Ci` [maintainer, phase 2].** This is GitHub Actions' composition:
- `jbct:check` per gated module, quoting file counts;
- `install -Dember.skipTests`;
- postgres-async;
- `examples verify`;
- forge-tests `-Pwith-e2e`, sharded with `-Dit.test` filters;
- the Ember shards;
- stub-suites.

It is not needed for the trust gate.

### 5.3 Mutate (R2)

The spec is bb-mutate's (`build{pl,tests,extra}`, `mutations[{id,file,kind,old,new,expect,zone}]`).

1. **Validate:** each mutation must match exactly once and must not be a no-op. This is checked
   before anything runs; otherwise the result is `REFUSED`.
2. **Build:** a build unit, then a freeze, as for the Gate.
3. **Control:** one control unit runs the named tests on the unmutated image. It must be green with
   more than 0 testcases, and every `expect` test must appear in it. **If the control fails, the job
   is `FAILED(control)` and no mutant runs.**
4. **Mutants:** M mutant units run in parallel from the frozen image. Each applies its one mutation
   in its copy-on-write layer, recompiles only the mutated module offline, then runs the named
   tests.
5. **Outcome per mutant:**
   - RED: listing which tests failed, and whether they were the `expect` tests;
   - SURVIVED;
   - APPLY-INVALID;
   - COMPILE-ERROR;
   - TIMEOUT;
   - NO-XML;
   - NOT-RUN.

   A RED counts only because the control was green, and the result says so explicitly.
6. Batch packing (several mutants per unit) is left out of the first cut, because parallel units
   replace it. Single runs are unambiguous by construction.

## 6. Lifecycle, results and guarantees per operation

**Job states:** `QUEUED → RUNNING → {PASSED, FAILED, EMPTY, VOID, ERROR, CANCELLED}`, plus
`REFUSED` at submit and `SUPERSEDED → CANCELLED`. Units have their own states with the same names.

| Operation | What the caller is guaranteed | Mechanism | When things fail |
|---|---|---|---|
| submit | When it returns, the job is durably queued with its source identity pinned, or refused with a reason | Postgres commit before the reply; `ls-remote` / patch-apply check inside the call | If `ls-remote` fails, the submit fails; no job is created |
| grant | A unit is held by at most one worker slot at a time | `SELECT … FOR UPDATE SKIP LOCKED`, a lease with an expiry, plus the per-host cap and port group | If the lease expires (the controller lost the worker), see "run" |
| run | A unit runs at most once per lease. On controller restart or ssh loss, the container keeps running and the controller re-attaches through state on disk | Detached container named `temper-<job>-<unit>`; state in the unit's `.temper/` | A re-attach that finds neither the container nor an rc marks the unit `ERROR(lost)`. It is **not** re-run automatically. That is at-most-once with visible loss, not exactly-once |
| result (R3) | A terminal result is immutable, keyed by job id, and carries temper's own counts | write-once rows; counts are computed by temper from XML newer than the unit marker; agents cite the job id | If copying the evidence fails, the result is still recorded, flagged `evidence=missing` |
| testcases (R4) | Every finished unit's testcases (class, name, outcome, time) are stored with the job | rows per testcase, parsed with an XML parser, not regex; `<testcase>` is authoritative and `tests=` is recorded only for comparison | If parsing fails, the unit is `ERROR(report)`, never a pass |
| gate (R5) | `temper gate <sha>` returns the verdict of the **latest non-superseded Gate at exactly that SHA**, or NONE | query by sha and recipe; Bundle/Patch sources are excluded (D7) | — |
| events / subscriptions (R6) | Each subscriber has a durable cursor. A watcher that restarts resumes after its last acknowledged event, and none is skipped or repeated as new | a monotonic events sequence; a `subscriber(name, selector, cursor)` row; the cursor advances on client ack | Past retention, the subscriber gets an explicit `gap` line, not a silent hole |
| cancel | It's requested durably; it takes effect when the worker kills the container | the container is killed before its driver (incident rule) | If the worker is unreachable, the job stays `CANCELLING` until re-attach |

**Verdicts follow the inventory's rules on counting:**
- **PASSED** needs rc 0, a testcase count above 0 when tests were requested, and manifest coverage
  (§5.2). The marker epoch is stored as text, which closes the copied-marker gap.
- **EMPTY:** tests were requested and 0 ran. It counts as a fail.
- **FAILED(missing-classes):** the manifest isn't covered. This also applies to unsharded runs,
  where it closes the crashed-fork gap.
- **VOID:** the host was compromised during the run (disk full, sockwatch kill, OOM-kill of an
  unrelated unit). It is never adjudicated as a defect.

## 7. Worker protocol over ssh stdio (D3)

The controller starts the worker with `ssh -T -o BatchMode=yes -o ServerAliveInterval=15 host`.
That host's `authorized_keys` entry is
`command="exec java -jar ~/.temper/worker-<ver>.jar",restrict`.

**Condition 1: ssh controls the work; units don't depend on it.**
- Units are detached containers.
- The worker is stateless apart from what's on disk. On reconnect, a fresh worker reports the
  facts it finds on disk, and the controller reconciles them.

**Condition 2: stdout carries only protocol messages.**
- Frames are length-prefixed JSON; logs go to stderr.
- There is no login shell.
- On connect, the worker sends a `hello{version, host facts}` frame.

**Condition 3: no bulk data on the control channel.**
- Logs and evidence move over a second ssh session (`ControlMaster`-shared).

**Deploy.** The controller pushes a worker jar matching its own version, so versions can't drift.

**Messages:**
- **Controller → worker:** `prepare{job}` (fetch, checkout, apply patch, seed), `start{unit}`,
  `freeze{job}`, `cancel{unit}`, `facts{}`, `collect{unit}`, `gc{policy}`.
- **Worker → controller:** `started`, `exited{rc}`, `report{testcases, manifestCoverage}`,
  `facts{containers, load, reactors, bbQueue}`, `error`.

**Host keys** are pinned when a host is registered and removed when it is deregistered (the Hetzner
IP-reuse incident).

**Pre-flight check (C3).** The controller on bigboy must be able to reach cloudbb-1 and cloudbb-2
over ssh. The cloud firewall admits the operator IP only, and bigboy's egress IP has **not yet been
verified** to be that IP.

## 8. Client surface (R6)

The CLI is a thin client over HTTP. On every command:
- output is **one stable line**;
- `--json` gives the full form;
- **exit codes are typed**: 0 passed, 1 failed, 2 refused, 3 empty, 4 void, 5 error, 6 cancelled,
  7 timeout, 8 none, 9 running. The exact table goes in the spec.

**Commands:**
- `temper submit <recipe> [--ref R | --bundle F | --patch F --base SHA] [--prio N] [--keep] [-- mvn args]`
  prints the job id. Its exit code says queued, deduplicated or refused.
- `temper wait <id> [--timeout T]` blocks until the job ends and exits with the verdict's code.
- `temper result <id> [--json]` prints the immutable result with temper's counts (R3).
- `temper diff <jobA> <jobB>` or `temper diff <job> --base <sha>` lists tests added, removed, newly
  failing and newly passing (R4). With `--base`, it compares against the latest non-superseded Gate
  at that SHA.
- `temper gate <sha>` prints `PASSED|FAILED|NONE|RUNNING`, the counts and the job id (R5).
- `temper watch --terminal --subscriber <name>` emits one line per **finished** job and nothing
  else. A Claude Monitor runs it as its command, and the cursor makes restarts lossless.
- `temper subscribe <id|sha|ref> --subscriber <name>` adds a selector to that subscriber's watch,
  so the maintainer can follow work without polling.
- `temper known add|list|expire …` manages the known-failure registry (R7).
- `temper evidence <id> -o dir` downloads the evidence.

## 9. Coexistence with bb-* (C1)

**Phase A: shared hosts, two queues.**
- temper's units count against the host cap the way bb-queue counts it. Container processes are
  visible in the host's process list, so bb-queue's `pgrep` of the Maven launcher also counts
  temper's units.
- A temper unit that needs the port group holds it **as a bb-* ticket** through `bb-hold` [maintainer]. bb-*
  stays the only writer of its own queue.
  - **Usage:** `bb-hold <owner> <prio> -- <cmd>`, where `<cmd>` is the worker's `docker run` of the
    unit.
  - **Contract:**
    - enqueue a normal `[port-group]` ticket;
    - block until it is granted;
    - exec `<cmd>` while holding;
    - release on exit, signals included;
    - exit with `<cmd>`'s rc;
    - write `granted <ticket>` to stderr.
  - **Delivery:** the maintainer delivers it when temper's first job needs it, and temper asks for it then.
- **Priority in phase A: -20**, below everything, including the rewrite (-10) and rc4 (10). Trust-gate
  shadow runs and the spike run at a priority the maintainer sets per window; temper asks before each.

**Phase B: one queue.**
- Once Gate and ModuleTest work, `bb-build` becomes a shim that submits to temper.
- `~/work/priorities` semantics move into temper (owner tag or SHA prefix → int, re-read on every
  grant, highest match wins).
- Backfill and the 1200 s starvation bound are ported.
- bb-queue's own scheduler retires.

## 10. Flakes (R7)

- **Known-failure registry:** entries of (test id, issue #, reason, expiry condition). A red result
  that matches an entry is annotated `KNOWN(#n)` and **stays red**: the verdict is unchanged, and
  only the annotation is added.
- **One isolated re-run, Gate only:** on a test-level failure that isn't in the registry, the Gate
  may re-run **that class alone** once, in a fresh unit. It reports `FAILED, rerun PASSED` or
  `FAILED, rerun FAILED`, and **never** turns the result into PASSED. The failing log is captured
  before the re-run starts (incident rule: a re-run destroys the failing log).
- **Flake ledger:** per test, it records every outcome, including re-run outcomes and VOIDs.
  temper reports rates and never adjudicates.

## 11. Settled (maintainer, 2026-10-12)

1. **Superseded jobs.**
   - A queued job is cancelled automatically when a newer SHA lands on its ref.
   - A running job is marked `superseded`, then cancelled, unless it is pinned with `keep`.
   - Integration-branch gates are never pinned by default.
2. **Retention.**
   - **Evidence** (start/end/rc, testcases, gzipped XML, log tail): 90 days.
   - **Checkouts:** 24 h after the job ends, unless pinned.
   - **Full tarballs:** 14 days.
   - **Frozen job images:** the same as checkouts, 24 h after the job ends unless pinned [maintainer].
   - Nothing referenced by an open PR's latest status is ever deleted.
3. **Status page:** out of the first cut. Later, LAN only and read-only.
4. **Limits:**
   - a hard memory limit per unit;
   - `cpu.weight` only, with no CPU quota; revisit after the spike.
5. **Disk guard [maintainer]:** a unit is refused below a free-space floor. That is reported as
   `VOID(disk)`, never as FAILED.

## 12. Spike: port isolation (blocks sharding)

The maintainer approves it under the suite lock, in a window the maintainer sets; not while #2173 runs.
- **Run:** two concurrent containers running the same fixed-port Forge class, plus a
  Testcontainers Postgres unit with `TESTCONTAINERS_HOST_OVERRIDE=<bridge gateway>`.
- **Negative control:** the same two Forge runs on the bare host must collide (BindException).
  Without that, the result proves nothing.
- **Also check:** whether "Testcontainers fails above about 3 reactors" is a load problem rather
  than a port problem.
- **Repeat** on cloudbb-1.

## 13. Trust gate (before the maintainer relies on temper) [maintainer]

- **Shadow comparison:** run Gate on at least 3 SHAs that bb-* has also built, including at least
  one red. temper's testcase counts, failures and EMPTY detection must **match bb-report
  exactly**.
- **Crashed-fork check:** one deliberate crashed-fork case must come out `FAILED(missing-classes)`.
- **Recording:** the comparison is committed to this repo.

## 14. Open items

- **Pre-flight before using cloud workers:** verify that bigboy's egress IP passes the cloud
  firewall's operator-IP rule. It probably shares the Mac's NAT, but that hasn't been checked [maintainer].
- **`bb-hold` delivery:** request it from the maintainer when the first job needs it.
- **Batch 1 answers** (Gate = root `clean install`, Ember-only sharding, `bb-hold`, priority -20,
  `docker commit` with conditions) are folded into §5.2, §9 and §11.

## 15. Low-probability and tangential ideas (kept for later)

- **Git notes as a mirror of results** (`refs/notes/temper`): the verdict travels with the repo.
- **Shadow mode against GitHub Actions** before primary-gate status: verdicts diffed over N days.
- **Dogfood mirror on Aether:** non-gating, with decisions diffed against the real controller.
- **Budget envelope for elastic hosts:** owner-authorized $X per week, drawn down through the
  watchdog ledger.
- **Test-impact selection** for *advisory* jobs only. Gates always run the full reactor.
- **Using the manifest as a sharding oracle:** since the manifest is computed anyway, a class added
  without a weight is visible at plan time. The plan can flag it instead of silently giving it the
  largest weight.
