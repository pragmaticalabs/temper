# temper — M1 implementation design

**Status.** Design for milestone M1, input for parallel `jbct-coder` agents. The contract is `docs/spec.md` v0.2;
M0's structure, rulings and rc4 facts are in `docs/m0-design.md` (cited as m0 §n). This document says what M1 adds
and changes, file by file, against the code on `main` as read on 2026-10-13. It does not restate the spec.

**Markers** (same as m0): **[V]** verified by reading code or docs in this tree; **[U]** unverified, the coder or a
host probe must confirm it first; **[P]** a temper proposal; **[G]** a guess forced by an underdetermined spec.
**Q-M1-n** are questions for the maintainer (§11); the design proceeds on the marked guess until ruled.

**M1 scope** (spec §12): the unsharded Gate via bb-hold; the manifest from the effective POM; the evidence store,
`result.json` and the nightly `pg_dump`; retention GC; the disk guard; refusals as data. **Exit gate: A11, A12, T1,
T2.** Also in scope: the §12.1 sizing data from the T1 runs.

---

## 0. Findings from the M0 code that drive this design

| # | Finding (file) | Consequence for M1 |
|---|---|---|
| F1 | `worker/unit/UnitStarter` replies `UnitStarted` only after `docker run` returns; the controller's `StartUnit` timeout is 20 min (`host/UnitLauncher`, m0 §6.4) [V] | A bb-hold wait can last hours, so the start must split: a `UnitHolding` reply, then an unsolicited `UnitStarted` once the hold is granted (§1.3) |
| F2 | `policy/Reconciler`: marker + no container + no rc → `MarkLost` [V] | A unit waiting in bb-hold has its marker (written before the hold) and no container. Without a new rule it is declared `ERROR(lost)` on every reconnect (§1.5) |
| F3 | `policy/GrantPolicy.fits`: `!needsPortGroup \|\| portIsolated` [V] | Port-group units can never be granted today. That line must change, or the Gate never leaves QUEUED |
| F4 | `refusal/Refusals.checkSubmit` lists `portGroup(units)` → `REFUSED(port-group)` [V] | Removed in M1 |
| F5 | `mvn/MvnStep.mayInclude`: a whole-reactor step "may include" every module [V] | **R-1553 refuses every Gate** whose tree has `StreamPublishReshuffleTest` enabled, though that class lives in `aether/forge/forge-tests`, which is outside the default reactor (design §5.2; `.bb-1553.py` reads it there). M1 resolves the real reactor (§1.6) |
| F6 | `Refusals.itTestOffence` comment: "whole-reactor, -am or -amd … revisit when M1 lifts that refusal" [V] | R-FAILSAFE must judge whole-reactor, `-am` and `-amd` steps once port-group units run (§1.6, Q-M1-5) |
| F7 | `store/pg/PgEventStore.minSeq` = `coalesce(min(seq), 0)`; `events/EventFeed.gap` fires only when `minSeq > 0` [V] | Once GC empties the table, a lagging subscriber is never told about the gap. That is the WP3 verifier's finding, and it is a silent hole. New subscribers get `cursor = max(seq)` = 0 on an empty table, which is a second instance of the same bug (§3.4) |
| F8 | `store/pg/Finishing.input` passes `manifest = none()`; `Verdicts` already orders `missing-classes` before `tests` and `rc` [V] | The verdict logic for coverage exists. M1 supplies the manifest |
| F9 | `ResultRecords` writes `EvidenceDto.NOT_STORED` [V] | Replaced by real evidence rows (§3) |
| F10 | `exec/ProcessRunner.run` returns stdout as `byte[]` [V] | `pg_dump` and evidence tarballs need a stream-to-file variant (§3.2) |
| F11 | `ops/worker-launch` allows only `run` and `install` [V] | Evidence over a second ssh session needs a third, read-only verb (§3.2, Q-M1-2) |
| F12 | No `temper-acceptance/` directory on `main`; the root pom has only the `acceptance` profile stub naming it [V]. There is no record of the M0 host-tier runs (A4, A7-live, A10-live) [V] | M1 creates the module. Where the M0 host-tier evidence lives is Q-M1-15 |
| F13 | `facts/FactsProbe` collects temper container ids with `label=temper.unit` (m0 §7.7) | M1's auxiliary containers (the effective-POM probe, §2.3) must be inside "temper" for the A7 cap count, or they double-count. The label filter changes to `temper.job` |

### 0.1 Item 5: what M0 already delivers

| Item | Status on `main` | M1 work |
|---|---|---|
| R-DISK at unit start → `VOID(disk)`, no `docker run` | **Done** (`UnitStarter.afterDiskCheck`, `Refusals.disk`, A1 probe) [V] | none |
| Disk fit at grant (`freeBytes ≥ floor`) | **Done** (`GrantPolicy`, `HostSnapshot`) [V] | none |
| Disk floor breached *during* a unit → `VOID(disk)` (spec §5.3) | **Missing** | run-floor monitor (§4.4, Q-M1-10) |
| `refusals` table, seed rows R-1553 and R-FAILSAFE, `RefusalStore.active()`, used in submit | **Done** [V] | none |
| `temper refusal add\|list\|expire` (spec §2.5, §8.2) and the matching API | **Missing** | §6 |
| R-1553 and R-FAILSAFE on whole-reactor steps | **Wrong for the Gate** (F5, F6) | §1.6 |
| A11 | **Not done** (no bb-hold path) | §1 |
| A12 | **Not done** (no manifest) | §2 |

---

## 1. Gate, unsharded, through bb-hold

### 1.1 Recipe and plan (domain; WP1)

```java
// model/Recipe.java
record Gate() implements Recipe { kind() = "gate"; canonical() = {"gate":{}} }   // replaces Unsupported("gate")
static Recipe gate();
// Unsupported remains for "mutate" only.
```

`Planner.plan(Recipe, RecipeOptions, PlanDefaults, Option<ReactorModules>)`. The new parameter is the reactor
the submit read from the tree (§1.6).

| Recipe | Unit | Steps | requestsTests | needsPortGroup | memory | manifest |
|---|---|---|---|---|---|---|
| Gate | `gate` (`UnitKind.GATE_UNSHARDED`, 3 h) | `[["mvn","-B","clean","install", FORCED_REPO_LOCAL]]` | true | `PortGroupRule.needs(steps, modules, reactor)` → true while `aether/ember` is in the reactor | `defaults.gateMemory()` (Q-M1-13) | `some(ManifestSpec)` (§2.2) |
| ModuleTest | unchanged | unchanged | unchanged | reactor-aware | unchanged | `some(ManifestSpec)` from step 2 |
| Mvn | unchanged | unchanged | unchanged | reactor-aware | unchanged | `none()` in M1 (Q-M1-8) |

- `UnitSpec` gains `Option<ManifestSpec> manifest`.
- `PlanDefaults` gains `MemoryMb gateMemory`.
- The unit is named `gate` [G]: it keeps `units.name` meaningful when M3 adds `build`/`rest`/`ember-k`.

### 1.2 The worker seam (worker; WP4)

M0 left `UnitLauncher` as the seam (`worker/unit/UnitLauncher.java` javadoc). M1 keeps its *role* but has to change
its *shape*: a bb-hold launch does not yield a container id when it returns (F1).

```java
// worker/unit/UnitLauncher.java  (changed)
sealed interface Launch {                       // what a launcher produced
  record Running(String containerId) implements Launch {}            // direct: docker run -d returned
  record Holding(HolderRecord holder) implements Launch {}           // bb-hold spawned, ticket enqueued, not granted
}
@FunctionalInterface interface UnitLauncher { Promise<Launch> launch(UnitLaunch launch); }
static UnitLauncher direct(DockerCli docker);                        // M0 behaviour → Running
static UnitLauncher routed(UnitLauncher direct, PortGroupLauncher hold) // StartRequest.portGroup present → hold, else direct

// worker/unit/PortGroupLauncher.java  (new)
interface PortGroupLauncher { Promise<Launch.Holding> enqueue(UnitLaunch launch, HoldRequest hold); }
record HoldRequest(String owner, int priority) {}                    // from StartUnit.portGroup
record HolderRecord(long pid, long startTicks) {}                    // identity: /proc/<pid>/stat field 22
```

**`BbHoldLauncher.enqueue`** (Zone C; argv only, no shell, `inheritEnv=false` with `HOME` and `PATH` passed
explicitly; whether bb-hold needs anything else is **[U]**, WP4's first task in §1.8):

1. argv = `setsid <bbHoldPath> <owner> <prio> --container temper-<job>-<unit> -- docker <DockerRunArgs.build(launch)…>`.
   - `setsid` without `-f`, so it execs and `Process.pid()` is bb-hold's own pid.
   - stdin is `/dev/null`; stdout goes to `.temper/bbhold.out` (the `docker run -d` output); stderr goes to
     `.temper/bbhold.err`. Each is opened by `ProcessBuilder.redirectOutput/Error(File)`.
   - The new session means an ssh hangup never reaches it, and the JVM exiting never kills it.
2. Write `.temper/bbhold.pid` = `<pid> <startTicks>` atomically. The identity check (§1.4) needs both.
3. Return `Holding` immediately.

**Marker order.** The marker is written **before** step 1, as in M0, so it still precedes the container's `docker
run`. The report filter is unaffected. Q-11's requeue safety now rests on "no marker → bb-hold was never spawned".

**`GrantWatcher`** (new, `worker/unit/GrantWatcher.java`): one per holding unit. It polls every 1 s **[P]** for
three things: `bbhold.err` (for `granted <ticket>`), holder liveness, and the cancelled set.

| Observation | Action |
|---|---|
| `granted <t>` appears | write `.temper/bbhold.ticket`. Resolve the container by name (`docker inspect`, retried ≤ 30 s). Write `container`, `start` (= now: **the timeout counts from the grant**, not from the enqueue), `image`, `network`. Arm `UnitTimer`, `UnitWatcher` and `HoldMonitor`. Post the unsolicited `UnitStarted` |
| bb-hold exits before a grant, rc 2 | post `UnitStartFailed{ERROR, "port-group: bb-hold refused (rc 2)"}` |
| rc 3 (duplicate owner+name) | retry the enqueue once after 10 s **[P]**: bb reap clears a dead predecessor in about 4 s (m0 §12.3). A second rc 3 → ERROR |
| rc 143 and the unit is in the cancelled set | post `UnitStartFailed{CANCELLED}` |
| rc 143 otherwise | post `ERROR("port-group: bb-hold signalled")` |
| granted, but no container appears within 30 s and bb-hold has exited | `ERROR("port-group: docker run failed")`. The rc and `bbhold.err` tail go into the detail |

The rc of a detached process is not observable once the worker has restarted. A new worker therefore classifies
"exited before grant" from `bbhold.err` (bb-hold prints its refusal reason there [U]) and, failing that, as
`ERROR("port-group: bb-hold exited before grant")`.

**`StartUnit` reply.** Direct units reply `UnitStarted` as in M0. Hold units reply `UnitHolding{job, unit,
markerEpochNs, facts}` after step 2. `UnitStarter` changes only at its `launched → recorded → armed` tail: a
`Holding` skips `recorded` and `armed` and starts the `GrantWatcher` instead.

### 1.3 Protocol and controller handling (WP1 protocol; WP5 controller)

| Message (protocol **v2**, `Handshake.PROTOCOL_VERSION = 2`) | Change |
|---|---|
| `StartUnit` | + `portGroup: Option<PortGroupHoldDto{owner, priority}>`, + `manifest: Option<ManifestSpecDto>` |
| `UnitHolding` (new, W→C reply) | `job, unit, markerEpochNs, facts` |
| `UnitStarted`, `UnitStartFailed` | may now also arrive **unsolicited** (after a hold) |
| `UnitExited` | + `voidReason: Option<String>` (`port-group-lost`, `disk`) |
| `DiskUnitDto` | + `holder: Option<HolderStatusDto>` (`WAITING`, `HELD`, `DEAD`); `none` = not a hold unit |
| `Heartbeat.running` | now also lists holding units, so their leases are refreshed |
| `UnitManifest` (new, W→C) | `job, unit, chunk, last, outcome` (§2.5) |
| `GcJobs` / `GcDone` (new) | §4.3 |

Controller:
- `host/UnitLauncher.recorded` accepts `UnitHolding`: `UnitStore.holding(ref, HoldingRow{marker, facts})`. The unit
  stays `GRANTED` and its `port_group` column becomes `waiting`. No new `UnitState` is added **[P]**, which keeps
  the M0 state CHECKs, views and probes intact.
- `host/WorkerEvents` routes unsolicited `UnitStarted` → `UnitStore.started` (now also sets `port_group='held'` and
  `bbhold_ticket`), and unsolicited `UnitStartFailed` → `complete`, the same as in `UnitLauncher`.
- **Grant policy** (`policy/GrantPolicy`, `HostSnapshot`): a port-group unit fits when
  `portIsolated || portGroupUnits(host) < maxHolding` **[P]** (`maxHolding = 1`, Q-M1-12).
  - `portGroupUnits` counts this host's active units with `needs_port_group`.
  - A holding unit counts against `cap` and `memoryBudget` like any GRANTED unit **[P]**. That reserves the memory
    it will need on grant, at the price of parking one slot while bb-* holds the group.

### 1.4 Liveness while the container runs: `VOID(port-group-lost)`

**Domain** (`domain/hold`, WP1; pure):

```java
enum HolderStatus { WAITING, HELD, DEAD }
enum ContainerState { RUNNING, EXITED, ABSENT }
enum HoldAction { NONE, VOID_PORT_GROUP_LOST }
sealed interface HoldRules {
  // two holder observations >= 2 s apart, each read BEFORE the container state of that round
  static HoldAction decide(HolderStatus earlier, HolderStatus later, ContainerState container);
  // VOID only when both observations are DEAD and the container is RUNNING
}
sealed interface GrantLine { static Option<String> ticket(String stderrText); }   // ^granted (\S+)$
```

**Why the read order and the second observation:** bb-hold exits *because* the container exited. Reading the
container first and the holder second can see "RUNNING, then DEAD" across that exit and void a clean unit.
Holder-then-container cannot. The second observation absorbs a pid-file read racing a write.

**Holder identity:** `/proc/<pid>` exists, its `stat` starttime equals the recorded `startTicks`, and its
`cmdline` contains `bb-hold` and the container name. A recycled pid fails the starttime check. Reading
`~/work/bb-hold/<owner>-<name>/` is allowed (Q-10) and is used only to cross-check the pid; temper never writes
there.

**`HoldMonitor`** (worker, new) runs on the heartbeat tick (10 s) for every held unit. On `VOID_PORT_GROUP_LOST`:
1. `storeOnce(.temper/void, "port-group-lost")`;
2. `docker kill <c>`.

The normal exit path then sends `UnitExited.voidReason`, which gives `VOID(port-group-lost)`. The observed reds are
still listed (spec §5.3). The Testcontainers reap of a voided unit waits `voidedReapWait` (5 s), not the 60 s of m0
§12.4, so the verdict stays inside A11's 30 s; what is left then is still reported in its end facts, with
`tcLeftovers.unverified` saying the window was cut short (present when it ended, not proven stuck). Only this
reason gets the short window: a `disk` void (§4.5) leaves the unit running, and its reap waits the full 60 s. A container
without a Testcontainers session id is never a unit's leftover (A11 live, 2026-10-14). On `Reattach`, the worker
re-arms the monitor, so a holder that died while no worker was attached is caught on the first tick after re-attach.
That is the spec's re-attach rule (§6.2) carried by the same code path.

### 1.5 Reconcile with holds (controller `policy/Reconciler`, WP5)

`DiskUnit` gains `Option<HolderStatus> holder`. The rules, first match wins (the new rules are marked **+**):

1. rc present, or container EXITED → **Collect**. bb-hold releases by itself.
2. container RUNNING → **Reattach** (+ **Cancel** if CANCELLING). The worker's `HoldMonitor` handles a dead holder.
3. **+** container ABSENT, holder `WAITING` or `HELD` → **Reattach** (+ **Cancel** if CANCELLING). The worker
   re-arms the `GrantWatcher`.
4. **+** container ABSENT, holder `DEAD`, no `granted` line in `bbhold.err` → the container never started:
   **Requeue** (GRANTED) or **CompleteCancelled** (CANCELLING). Before the scan reports this, the worker inspects
   the container a second time after 10 s, which covers a `docker run` child that outlived bb-hold by a moment.
5. **+** container ABSENT, holder `DEAD`, granted → **MarkLost** (`ERROR(lost)`, a visible loss).
6. Otherwise the M0 rules apply (marker without container → MarkLost; no marker → Requeue / CompleteCancelled).

### 1.6 The reactor at submit (R-1553, R-FAILSAFE, port group)

```java
// domain/mvn/ReactorModules.java (new, WP1; pure)
record ReactorModules(Set<String> paths) {
  // root pom <modules>, recursively through aggregator poms, plus the modules of every <profile> that has an
  // <activation> element (conservative: such a profile may be active without -P)
  static Result<ReactorModules> parse(Function<String, Option<byte[]>> pomAt);   // path -> bytes of <path>/pom.xml
}
// MvnStep.mayInclude(String module, Option<ReactorModules> reactor):
//   -pl/-am/-amd -> as M0; no -pl, no -P, no -f -> reactor.contains(module) when known, else true (M0 behaviour)
```

- `submit/TreeReader.facts(sha, classNames)` also reads `pom.xml` files with `git show <sha>:<path>/pom.xml`,
  following `<module>` entries. `TreeFacts` gains `Option<ReactorModules> reactor`. An unreadable or unparseable
  pom gives `none()`, which is the conservative M0 behaviour.
- **SubmitJob order changes:** resolve → gather (`RefusalStore.active`, then `TreeReader.facts`, which takes the rules' class names, so the two run in sequence) → **plan** (it needs
  the reactor for `PortGroupRule`) → refuse → dedup → store.
- `Refusals.checkSubmit` drops `portGroup(...)`. `RefusalCode.PORT_GROUP` stays, because historic REFUSED rows
  carry it.
- **R-FAILSAFE** extends to steps whose reactor `mayInclude` the module (whole reactor, `-am`, `-amd`) and that
  carry `-Dtest` without `-Dit.test` (Q-M1-5). A Gate has no `-Dtest`, so it is unaffected.

### 1.7 Cancel during a hold (worker `UnitCanceller`, WP4)

The incident rule is kept: kill the build before its driver.

1. Write `killed=cancel` with `storeOnce`.
2. If the container exists, `docker kill` it. bb-hold's `docker wait` returns and it releases the ticket by itself.
3. Otherwise, signal bb-hold with `ProcessHandle.of(pid).destroy()` (SIGTERM; identity checked first). Wait up to
   10 s for it to exit.
4. **Re-check the container by name** and `docker kill` it if it appeared: the grant can race the signal.

Whether SIGTERM while *waiting* removes the queued ticket is **[U]** (Q-M1-14).

### 1.8 WP4's first task (before any hold code)

Read `~/tools/bb-hold` on bigboy (reading is allowed, Q-10) and record its answers in the bigboy host record:
- the environment it needs;
- what it prints on stdout;
- the exact stderr lines for grant and refusal;
- its state-file format;
- whether SIGTERM while waiting dequeues the ticket;
- whether `docker run -d` failing after a grant releases the ticket.

Any answer that contradicts §1.2–§1.7 goes back to the main session before coding.

---

## 2. Manifest from the effective POM (spec §5.2)

### 2.1 Where it runs, and why

| Part | Where | Cost |
|---|---|---|
| `help:effective-pom` | an **auxiliary container** `temper-<job>-<unit>-pom`, started by the worker in `StartUnit` **before** the marker and before bb-hold | about 20–60 s per unit [U] |
| class-file scan + rules | the **worker JVM, on the host**, during exit processing (`UnitCollector`, fork-joined with report collection). Unsharded units bind-mount the workspace at the same path, so `target/test-classes` is readable there | seconds: one read per test class file |

**Rejected alternatives:**
- **A step inside the unit container after `clean install`.** RunSteps stops at the first failing step, so a red
  build, which is exactly T2's case, would get no manifest. It would also need a scanner shipped in the runner
  image, and it would hold the port group longer.
- **A separate `test-compile` pass before the build.** It costs a second full compilation of the reactor (a large
  slice of the ~94 min Gate), and test-jar dependencies fail before `package`. The Gate definition is `clean
  install` (maintainer).
- **Effective POM *after* the build.** This would not hold the port group either, but it would run after a red
  build, where `-am` resolution can differ. Running it first also lets a broken pom fail fast, outside the hold.

**Deviation from spec §5.2 [P]:** the spec says "`mvn -o help:effective-pom` … in a prior `-o` pass". M1 runs it
**online**, in its own container. The seed `.m2-local` may lack `maven-help-plugin` [U], and offline buys nothing
for a goal that resolves no reactor artifacts (Q-M1-9).

**Cost of a crashed reactor:** modules the reactor never reached have no `test-classes`. They cannot be in the
manifest. They are reported as `notReached` in the result rather than silently dropped. The run is already red
(rc ≠ 0) whenever this happens.

### 2.2 Planner output (domain `model/UnitSpec`, `domain/manifest`)

```java
enum Runner { SUREFIRE, FAILSAFE }
enum Reach  { TEST, INTEGRATION_TEST }           // the furthest test-bearing phase the main step reaches
record ManifestSpec(List<String> pomArgv,        // mvn -B org.apache.maven.plugins:maven-help-plugin:<pinned>:effective-pom
                                                 //   -Doutput=${ws}/.home/.temper/effective-pom-<unit>.xml
                                                 //   + the main step's -pl/-am/-amd/-P/-D/-f args, + FORCED_REPO_LOCAL
                    Reach reach,
                    Map<String,String> userProperties,   // the main step's -D's (MvnStep.properties)
                    Option<String> test, Option<String> itTest) {}
```

- **The pom argv reuses the main step's reactor-selection args.** The effective POM therefore contains exactly the
  reactor's projects. `-pl X -am` needs no reactor arithmetic in temper.
- `${ws}` is substituted by the worker in **two** planner constants: `FORCED_REPO_LOCAL` and the new
  `EFFECTIVE_POM_OUTPUT`. These are constant tokens, never request data. R-SHELL and R-M2 run over `pomArgv` too:
  it goes through `Refusals.checkSubmit` as an extra step of the unit.
- The help-plugin version is pinned **[U]** (WP2 confirms a 3.x that supports `-Doutput` for a reactor).

### 2.3 Auxiliary container (worker `manifest/PomProbe`, WP4)

The container runs `docker run --rm --name temper-<job>-<unit>-pom --label temper.job=<job> --label temper.aux=pom`
with the same user, env allow-list, workspace mount, network and image as the unit. It has **no** `temper.unit`
label, so the M0 scan and reconcile never mistake it for a unit.

- **Cap counting:** `FactsProbe` collects temper container ids by `label=temper.job` (F13). The probe's Maven
  launcher is therefore not counted as a foreign reactor. Its unit is already counted as GRANTED, so this is not a
  double count.
- **Failure:** a non-zero rc gives `UnitStartFailed{ERROR, "manifest: effective-pom rc=<n>"}`. The build does not
  run (Q-M1-7).
- **Retries:** a retried start first runs `docker rm -f` on the probe name.
- **Output:** the XML is moved into `units/<u>/manifest/effective-pom.xml`.

### 2.4 Engine (domain `org.pragmatica.temper.domain.manifest`; WP2; pure, JDK + core only)

```java
// parsing
record PomModule(String id, String path, String packaging, Path testOutputDirectory, Path outputDirectory,
                 Path testSourceDirectory, Map<String,String> properties, List<PluginUse> testPlugins) {}
record PluginUse(Runner runner, List<Execution> executions) {}
record Execution(String id, Option<String> phase, List<String> goals, Map<String, ConfigValue> config) {}
sealed interface ConfigValue { record Text(String value) ; record Items(List<String> values) }
sealed interface EffectivePom { static Result<List<PomModule>> parse(byte[] xml, Path workspace); }
  // StAX, namespace-unaware, DTD/XXE off (as ReportParser); <projects> wrapper or a single <project>;
  // plugin config merged with execution config (execution wins); paths must lie under `workspace`
  // (else ManifestError.OutsideWorkspace)

// selection
record TestParams(List<String> includes, List<String> excludes, Set<String> groups, Set<String> excludedGroups,
                  boolean skipped, Option<String> test) {}
record ModuleSelection(PomModule module, Runner runner, TestParams params) {}
sealed interface Selections { static Result<List<ModuleSelection>> resolve(List<PomModule> modules, ManifestSpec spec); }

// classes
record MethodInfo(String name, Set<String> annotations, Set<String> tags) {}
record ClassInfo(String binaryName, Option<String> superName, List<String> interfaces, boolean isAbstract,
                 boolean isInterface, Option<String> outerName, boolean nested /* @Nested */,
                 Set<String> annotations, Set<String> tags, List<MethodInfo> methods) {}
sealed interface ClassInfos { static Result<ClassInfo> read(byte[] classFile); }   // java.lang.classfile, ClassFile.of().parse
record ClassIndex(Map<String, ClassInfo> byName) {}                                  // test-classes + classes of the reactor

// the rules
record ManifestEntry(String module, String className, Runner runner) {}
sealed interface ManifestOutcome {
  record Computed(Set<ManifestEntry> entries, List<String> notReached, Set<String> unresolvedAnnotations) implements ManifestOutcome {}
  record Unsupported(String reason) implements ManifestOutcome {}                  // → ERROR(manifest); unit reason "manifest: <reason>", result reasonDetail "<reason>"
}
sealed interface ManifestRules {
  static ManifestOutcome compute(List<ModuleSelection> selections, Map<String, List<ClassInfo>> testClassesByModule,
                                 ClassIndex index, Set<String> modulesWithTestSourcesButNoClasses);
}
sealed interface ManifestError extends Cause { MalformedPom; OutsideWorkspace; MalformedClass; UnsupportedParameter; UnsupportedTagExpression }
```

**Rules**

1. **Executions reached.** For each module whose packaging is not `pom`, take every surefire or failsafe execution
   whose phase falls in `[test]` (reach TEST), or in `[test, integration-test]` (reach INTEGRATION_TEST). The phase
   is the execution's `<phase>`, or the goal's default (`surefire:test` → test; `failsafe:integration-test` →
   integration-test). `<phase>none</phase>` is unbound. Executions are unioned.

2. **Parameters.** An explicit (already interpolated) configuration value wins. Otherwise the user property from
   `ManifestSpec.userProperties` applies, then the module's effective `<properties>`, then the plugin default:
   - skip: `skip`, `skipTests`, `skipExec`, `maven.test.skip`; failsafe also `skipITs`;
   - `test` (surefire) / `it.test` (failsafe);
   - `includes` / `excludes`, with the default includes (surefire `**/Test*`, `**/*Test`, `**/*Tests`,
     `**/*TestCase`; failsafe `**/IT*`, `**/*IT`, `**/*ITCase`);
   - `groups` / `excludedGroups`.

   `-Dtest` / `-Dit.test` **replace** includes *and* excludes (surefire semantics). The pattern alphabet (simple
   name, path glob, `!`, `#method`, `%regex[…]`) is implemented once in `SurefirePatterns`. The M0
   `refusal/SelectionMatcher` is reused or folded in; WP2 decides, with WP1's agreement, since WP1 owns that file.

3. **Unsupported → `Unsupported`, never a guess:**
   - `includesFile`, `excludesFile`, `dependenciesToScan`;
   - a tag *expression* (`&`, `|`, `!`, parentheses) in `groups`/`excludedGroups`;
   - a JUnit 4 category (a class name) in `groups`.

4. **Candidates.**
   - The class is concrete and not an interface.
   - It **effectively declares** a test method. The method is annotated with `@Test` (Jupiter or JUnit 4),
     `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory` or `@TestTemplate`, directly or through a
     meta-annotation resolvable in `ClassIndex`. It may be declared in the class itself, or inherited from a
     superclass or an interface default method in the index (**[P]**, deviates from the spec's "declares", Q-M1-6).
     JUnit 5 runs inherited tests under the subclass's name, so without this a concrete subclass of an abstract test
     base is reported but not expected: drift on every green run.
   - Annotation types that cannot be resolved (they come from a jar) are recorded in `unresolvedAnnotations`.
     T1 shows whether they matter.

5. **Naming.**
   - A top-level candidate is matched by its own path.
   - A `@Nested` candidate is named `Outer$Inner` and matched by its **outermost** class (spec).
   - An outer class whose only tests are in `@Nested` children is not an entry.
   - A static nested class that is not `@Nested` falls under surefire's default exclude `**/*$*`.

6. **Groups.**
   - Effective tags = the class's tags (`@Tag`/`@Tags`, inherited from superclasses because `@Tag` is
     `@Inherited`, and from the enclosing classes of a `@Nested` class, plus meta-annotations) ∪ the method's tags.
   - The class is **excluded** if a class-level tag is in `excludedGroups`, or if every test method's effective tags
     hit `excludedGroups`.
   - With `groups` set, the class is **included** only if some test method's effective tags meet `groups`.

7. **Class-level `@Disabled`: [U].** Whether surefire 3.5.4 + JUnit Platform writes a skipped `<testcase>` for a
   disabled class decides whether such a class is an entry. The A12 oracle (§9.2) settles it before T1. The same
   question applies to class-level `@Enabled*/@Disabled*` conditions, which cannot be evaluated statically: a
   disagreement there is a T1 finding, not a silent rule.

8. **notReached:** a module with a non-empty `testSourceDirectory` and no `testOutputDirectory`.

### 2.5 Delivery, storage and verdict

- **Worker** `manifest/ManifestCollector.collect(ref, spec) → Promise<ManifestOutcome>`. It reads the effective POM
  and walks each module's `testOutputDirectory` and `outputDirectory`, without following symlinks and staying under
  ws. It builds `ClassInfo`s and calls `ManifestRules`. It writes `units/<u>/manifest/manifest.json` (evidence) and
  sends `UnitManifest` chunks (≤ 2000 entries per chunk) between `UnitExited` and the `UnitReport` chunks.
- **Re-sending:** an exit already processed (`exited.json` present) re-sends from `manifest.json`, as the M0
  report path does.
- **Controller:** `WorkerEvents` buffers the manifest chunks with the report chunks. `UnitStore.complete` gains
  `Option<UnitManifestRows>`.
- **Store:** `manifests` rows (+ `unit_id`, `runner`), plus `manifest_units(unit_id, status, detail, not_reached,
  unresolved)`. An `Unsupported` outcome sets the unit's `errorReason = "manifest: <reason>"`.
- **`Finishing.input` passes `some(Manifest)`** when every unit with a `ManifestSpec` delivered `Computed`.
  `Verdicts` is unchanged except for the annotation `manifest-drift=<n>` when `drift ≠ ∅` (spec §5.2 "recorded as
  manifest-drift").
- **Result record (§5.4), additive:**

  ```json
  "manifest": {"computed": true, "missing": ["…"], "drift": ["…"], "notReached": ["…"], "unresolvedAnnotations": 0}
  ```

  The lists are capped at 500 entries each, with counts in `counts`. The event line adds `missing=<n>` on
  `FAILED(missing-classes)`.
- **Order (existing [V]):** `VOID > ERROR > CANCELLED > FAILED(timeout) > EMPTY > FAILED(missing-classes) >
  FAILED(tests) > FAILED(rc)`. A crashed fork exits rc ≠ 0, so it is not EMPTY, and it reports
  `FAILED(missing-classes)` naming the class. That is T2.

---

## 3. Evidence store, `result.json`, `pg_dump` (controller; WP5, with WP3 and WP4)

### 3.1 Layout on the controller host [P]

```
<evidence.root>/                         default ~/.local/share/temper/evidence  (NOT under ~/.temper: same user as the worker)
  jobs/<job>/result.json                 the §5.4 record, written after the result commits
  jobs/<job>/units/<unit>/evidence.tar.gz  "evidence": .temper/*, reports/*.xml, threaddumps/, manifest/, log.tail (last 1 MiB)
  jobs/<job>/units/<unit>/full.tar.gz      "full tarball": the whole unit directory, including the full log
  pgdump/temper-<yyyymmddThhmmssZ>.dump    pg_dump -Fc
```

### 3.2 Pull over a second ssh session (design §7, Condition 3)

**`ops/worker-launch`** (WP4) gains one verb, `evidence <job> <unit> small|full`.
- **Validation:** `<job>` must match `^[1-9][0-9]{0,18}$` and `<unit>` must match `^[a-z0-9][a-z0-9-]{0,31}$`. The
  directory `~/.temper/jobs/<job>/units/<unit>` must be a real directory: no symlink at any component, and its
  `realpath` must lie under `~/.temper/jobs`.
- **Output:** `tar -czf - <list>` on stdout, where `<list>` is the fixed set for `small` or `.` for `full`. Missing
  members are skipped. Nothing is written.
- **Exit codes:** 64 for a refusal, 66 for a missing directory.
- This widens what the key can do: it can now **read** unit directories. That needs a maintainer ruling (Q-M1-2).

**Worker:** `UnitCollector` also writes `log.tail` (the last 1 MiB of `log`), so the small set needs no `tail` in
bash.

**Controller:**
- `SshCommand.evidence(host, ref, kind)`.
- `EvidencePuller.pull(HostName, UnitRef, EvidenceKind) → Promise<StoredEvidence(path, bytes, sha256)>` (Zone C):
  1. spawn ssh with stdout redirected to `<file>.partial` (F10: `exec/Command` gains `Option<Path> stdoutFile`;
     `JdkProcessRunner` uses `redirectOutput(File)`);
  2. byte caps `maxSmallMiB` / `maxFullMiB`, enforced by a size watch that kills the process;
  3. rc 0 and the gzip magic bytes required;
  4. fsync, then an atomic rename.
- Timeouts: 10 min. One retry after 30 s.

**When a unit ends** (`WorkerEvents`, after the last `UnitReport` chunk):
1. **pull `small`;**
2. `UnitStore.complete(…)`, which inserts the `evidence` row in the same transaction;
3. `UnitReportAck`;
4. **pull `full`** in the background, recorded with `EvidenceStore.recorded`.

- Only units that have a marker are pulled. A unit refused at start has nothing on disk.
- **Lost and hold-failed units are not pulled** (main-session ruling, 2026-10-13). A unit ended by `Failure{lost}`,
  reconcile `MarkLost`, or an unsolicited `UnitStartFailed` after its hold failed still has its marker, but the
  controller pulls only after a `UnitExited` report. Its job therefore records `{"stored": false}` with
  `evidence=missing`; the verdict is unchanged, and the unit's files stay on the host until retention collects it.
- **The result record is immutable,** so `evidence` is decided when the job finishes. It is
  `{"stored": true, "path": "<root>/jobs/<job>", "bytes": <Σ small>}` if every unit with a marker has a small
  tarball. Otherwise it is `{"stored": false}` plus the annotation `evidence=missing` (design §6, R3). A later
  successful pull is kept as a file and a row, but does not change the record.
- **Disk guard on the controller [P]:** if the evidence filesystem has less than `evidence.minFreeGiB` free, the
  `full` pull is skipped and logged. The `small` pull is still attempted.

### 3.3 `result.json`

- `evidence/ResultFiles.write(JobId, String recordJson) → Result<Unit>` writes a tmp file, forces it to disk, renames
  it atomically and forces the directory (the same as `UnitFiles.storeAtomic`). It runs right after `complete`
  returns a finished job.
- **Sweep:** at controller start and every retention tick, `ResultFiles.sweep()` writes the file for every result
  whose job finished in the last 90 days and has no file. That closes the window between the commit and the write,
  including a controller crash inside it.
- **Guarantee:** every result row has a byte-identical `result.json` (the `record` jsonb as stored) within one sweep
  interval of its commit, provided the evidence filesystem is writable. A failed write is logged and retried by
  the next sweep.
- `result.json` is **not** evidence, and retention never deletes it **[G]** (Q-M1-11).

### 3.4 Nightly `pg_dump`

- `maintenance/PgDump.dump() → Promise<DumpOutcome>` runs `docker exec temper-pg pg_dump -U temper -d temper -Fc`
  with stdout to `pgdump/…dump.partial`.
  - Container-local auth is `trust` (bigboy.md [V]), so no password travels.
  - The dump is verified with `docker exec -i temper-pg pg_restore --list` on that file: rc 0 and a non-empty TOC.
    The file is then renamed.
- Schedule: daily at `pgDump.at` (default `03:30` local **[G]**), plus at start when the newest good dump is older
  than 24 h.
- Each run writes a `maintenance_runs` row `(kind 'pg_dump', started_at, ended_at, ok, detail, path, bytes)`.
- **Guarantee:** one dump per day the controller was up at or after the scheduled time. Each dump is a single
  consistent snapshot (pg_dump's own transaction). Verification means the archive's TOC is readable; that is not a
  full restore. The DB's RPO is ≤ 24 h. Results additionally survive in `result.json` with an RPO of one sweep
  interval.

---

## 4. Retention GC and the disk guard

### 4.1 Policy (domain `domain/retention`, WP1; pure)

```java
record RetentionPolicy(Duration evidence /*90d*/, Duration full /*14d*/, Duration checkout /*24h*/,
                       Duration events /*30d [G]*/, Duration pgDumps /*14d*/) {}
record EvidenceItem(long id, UnitName unit, EvidenceKind kind, Instant storedAt) {}
record JobRetention(JobId job, boolean keep, boolean terminal, Option<Instant> finishedAt, List<EvidenceItem> items,
                    List<String> hostsWithCheckout, boolean hasTestcases) {}
record DumpFile(String name, Instant takenAt) {}
sealed interface RetentionAction {
  record DeleteEvidence(long evidenceId, Instant storedAt) ; record DeleteTestcases(JobId job) ; record DeleteCheckout(String host, JobId job) ;
  record DeleteEventsUpTo(long seq) ; record DeletePgDump(String name)
}
sealed interface RetentionPlan {
  static List<RetentionAction> plan(RetentionPolicy p, Instant now, List<JobRetention> jobs, Option<Long> eventHorizonSeq,
                                    List<DumpFile> dumps);
}
```

**Rules:**
- **A job with `keep` produces no action. Ever.** This covers its evidence, full tarballs, checkout and rows. The
  same holds for every non-terminal job.
- Clocks start at `finishedAt`:
  - `full` tarball at 14 d;
  - `small` evidence plus the job's `testcases`, `manifests` and `unit_facts` rows at 90 d (Q-M1-11);
  - host checkout (`jobs/<job>` on the worker) at 24 h, only once its small evidence was pulled or definitively
    failed.
- Never deleted [G]: `results`, `jobs`, `units` and `result.json`.
- **Events** older than 30 d, by `at`, are deleted regardless of `keep` [G]. Events are a stream, not something a
  job references (Q-M1-11).
- `pg_dump` files are deleted after 14 d. **The newest good dump is always kept,** whatever its age.
- `DeleteEvidence` carries the `storedAt` the plan read. The row is marked deleted only while its `stored_at` is
  still that value, because a later pull replaces the live row in place (same id).
  - **A pull that lands after the file delete** keeps its row live with its new file. Without the guard that file
    would sit on disk under a deleted row, and no later plan would collect it.
  - **A pull that lands between the plan and the file delete** loses its new file to the delete, and its row stays
    live with no file. The next retention tick lists the row again with its new `storedAt` and finishes it.
  - **Guarantee:** a live evidence row lacks its file for at most one retention tick, and only while retention keeps
    running. A stopped GC leaves the row live, and a download of it then finds no file.

### 4.2 Execution (controller `retention/RetentionGc`, WP5; store `RetentionStore`, WP3)

`SharedScheduler` runs it every `retention.intervalMinutes` (60) behind a non-reentrant guard:
1. `RetentionStore.candidates(…)`;
2. `RetentionPlan.plan`;
3. execute the actions in batches.

| Action | How |
|---|---|
| file deletes | delete the file, then mark the `evidence` row `deleted_at`, only while its `stored_at` is the one planned (`DeleteEvidence.storedAt`, §4.1) |
| `DeleteTestcases` | `SET LOCAL temper.retention = 'on'`, the only door the write-once trigger opens (m0 §5.2 [V]), then delete in batches of 10 000 by `ctid` |
| `DeleteEventsUpTo(s)` | bounded steps, each its own transaction under the event order lock: delete the lowest 10 000 events with `seq <= $1` **and** raise `event_floor.floor_seq = GREATEST(floor_seq, <the step's highest deleted seq>)` (the last, short step: `$1`), capped at the events sequence's last value. The floor never passes an event that is still stored, and the lock every event writer waits for is held for one step at a time |
| `DeleteCheckout` | `GcJobs` to the host's worker (§4.3) |

### 4.3 Host checkouts (worker `gc/JobGc`, WP4)

`GcJobs{jobs}` → `GcDone{deleted: List<Long>, kept: List<{job, reason}>}`. A job directory is deleted only if all of
these hold:
- no unit of the job is in the registry (running, holding or collecting);
- `docker ps -a --filter label=temper.job=<id>` is empty (covers aux and Testcontainers-unrelated containers);
- every unit directory has an `rc` or no marker.

Deletion walks the tree without following symlinks. A file the worker cannot delete (for example root-owned, left
by a sibling container) makes it keep the job with the reason given, rather than half-delete it. The controller
records `checkout_gone` per host and job.

### 4.4 Event floor: fixing F7 (store `EventStore`, WP3; controller `EventFeed`, WP5)

```java
sealed interface Pending { record Gap(long from, long to) ; record Events(List<EventRow> rows) }
Promise<Pending> pending(long cursor, Set<String> kinds, int limit);
  // ONE statement (one snapshot even under READ COMMITTED):
  //   SELECT f.floor_seq, e.* FROM event_floor f LEFT JOIN LATERAL
  //     (SELECT … FROM events WHERE seq > $1 AND (…kinds…) ORDER BY seq LIMIT $3) e ON true
  //   floor_seq > cursor  →  Gap(cursor + 1, floor_seq)   else  Events(rows)
// minSeq() is removed; subscriber() creates new rows at GREATEST(max(seq), floor_seq).
```

**Why one statement:** reading the floor and the events in two statements lets a GC commit between them. The reader
would then see the post-GC events with the pre-GC floor and skip without a gap. A single statement sees one
snapshot. Since each GC step moves the floor and deletes in one transaction, the reader sees either both of a
step's changes or neither.

**Rejected alternative:** inferring gaps from holes in `seq`. Rolled-back transactions also leave holes, so the
floor is the only reliable signal. `V2` creates `event_floor` with one row, `floor_seq = 0`.

### 4.5 Disk guard during a run (worker `HeartbeatTask`, WP4)

- On every heartbeat, if free space on the docker root or the workspace filesystem is below
  `runFloorBytes` (default **10 GiB [G]**, lower than the 50 GiB start floor because a Gate legitimately consumes
  disk while it runs), the worker writes `storeOnce(.temper/void, "disk")` for every running unit.
- It does **not** kill the unit [G] (Q-M1-10).
- Exit processing turns the file into `UnitExited.voidReason = "disk"`, giving `VOID(disk)`. The observed reds are
  still listed.
- The same tick records `memAvailableMinKb` per running unit into `UnitEndFacts` (sizing, §8).

---

## 5. Honest guarantees for the new operations

| Operation | Guarantee | Mechanism | When it fails |
|---|---|---|---|
| hold the port group | A port-group unit's container starts only after bb-* granted temper a `[port-group]` ticket. The ticket is held until that container exits, through ssh drops and worker or controller restarts | bb-hold runs in its own session (`setsid`) and owns `docker wait` (the bb-hold contract) | bb-hold dies while the container runs → `VOID(port-group-lost)` and the container is killed, detected ≤ 2 heartbeats after a worker is attached. **Deferred** while no worker is attached (same as m0 §7.5 timeouts) |
| waiting for a hold | A waiting unit is neither re-run nor declared lost on reconnect | holder status in `Hello` (§1.5 rules 3–5) | holder dead before grant → requeue (nothing ran); dead after grant without a container → `ERROR(lost)` |
| manifest coverage | On a unit with a `ManifestSpec`, every class the rules expect from a **reached** module and not reported fails the job `FAILED(missing-classes)`; every class reported but not expected is listed as drift | pure rules over the effective POM and the compiled classes; compared with the `<testcase>` classnames | modules never reached are listed as `notReached`, not covered. An unsupported configuration is `ERROR(manifest)`, never a silent pass |
| evidence | A finished job's record says `stored:true` only if every unit that ran has a small evidence tarball on the controller | pull before the result commits; tarball fsynced and renamed; sha256 recorded | otherwise `stored:false` + `evidence=missing`; the verdict is unchanged |
| `result.json` | It equals the stored record, within one sweep interval | atomic write + startup/hourly sweep | evidence fs unwritable → retried each sweep, logged |
| `pg_dump` | one TOC-verified dump per day the controller was up; 14 d kept; the newest is never deleted | `pg_dump -Fc` (one snapshot) + `pg_restore --list` | failure → `maintenance_runs.ok=false`, previous dumps kept |
| retention | nothing of a `keep` or non-terminal job is deleted; deleted events are always announced to a lagging subscriber as a `gap` | pure plan; event deletion in bounded steps, each step's delete and floor raise (to that step's top) in one tx under the event order lock; single-statement read | a host checkout that cannot be fully deleted is kept and reported |

---

## 6. Refusals as data: management surface

**API** (controller `api/ApiRoutes`, WP5; all routes require the bearer token):

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/v1/refusals?all=true\|false` | — | `List<RefusalDto{code, matcher, issue?, reason, until?, expiredAt?}>` |
| POST | `/api/v1/refusals` | `RefusalAddRequest{code, matcher, issue?, reason, until?}` | 201 · 400 (bad code/matcher) · 409 (code exists) |
| POST | `/api/v1/refusals/{code}/expire` | — | 200 `RefusalDto` (its `code` and `expiredAt` included) · 404 |
| GET | `/api/v1/jobs/{id}/manifest` | — | `ManifestView{computed, pruned, entries[{module,class,runner}], optional[{module,class,runner}], missing, drift, notReached}` (T1 harness) |

**Manifest view (rulings R-2, R-7):**
- `entries` are the required classes and `optional` the classes that may or may not report. missing = required −
  reported; drift = reported − (required ∪ optional). Both are judged only when `computed`.
- `pruned` is true once retention deleted the job's `manifests` rows; `manifest_units.entry_count` records how many
  rows the unit wrote, and fewer remaining reads as pruned. Computed, not pruned and empty therefore always means
  "no tests".

**Validation:**
- `code` matches `^R-[A-Z0-9-]{1,30}$` and is not a structural code (`R-ENV`, `R-M2`, `R-SHELL`, `R-DISK`).
- `matcher` is one of the two closed kinds the domain can execute (`excludeTestClass`, `itTestRequired`), parsed
  by the existing `RefusalMatcherDto.toDomain()`.
- An unknown kind is a 400. A refusal must not exist that no code can evaluate.

**Store:** `RefusalStore.list(boolean includeExpired)`, `add(RefusalRow)` → `StoreError.RefusalExists` on a PK
conflict, `expire(String code)` → `expired_at = now()`.

**CLI** (WP6):
- `temper refusal list [--all]`
- `temper refusal add <code> --matcher <file.json> --reason "…" [--issue N] [--until "…"]`
- `temper refusal expire <code>`

`add` and `expire` print one line; `list` prints one line per refusal and nothing when there are none. The exit codes are 0, 64 for usage, 2 for a 409 **[G]**, and 69 when the controller is
unreachable.

---

## 7. Changes to existing modules, per file

New files are marked **+**. "WP" is the owner (§10).

### temper-domain (WP1 unless noted)

| File | Change |
|---|---|
| `model/Recipe.java` | **+** `Gate`; `Unsupported` now only for `mutate` |
| `model/UnitName.java` | constant `GATE` |
| `model/UnitSpec.java` | + `Option<ManifestSpec> manifest` |
| `plan/Planner.java` | Gate case; `ManifestSpec` for Gate/ModuleTest; reactor parameter; `EFFECTIVE_POM_OUTPUT` constant |
| `plan/PlanDefaults.java` | + `gateMemory` |
| `plan/PortGroupRule.java` | reactor-aware `mayInclude` |
| `mvn/MvnStep.java` | `mayInclude(module, Option<ReactorModules>)`; `reach()` (furthest test phase); reactor-selection args accessor |
| `mvn/ReactorModules.java` **+** | §1.6 |
| `refusal/Refusals.java` | drop `portGroup` from `checkSubmit`; R-FAILSAFE judges whole-reactor/-am/-amd with the reactor; R-1553 uses the reactor |
| `refusal/TreeFacts.java` | + `Option<ReactorModules> reactor` |
| `verdict/Verdicts.java` | annotation `manifest-drift=<n>`; missing = required − reported, drift = reported − (required ∪ optional) (R-2) |
| `verdict/Manifest.java` | `required` + `optional` class-name sets (was one `classes` set; R-2) |
| `hold/{HolderStatus,ContainerState,HoldAction,HoldRules,GrantLine}.java` **+** | §1.4 |
| `retention/*` **+** | §4.1 |
| `manifest/**` **+** (**WP2**) | §2.2 types used by the planner (`ManifestSpec`, `Reach`, `Runner`) **land first in WP2's API commit**; the rest is §2.4 |

### temper-protocol (WP1)

| File | Change |
|---|---|
| `message/Handshake.java` | `PROTOCOL_VERSION = 2` |
| `message/Message.java` | `StartUnit` + `portGroup`, `manifest`; **+** `UnitHolding`, `UnitManifest`, `GcJobs`, `GcDone`; `UnitExited` + `voidReason` |
| `message/DiskUnitDto.java` | + `holder` |
| `message/{HolderStatusDto,PortGroupHoldDto,ManifestSpecDto,ManifestOutcomeDto,ManifestEntryDto}.java` **+** | wire forms |
| `message/UnitEndFactsDto.java` | + `memAvailableMinKb: Option<Long>` (with `domain/facts/UnitEndFacts`) |
| `api/RecipeDto.java` | `gate` maps to `Recipe.Gate` |
| `api/ResultRecordDto.java` | + `manifest` block; `EvidenceDto` filled; `UnitResultDto` + `portGroup{ticket, heldFrom}?` |
| `api/UnitView.java` | + `portGroup: Option<String>` (`waiting\|held`), `ticket` |
| `api/{RefusalDto,RefusalAddRequest,ManifestView}.java` **+** | §6; `ManifestView` + `pruned`, `optional` (R-2, R-7) |
| `message/ManifestOutcomeDto.java` | `computed` + `optional`, `unresolvedSupertypes` (final chunk only; R-2) |
| `exec/Command.java`, `exec/JdkProcessRunner.java` | + `Option<Path> stdoutFile` (redirect to a file) |

### temper-store (WP3)

| File | Change |
|---|---|
| `resources/db/migration/V2__m1.sql` **+** | `units` + `port_group text CHECK (port_group IN ('waiting','held'))`, `bbhold_ticket text`, `hold_since timestamptz`; `manifests` + `unit_id`, `runner`; **+** `manifest_units`, `evidence(id bigserial, job_id, unit_id, kind, path, bytes, sha256, stored_at, deleted_at)`, `event_floor(id int PK CHECK (id = 1), floor_seq bigint NOT NULL)` seeded `(1, 0)`, `maintenance_runs`, `checkouts(job_id, host)` (recorded at grant, so a requeue that clears `units.host` keeps the host listed; seeded from `units.host`), `checkouts_gone(job_id, host, gone_at)`; `units` + `manifest_spec jsonb` (the planner's `ManifestSpec`, carried by `StartUnit`) |
| `resources/db/migration/V3__m1_manifest.sql` **+** | `manifests` + `optional boolean NOT NULL DEFAULT false` (R-2); `manifest_units` + `entry_count int` (rows written, backfilled from `manifests`; R-7) |
| `pg/Manifests.java`, `api/JobManifest.java` | optional rows written and read apart; `JobManifest` + `optional`, `pruned` (R-2, R-7) |
| `api/QueuedUnit.java`, `api/ActiveUnit.java` | + `UnitKind kind`, read from `units.kind` (R-3) |
| `api/UnitStore.java` | + `holding(ref, HoldingRow)`; `started` sets `port_group/ticket`; `complete` + `Option<UnitManifestRows>`, + `Option<StoredEvidence>` |
| `api/EventStore.java` | `pending(...)`, `floor()`; `minSeq()` removed |
| `api/RefusalStore.java` | + `list`, `add`, `expire` |
| `api/{EvidenceStore,RetentionStore,MaintenanceStore}.java` **+** | §3, §4 |
| `api/ActiveUnit.java`, `api/UnitRow.java` | + `portGroup`, `ticket` |
| `api/Store.java` | + accessors for the three new stores |
| `pg/PgUnitStore.java`, `pg/PgEventStore.java`, `pg/PgRefusalStore.java` | as the API |
| `pg/{PgEvidenceStore,PgRetentionStore,PgMaintenanceStore}.java` **+** | — |
| `pg/Finishing.java` | loads `manifests`/`manifest_units` → `some(Manifest)` per §2.5 |
| `pg/ResultRecords.java` | evidence from `evidence` rows; `manifest` block; unit `portGroup` |
| `pg/Rows.java`, `pg/PgStore.java` | mapping and wiring |

### temper-controller (WP5)

| File | Change |
|---|---|
| `config/ControllerConfig.java`, `config/ConfigLoader.java` | + `[units] gateMemoryMb`, `[portGroup] owner="temper" maxHolding=1`, `[evidence]`, `[retention]`, `[pgDump]` |
| `submit/TreeReader.java` | reactor poms (§1.6) |
| `submit/SubmitJob.java` | gather before plan; Gate allowed |
| `host/UnitLauncher.java` | `StartUnit.portGroup/manifest`; `UnitHolding` → `UnitStore.holding`; routes by `HostSnapshot.holdsPortGroup` (R-3) |
| `host/WorkerEvents.java` | unsolicited `UnitStarted`/`UnitStartFailed`; manifest buffering; evidence pull before `complete`; `GcDone` |
| `host/ReconcileRun.java`, `policy/Reconciler.java`, `policy/DiskUnit.java` | holder rules (§1.5) |
| `policy/GrantPolicy.java`, `policy/HostSnapshot.java`, `scheduler/Scheduler.java` | port-group fit + `maxHolding`; `HostSnapshot.holdsPortGroup(kind, needsPortGroup, portIsolated)` decides who goes through bb-hold (a Gate even on a port-isolated host), and both the fit and the holder count use it (R-3) |
| `api/ManifestViews.java` | `optional`, `pruned`; drift excludes optional classes (R-2, R-7) |
| `host/SshCommand.java` | `evidence(host, ref, kind)` |
| `events/EventFeed.java` | `pending()`; gap from the floor |
| `events/EventLines.java` | `missing=<n>` |
| `api/ApiRoutes.java`, `api/Views.java` | refusal routes, manifest route, unit `portGroup` |
| `evidence/{EvidencePuller,ResultFiles}.java` **+** | §3 |
| `maintenance/PgDump.java` **+** | §3.4 |
| `retention/RetentionGc.java` **+** | §4.2 |
| `Controller.java` | wiring + schedules |

### temper-worker (WP4)

| File | Change |
|---|---|
| `unit/UnitLauncher.java` | `Launch` sealed result; `routed(...)` |
| `unit/{BbHoldLauncher,GrantWatcher,HoldMonitor,HolderProbe}.java` **+** | §1.2–§1.4 (`HolderProbe` reads `/proc` and the pid file) |
| `unit/UnitStarter.java` | PomProbe pre-step; `Holding` branch → `UnitHolding` reply |
| `unit/UnitCanceller.java` | hold-aware order (§1.7); also fix the wrong "docker kill first" comment (WP4 round-2 note) |
| `unit/UnitCollector.java` | manifest in the fork-join; `voidReason` from `.temper/void`; `log.tail` |
| `unit/UnitRegistry.java` | holding units; included in `running()` |
| `unit/StartRequest.java` | + `portGroup`, `manifest` |
| `manifest/{PomProbe,ManifestCollector}.java` **+** | §2.3, §2.5 |
| `gc/JobGc.java` **+** | §4.3 |
| `host/StateScan.java` | `holder` per unit; second inspect for rule 4 |
| `link/Router.java`, `link/RequestHandler.java` | `GcJobs`; `Reattach` re-arms `GrantWatcher` or `HoldMonitor` |
| `link/HeartbeatTask.java` | run floor; `memAvailableMinKb` sample |
| `facts/FactsProbe.java` | temper container ids by `label=temper.job` (F13) |
| `docker/DockerCli.java` | `runAux(--rm)`, `idsByJobLabel(job)` |
| `config/WorkerConfig.java` | `bbHoldPath` (default `$HOME/tools/bb-hold`), `runFloorBytes` |
| `config/UnitFile.java` | + `BBHOLD_PID`, `BBHOLD_ERR`, `BBHOLD_OUT`, `BBHOLD_TICKET`, `VOID` |
| `config/WorkerPaths.java` | `manifestDir(ref)`, `logTail(ref)` |
| `ops/worker-launch` | `evidence` verb (§3.2) |

### temper-cli (WP6)

| File | Change |
|---|---|
| `command/CommandLine.java` | enable `submit gate`; `refusal list\|add\|expire` |
| `command/JobCommands.java`, `command/Lines.java` | `result`/`wait` lines add `missing=` `drift=` `evidence=`; refusal lines |
| `transport/Controller.java` | refusal endpoints |

### temper-acceptance **+** (WP7)

This is a new module under the existing `acceptance` profile. It contains the A11 host ITs, the A12 oracle IT with
its fixture project, the T1 harness, the T2 harness and the sizing report (§8, §9).

---

## 8. T1 and T2 tooling, and sizing (WP7)

### 8.1 T1 comparison harness

```java
// org.pragmatica.temper.acceptance.trust
record BbReport(int rc, long testcases, long failures, long errors, long skipped, Set<String> failedClasses,
                boolean empty, Set<String> argsWords) {}
sealed interface BbReportParser { static Result<BbReport> parse(String stdout, int exitCode); }  // bb-report format [V, scratchpad copy]
record TemperSide(ResultRecordDto result, List<TestcaseDto> rows, ManifestView manifest) {}
record ShaComparison(String sha, boolean countsEqual, Set<String> failedOnlyBb, Set<String> failedOnlyTemper,
                     boolean emptyEqual, Set<String> missing, Set<String> drift, String crashEvidence,
                     List<String> caseLevelDiff /* diagnostic */) { boolean passed(); }
sealed interface TrustComparison { static ShaComparison compare(String sha, BbReport bb, TemperSide temper, List<ParsedTestcase> bbRows); }
final class T1Harness { static void main(String[] args); }  // --controller URL --prio N --sha S --bb-co PATH (×3+) --out docs/trust-gate
```

**Procedure** (run on bigboy in a maintainer window, Q-M1-3):

1. **Preflight per SHA:**
   - `git -C <bb-co> rev-parse HEAD` equals the SHA;
   - `.bb/args` is empty or `clean install` (bb-build's default [V]);
   - `.bb/rc` exists;
   - report XML newer than `.bb/run.marker` exists.

   A SHA that fails any check is not comparable and is reported as such.
2. **Snapshot first:** copy `.bb/` and the XML newer than the marker into `T1-<date>/<sha7>/bb/` under the output directory,
   then run `bb-report <co>` and keep its stdout and exit code. bb checkouts are reused, so the snapshot is the
   record.
3. `temper submit gate --sha S --fresh --prio <maintainer>`, then `temper wait`.
4. **Compare** (the criterion, spec T1):
   - `<testcase>` count = bb's `<testcase> elements`;
   - failed set at **class level** (bb-report prints `FAILED: <class>` from file names [V]) = temper's classes with
     a failed or error row;
   - EMPTY ⇔ bb exit 3;
   - **zero manifest disagreements:** `drift = ∅`, and `missing = ∅` unless the log tail shows a fork crash
     (`The forked VM terminated`), in which case it is recorded and explained, never waved away.
   - **Diagnostic, not the criterion:** a case-level diff of temper's rows against bb's XML parsed with the same
     `ReportParser`.
5. **Write** `T1-<date>.md`: one table per SHA with ✔/✘ per check, the diffs, the job id, the
   facts at start and end, and the overall verdict. The main session commits it after an independent verifier
   re-runs step 4 from the snapshots.

### 8.2 T2 crashed fork

The test class is `CrashedForkProbeTest`, with one `@Test` that calls `System.exit(3)`. It is added to a module early
in the reactor, which keeps the Gate short because the reactor stops there.

**Assertions:**
- the verdict is `FAILED`, reason `missing-classes`;
- `manifest.missing` contains the probe class;
- rc ≠ 0;
- the log shows the fork-crash line.

**Control:** the parent SHA's Gate (from T1) is not `missing-classes`.

**Where the crashed-fork SHA comes from: undecided (Q-M1-1).** The Pushed source only accepts SHAs that pragmatica
origin contains [V]. The options:

| Option | What | Cost | Note |
|---|---|---|---|
| (a) | A scratch branch `temper/t2-crashed-fork` pushed to pragmatica origin, deleted after T2 | none in temper | needs maintainer approval for pushing to pragmatica |
| (b) | Move the `Bundle` source (spec §2.2; M2) into M1: `POST /blobs`, bundle verification, the `unpushed` tag | M-sized: WP1, WP3, WP5, WP6 | T2 evidence would be `unpushed`. That is fine for T2, but it pulls M2 scope forward |
| (c) | An **acceptance-only origin**: a controller instance on the acceptance ports (17070/55433) whose `repo.origin` is a local bare clone of pragmatica plus the scratch commit | none in production code | the same code path, but the evidence comes from a test instance, not the live controller |

### 8.3 Sizing for §12.1 (from the T1 runs)

`SizingReport` (WP7) writes `T1-<date>-sizing.md`:

| Datum | Source | Honesty note |
|---|---|---|
| per-module test time | Σ testcase `time_s` grouped by module through `/jobs/{id}/manifest` | that is test time, not wall time; drift classes are listed as unattributed |
| per-module wall time | the Maven Reactor Summary in the log tail (`ReactorSummary.parse`, pure) | it includes compile time |
| per-unit `memory.peak` | `UnitEndFacts` (cgroup, 10 s sampling) [V] | a lower bound; it **excludes Testcontainers siblings**, which have their own cgroups |
| host `MemAvailable` minimum during the unit | the new `memAvailableMinKb` | captures the siblings and bb-* load together |
| load and bb tickets at start and end | `facts-start/end.json` [V] | — |

**Derived values:**
- `gate.emberShards` and `unit.memory` are computed so that they fit in `memoryBudgetMb` while one bb-* reactor
  runs.
- If `rest` (≈ Gate wall − Σ ember test time) exceeds 40 min, the report says so (spec §12.1).

**Gap:** an unsharded Gate cannot measure one Ember shard's memory. The report proposes one `ModuleTest -pl
aether/ember` run on a T1 SHA as the upper-bound measurement (Q-M1-13).

---

## 9. Test plan per acceptance criterion

**Tiers:** unit (default build), it-pg (testcontainers `postgres:16`), docker (runner image, no bb-*), host
(bigboy, `-Pacceptance`, a maintainer window). **Every probe is run by an independent verifier, never the author.**

### 9.1 A11: bb-hold re-attach

| Tier | Test (owner) | Mutation probe → must go RED |
|---|---|---|
| unit | `HoldRulesTest` (WP1): a table over (earlier, later, container) | VOID on a single DEAD observation; reading container-before-holder in the fixture order; dropping the RUNNING condition |
| unit | `GrantLineTest` (WP1): `granted T1` / noise / partial line | accepting any line containing "granted" |
| unit | `BbHoldLauncherTest` (WP4, fake ProcessRunner and files): argv has `setsid` and no `sh`/`-c`; the pid file holds `<pid> <startTicks>`; the marker is written before spawn | drop `setsid` (an argv assertion); write the marker after spawn |
| unit | `GrantWatcherTest` (WP4): grant → files + `UnitStarted`, timer armed from the grant time; rc 2 → ERROR; rc 3 twice → ERROR; rc 3 then grant → started; rc 143 + cancelled → CANCELLED | arm the timer at the enqueue; treat rc 143 as CANCELLED without the cancelled set |
| unit | `HoldMonitorTest` (WP4): two DEAD observations + RUNNING → `void=port-group-lost` + kill; a recycled pid (different starttime) counts as DEAD | identity check by pid only |
| unit | `UnitCancellerHoldTest` (WP4): the container exists → `docker kill` before any signal; waiting → SIGTERM, then a container re-check and kill | swap the order; drop the re-check |
| unit | `ReconcilerHoldTest` (WP5): rules 3–5 of §1.5 | delete rule 3 → a waiting unit is MarkLost |
| unit | `GrantPolicyPortGroupTest` (WP5): fits with no holder; blocked at `maxHolding`; `portIsolated` bypasses | restore the M0 `!needsPortGroup \|\| portIsolated` line → the Gate is never granted |
| host | `BbHoldReattachIT` (WP7), on a fixture repo whose module is in the acceptance controller's `portGroup.modules`, test sleep 120 s. **Case 1:** while RUNNING, kill the ssh pid and restart the controller. Assert `~/tools/bb-queue 0` lists the temper ticket as running until the container exits, then not within 10 s; the unit is PASSED, with one `started_at` and the same container id. **Case 2:** `kill -9 $(cut -d' ' -f1 .temper/bbhold.pid)`. Assert `VOID(port-group-lost)` within 30 s, the container is gone, and the ticket is released (bb reap ≤ 4 s). **Case 3:** occupy the group (a second temper hold unit), submit, cancel while waiting. Assert CANCELLED, no container, no queued temper ticket | disable `HoldMonitor` → case 2 ends PASSED; delete Reconciler rule 3 → case 1 variant (drop ssh while waiting) ends ERROR(lost) |

### 9.2 A12: manifest fixtures

| Tier | Test (owner) | Mutation probe → RED |
|---|---|---|
| unit | `EffectivePomTest` (WP2) on XML fixtures: the `<projects>` wrapper and the single form; plugin vs execution config merge; `<phase>none`; a path outside ws refused | execution config ignored; plugin config wins over execution |
| unit | `SelectionsTest` (WP2): explicit config > user property > module property > default; `-Dtest` replaces includes **and** excludes; failsafe `it.test`; skip flags per runner; reach TEST excludes failsafe | user property beats explicit config; `-Dtest` keeps excludes; failsafe counted at reach TEST |
| unit | `SurefirePatternsTest` (WP2): simple name, path glob, `!`, `#method`, `%regex[]`, default includes/excludes, `**/*$*` | `*` crossing `/` |
| unit | `ManifestRulesTest` (WP2), on class files **synthesised with `java.lang.classfile`** (so the fixtures never run as temper's own tests). It covers the A12 list: module-specific includes/excludes/groups; a `@Nested`-only outer (only `Outer$Inner`); mixed tagged methods (stays); all methods excluded by tag (gone); class-level tag excluded; failsafe `*IT` in a module with failsafe bound, and the same class with failsafe unbound (absent); inherited tests (concrete subclass in, abstract base out); a meta-annotated `@Test`; an unresolved annotation recorded; tag expression → Unsupported; `notReached` | nested named by itself; outer-only-nested included; groups ignored; "all methods excluded" rule dropped; inheritance dropped; failsafe bound ignored |
| docker | `ManifestOracleIT` (WP7): a fixture Maven project (`temper-acceptance/src/test/resources/manifest-fixture/`) with the modules `includes`, `groups`, `nested`, `failsafe`, `skip`, `inherit`, `meta`, `disabled`. It runs `mvn -B verify` in the runner image, then the real `PomProbe` + `ManifestCollector`. **Oracle:** `manifest == reported classnames` exactly, with `drift = missing = ∅`. This is the instrument that settles §2.4 rule 7 | each ManifestRulesTest probe re-run here must also go red. Control: delete one fixture test class after the build → `missing` names it |

### 9.3 T1: trust gate

| Tier | Test | Probe / instrument validation |
|---|---|---|
| unit | `BbReportParserTest` (WP7) on captured bb-report outputs: normal, red with `FAILED:` lines, EMPTY exit 3, DRE warning | — |
| unit | `TrustComparisonTest` (WP7) | compare `testsAttr` instead of `<testcase>` → red on the `@Nested` fixture (bb sums 177 vs 293, S1 [V]); ignore the failed set → red; ignore drift → red; treat `missing` on a non-crash run as OK → red |
| host | the live T1 (§8.1) on ≥ 3 SHAs, ≥ 1 red | **Before trusting it:** run the harness on a doctored snapshot (one `<testcase>` removed from one bb XML, one failure flipped) → it must report ✘ on exactly those checks |

### 9.4 T2: crashed fork

| Tier | Test | Probe |
|---|---|---|
| unit | `VerdictsTest.crashedFork` (WP1, extends A2): rc 1, the manifest has class X with no rows → `FAILED(missing-classes)` naming X | swap the `missing-classes` and `rc` order → `FAILED(rc)` |
| docker | `CrashedForkIT` (WP7): the manifest fixture with one `System.exit` class, `verify` → `missing` names it, rc ≠ 0, and no `<testcase>` for it | `Finishing` passes `none()` → `FAILED(rc)`, red |
| host | the live T2 (§8.2) | the same `none()` probe on the acceptance controller |

### 9.5 Other M1 items (not exit criteria, but tested)

| Item | Tests | Probe |
|---|---|---|
| event floor | `EventFloorIT` (WP3, it-pg): GC deletes all events → a lagging subscriber gets `Gap`; a new subscriber after GC gets no spurious gap; 200× concurrent GC + read never skips without a gap | read the floor in a separate statement → the concurrent test goes red (probabilistic, loop); drop the floor update → red |
| retention | `RetentionPlanTest` (WP1): `keep` and non-terminal are exempt; each clock; the newest dump is kept | drop the `keep` check → red |
| GC store | `RetentionIT` (WP3): testcases deleted only inside `temper.retention='on'`; results untouched | delete without the setting → trigger error, test red |
| host GC | `JobGcTest` (WP4): an active unit, a labelled container or a missing rc keeps the job; symlinks are not followed | follow links → red (a symlink out of ws gets deleted) |
| evidence | `EvidencePullerTest` (WP5, fake ssh process): cap, gzip magic, rc, atomic rename; `stored:false` + `evidence=missing` when the pull fails | accept rc ≠ 0 → red |
| worker-launch evidence | `worker-launch` bats-style test (WP4): `../`, a symlinked unit dir, a bad job id, extra args → 64; a valid call → tar on stdout | drop the realpath check → red |
| result.json | `ResultFilesIT` (WP5): crash between commit and write (test hook) → the sweep writes it; byte-identical to `results.record` | remove the sweep → red |
| pg_dump | `PgDumpIT` (WP5, it-pg via testcontainers, `docker exec` into the PG container): dump + `pg_restore --list` OK; a corrupted file → `ok=false` | skip verification → red on the corrupted file |
| refusals API | `RefusalRoutesTest` (WP5) + CLI tests (WP6): add / list / expire, 409, 400 on an unknown matcher or a structural code | accept a structural code → red |
| R-1553 with reactor | `RefusalsTest` (WP1): a Gate with the class enabled in forge-tests, reactor without forge-tests → allowed; reactor unknown → refused (conservative); `-Pwith-e2e` → judged as M0 | revert `mayInclude` → the Gate is refused |
| run floor | `HeartbeatRunFloorTest` (WP4): below the floor → `void=disk` once, no kill | kill on breach → red (pins Q-M1-10's guess until ruled) |

---

## 10. Work packages

```
WP1 shared types (domain non-manifest + protocol) ──┬──► WP3 store ──(api commit)──► WP5 controller ──┐
WP2 manifest engine (domain/manifest/**) ───────────┼──────────────────────────────► WP4 worker ───────┼──► WP7 acceptance + trust (host)
                                                    └──► WP6 CLI                                       │
WP2 ────────────────────────────────────────────────────────────────────────────────► WP7 oracle IT ────┘
```

**Order:**
1. **WP2's first commit is its API types** (`Runner`, `Reach`, `ManifestSpec`, `ManifestEntry`, `ManifestOutcome`,
   the `ManifestRules.compute` signature with a stub). WP1's planner needs `ManifestSpec`. WP2 then fills in the
   engine in parallel with everything else.
2. WP1 lands. Then WP3's first commit is the `store.api` changes verbatim from §7; WP5 codes against them with
   fakes.
3. WP3, WP4, WP5 and WP6 run in parallel.
4. WP7's unit-tier and docker-tier work (harness core, oracle IT) starts after WP2. Its host tier runs last, after
   WP4 and WP5 merge.

**File ownership is exclusive;** no file is edited by two packages.

| WP | Owns | Content | Size (main + test LOC, est.) |
|---|---|---|---|
| **WP1** shared types | `temper-domain/**` **except** `domain/manifest/**`; `temper-protocol/**` | §1.1, §1.4 domain, §1.6, §4.1, §7 domain and protocol rows; A2 extension (`crashedFork`) | **M**: ~0.9k + 1.0k |
| **WP2** manifest engine | `temper-domain/src/main/java/org/pragmatica/temper/domain/manifest/**`, its tests and `src/test/resources/manifest/**` | §2.2 types, §2.4; A12 unit tier | **L**: ~1.6k + 1.8k |
| **WP3** store | `temper-store/**` | V2, §7 store rows, event floor, retention SQL, `Finishing`/`ResultRecords` | **M**: ~1.0k + 1.1k |
| **WP4** worker | `temper-worker/**`, `ops/worker-launch` | §1.2–§1.4, §1.7, §1.8 (first), §2.3, §2.5 worker side, §3.2 verb + `log.tail`, §4.3, §4.5, F13 | **L**: ~2.0k + 1.8k |
| **WP5** controller | `temper-controller/**`, `ops/temper-controller.service` | §1.3, §1.5, §1.6 (TreeReader, SubmitJob), §3, §4.2, §4.4 feed, §6 API | **L**: ~1.9k + 1.6k |
| **WP6** CLI | `temper-cli/**`, `ops/temper` | §6 CLI, `submit gate`, result-line fields | **S**: ~0.3k + 0.4k |
| **WP7** acceptance + trust | `temper-acceptance/**` (new module; the root-pom profile stub already names it [V]) | §8, §9 host and docker tiers, fixtures; T1/T2 run scripts. The **main session** runs the live T1/T2/A11 and commits the `T1-*.md`, `T2-*.md` records | **L**: ~1.5k + 1.2k + fixtures |

**Handoff contract** (unchanged from m0 §10):
- `jbct-reviewer` reviews each package.
- An independent verifier runs the §9 probes red/green and records them in a `WPn-…` verification record.
- The build is a root `mvn install` through `build-runner`, which never writes `~/.m2`.
- Host-tier runs happen only in maintainer windows, under the suite lock rules.

---

## 11. Open questions for the maintainer

Each question carries a **marked guess**. The design proceeds on the guess until the maintainer rules.

| # | Question | Guess |
|---|---|---|
| **Q-M1-1** | **T2's crashed-fork SHA:** Pushed accepts only SHAs that pragmatica origin contains. Options: (a) a short-lived scratch branch on pragmatica origin; (b) move `Bundle` into M1; (c) an acceptance-only controller with a local origin (§8.2) | **Not decided here, per brief.** Leaning (a): zero temper code, and the real controller produces the evidence. (c) is the fallback if pushing to pragmatica is not wanted. (b) only if M2's Bundle is wanted early anyway |
| **Q-M1-2** | **worker-launch `evidence <job> <unit> small\|full`:** the forced-command key gains read access to `~/.temper/jobs/<job>/units/<unit>` (tar to stdout, validated, realpath-confined). Approve? | Approve. It is read-only, no new key and no `authorized_keys` change. Record it in the bigboy host record |
| **Q-M1-3** | **T1 inputs:** which ≥ 3 SHAs (≥ 1 red) with **intact** bb checkouts (`.bb/` + XML), the maintainer window, and the `--prio` for the Gate holds | The maintainer names them. The harness snapshots bb evidence before temper runs |
| **Q-M1-4** | **R-1553 vs the Gate:** resolve whole-reactor membership from the root pom's `<modules>` (+ any profile with an `<activation>`), so a Gate is not refused because of a class outside the default reactor (F5) | Yes. An unknown reactor falls back to the conservative M0 behaviour |
| **Q-M1-5** | **R-FAILSAFE on whole-reactor/-am/-amd steps** (F6): refuse `-Dtest` without `-Dit.test` when the resolved reactor may include forge-tests | Yes |
| **Q-M1-6** | **Manifest "declares":** count tests inherited from superclasses and interface default methods (JUnit 5 reports them under the subclass) | Yes ("effectively declares"); otherwise every such class is drift on green runs |
| **Q-M1-7** | **Manifest failure:** an effective-pom failure or an unsupported config (`includesFile`, tag expressions, …) → `ERROR(manifest)`, and the build does not run when the pre-step fails | Yes. Never a Gate PASSED without coverage |
| **Q-M1-8** | **Manifest scope in M1:** Gate and ModuleTest only; `Mvn` gets no coverage check | Yes. Mvn's arbitrary goals make "the lifecycle reached" guesswork |
| **Q-M1-9** | **Effective POM online, in an auxiliary container before the hold** (deviation from §5.2's "`-o` … prior pass") | Yes. It saves port-group time and works with the seed |
| **Q-M1-10** | **Disk floor during a run:** `VOID(disk)` when free space < a *run floor* of 10 GiB (the 50 GiB start floor would void a healthy Gate that eats its own disk). Kill or not? | 10 GiB, mark only, no kill |
| **Q-M1-11** | **Retention specifics:** keep `results`, `jobs`, `units` and `result.json` forever; delete `testcases`/`manifests`/`unit_facts` rows at 90 d; events at 30 d, including those of pinned jobs; the newest dump always kept | As stated. Note: deleting testcases limits M2's `diff` to 90 d |
| **Q-M1-12** | **bb-hold owner and concurrency:** owner tag `temper` (its `~/work/priorities` value is 5), `<prio>` = the job priority (advisory); at most **one** temper unit waiting or holding per host; a waiting unit counts against cap and memory | As stated |
| **Q-M1-13** | **Gate memory** until §12.1: `units.gateMemoryMb = 16384` within `memoryBudgetMb = 20480`; plus one `ModuleTest -pl aether/ember` on a T1 SHA to bound a shard's memory | As stated. A too-low limit OOMs a fork, which reports as `missing-classes`: a false red |
| **Q-M1-14** | **bb-hold facts:** does SIGTERM while *waiting* remove the queued ticket? What does it need in its env? What is on stdout? Does a failed `docker run -d` after a grant release the ticket? | WP4 reads `~/tools/bb-hold` first (§1.8). Guess: yes; HOME+PATH; the `docker run` output; yes |
| **Q-M1-15** | **M0 host-tier records:** `temper-acceptance` is absent on `main`, and there is no A4/A7-live/A10-live record. Where is that evidence? | They were run ad hoc and not committed. M1's WP7 creates the module, and the M0 host cases should be re-run and recorded with A11 |
| **Q-M1-16** | **A11 live** takes a real `[port-group]` ticket on bigboy's bb-queue (case 3 holds it twice) | Run it inside the T1 window |
| **Q-M1-17** | **Hold wait bound:** should a unit waiting in bb-hold time out (e.g. 12 h → `ERROR(port-group-wait)`)? | No bound in M1. Cancel is the bound, and `portGroup=waiting since` is visible |
| **Q-M1-18** | **`GET /jobs/{id}/evidence` and `temper evidence`** (spec §7, §8.2): rc4 http-server has no streaming (m0 §2.4 [V]) | Defer to M2. In M1, evidence is read on the controller host |

---

## 12. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | bb-hold behaviour differs from the delivered contract in the unknowns of Q-M1-14 (env, dequeue on signal, stdout) | WP4 reads the script first (§1.8). The design holds the escalation point before any hold code |
| R2 | Manifest rules diverge from surefire (pattern semantics, `@Disabled` and conditional classes, meta-annotations from jars, inheritance) → disagreements fail T1 | Unsupported configurations error out rather than guess. The docker-tier **oracle** (`manifest == reported` on a fixture) runs before T1. `unresolvedAnnotations` is reported. T1's drift check is the backstop |
| R3 | **Container vs bare host:** bb-* ran the Gate on the host; temper runs it in a container (no passwd entry for the uid, `git` possibly absent from the image (m0 §7.6 [U]), hostname, `/tmp`, ulimits, the memory limit). Counts can differ for environmental reasons, and T1 requires exact equality | Each T1 mismatch is triaged with the case-level diff. Environmental differences are fixed in the runner image or recorded; never tolerated silently |
| R4 | bb checkouts are reused, so the bb side of a T1 SHA can vanish | Snapshot before submit (§8.1 step 2) |
| R5 | A ~94 min Gate holds the port group and blocks bb-* Ember runs | maintainer windows and priorities; `maxHolding = 1` |
| R6 | A waiting hold parks a temper cap slot and its memory | `maxHolding = 1`; visible `portGroup=waiting` |
| R7 | **Timeout and hold enforcement are deferred while no worker is attached** (as M0) | A documented guarantee (§5); re-attach fires both immediately |
| R8 | A pid is reused for the holder | starttime + cmdline identity (§1.4) |
| R9 | The evidence verb widens the controller key to reads of unit directories | The path is confined by realpath, the verb is read-only, and the key stays `restrict`ed to localhost (Q-M1-2) |
| R10 | `memory.peak` excludes Testcontainers siblings, so sizing that relies on it is low | Also record host `MemAvailable` minimum; the report states the gap (§8.3) |
| R11 | Root-owned files in a workspace (from sibling containers) block host GC | `JobGc` keeps the job and reports it; nothing is half-deleted |
| R12 | The effective-POM pre-step adds about 1 min per unit and a new failure mode | It runs outside the hold; the failure is visible as `ERROR(manifest)` |
| R13 | Full evidence tarballs (logs up to 200 MiB) fill the controller disk | 14 d retention; `evidence.minFreeGiB` skips `full`, never `small` |
| R14 | Protocol v2 needs controller and worker to deploy together | The version handshake already forces it (m0 §6.4 [V]) |
| R15 | Reactor parsing at submit misses a module that a non-default profile adds | Any `<activation>` profile counts; `-P` falls back to "may include all" |

## 13. maintainer rulings on §11 (2026-10-13)

- **M1-1: (a), with conditions.**
  - The branch is `scratch/temper-t2-crashed-fork`, deleted after T2.
  - Workflows checked (rc4 `.github/workflows/*`): no push trigger matches `scratch/**`, and the
    `pull_request` triggers need a PR, which we never open.
  - T1's red uses the same mechanism.
- **M1-2:** the `evidence` verb is APPROVED.
  - Realpath-confined under `~/.temper/jobs`, with a size cap and no env dump.
  - Tests: a path escape is refused; a symlink is refused.
- **M1-3, T1 inputs:**
  - **Green 1:** `ea364e8952`, bigboy checkout `fdepfsm2c-gate`. Root `clean install`, rc 0, 4158
    XML, about 21048 testcases.
  - **Green 2:** `0cf563192a`, cloudbb-1 checkout `cto-smoke-0cf563192ad5`. rc 0, 20786
    testcases (a same-SHA count is host-independent).
  - **Red:** a deterministic `scratch/temper-t1-red` = rc4 tip plus ONE trivially failing
    assertion in a small module. bb-build runs it first, then temper. The branch is deleted after
    T1. NOT `v2172-gate` (a load flake, #2176).
  - **Pushed reachability is extended to `refs/pull/*/head`.** `ea364e8952` lives only there. It
    is still on origin per `ls-remote`, so the evidence rule holds.
  - **No special window:** priority 5 through the normal queue, and no timing claims. Snapshot the
    bb evidence first.
- **M1-12, 13, 16, 17:** agreed. Gate memory is 16 GiB until the sizing report exists.
- **M1-14, bb-hold facts:**

  | Case | Exit | Behaviour |
  |---|---|---|
  | SIGTERM while waiting | 143 | the ticket goes to done as `cancelled` |
  | cancelled via `bb-queue cancel` | 9 | — |
  | argv fails after the grant | argv's rc | the ticket is released as `start-failed` |
  | duplicate owner+name | 3 | refused |
  | `docker wait` failed | 1 | — |
  | other refusals | 2 | — |

  - **Environment:** none needed. It sets an absolute PATH and LC_ALL=C, and derives USER and HOME.
    It never reads stdin.
  - **stdout:** argv's own output, i.e. the container id. Every bb-hold message, including
    `granted <ticket>`, goes to stderr.
- **M1-4 to 11, 15, 18:** accepted.
- **Backlog:** a `temperacc` priority line. The maintainer adds it on request; temper never writes
  `~/work/priorities`.

## 14. Rulings during M1 verification (2026-10-13)

The owner ruled on these after the independent WP2–WP5 verifications (their reports are not part of this repository).

- **R-1, integration order.** WP2, WP3, WP4 and WP5 merge into `m1`. The cross-cutting changes below are made once, on `m1`. An independent verifier then checks the integrated `m1`.
- **R-2, `optional` in the shared types.** The manifest carries two sets: `required` (the old `entries`) and `optional`. The verdict computes:
  - missing = required − reported;
  - drift = reported − (required ∪ optional).

  `optional` crosses the wire (`ManifestOutcomeDto`), the store (`manifest_units`) and the view (`JobManifest`). The formula was the main session's proposal, and the owner accepted it.
- **R-3, unit kind on `QueuedUnit`.** The grant fit knows whether a unit is a Gate. On a port-isolated host, a Gate still counts against `maxHolding`, so a second Gate is never granted only to wait in bb-hold (m0 §12.4).
- **R-4, jbct layering covers every module.** The controller, store and worker packages are classified in `jbct.toml`, and any violations are fixed before T1. Until now, "jbct clean" for those modules meant formatting and lint only, because the layering check examined 0 of their files.
- **R-5 (WP2 F1), JUnit 4 runners.** A JUnit 4 class with a non-default `@RunWith` is `optional`, and so is every class its runner could report. Such classes are never missing and never drift. This is precise only for the default runner; nothing else is modelled.
- **R-6 (WP2 F2), cross-module `@Nested`.** Host lookup follows superclasses across modules. A `@Nested` class that a subclass in module B reaches is expected in module B. This replaces the owning-module-only limit, which only ever caused drift.
- **R-7 (WP3 F7), pruned manifests.** Once retention removes a job's `manifest_units`, `GET /jobs/{id}/manifest` says so with `pruned: true`. Computed-and-empty therefore always means "no tests".
- **R-8 (main session), evidence on lost or hold-failed units.** These are not pulled. Their records say `stored:false` and `evidence=missing`.
- **R-9 (main session, marked as a guess), a lost `UnitStarted`.** On re-attach of a running hold unit, the worker re-sends `UnitStarted` with its ticket. The grant watch re-posts until a post succeeds. This relies on the store recording a start at most once (`startLocked`: GRANTED or CANCELLING, with no `startedAt`). Settle it with a protocol ack if T1 shows a gap.
- **R-10 (owner, follows R-4), `store.api` is application.** The driver is twofold: the layering check must examine every module, and the controller's application layer (cancel, policy, result, submit) imports `store.api`. So `store.api` names no protocol or adapter type. The store opens through `store.pg.Postgres.connect`, which overrides m0 §5.3's factory on the `Store` interface. Event kinds (`EventRow.Kind`), counts (the domain `Counts`) and the result record (`ResultRecord`) are application-side models, mapped to and from the wire DTOs in `store.pg.WireRecords`. The jsonb and the wire DTOs are byte-identical, and `ResultRecordRoundTripIT` pins that, including a stored M1 (V2/V3) row.
- **R-11 (owner, 2026-10-13), JUnit 5 (Jupiter) only.** temper is an internal tool, and every repo it gates runs Jupiter (pragmatica: 0 `junit.framework`, 0 JUnit 4 or Vintage dependencies, 0 `@RunWith`). The JUnit 3/4 modelling came from the WP2 oracle fixture, not from gated code. The manifest engine models Jupiter only: a module whose test classes reach `junit.framework.TestCase` or use JUnit 4 (`org.junit.Test`, `@RunWith`) yields `ManifestOutcome.Unsupported` (an ERROR, not a guess, as risk R2 already rules). The JUnit 3/4 model, the custom-runner optional classes (R-5) and `unresolvedSupertypes` are deleted. This settles integration finding F3 by removal. If temper is ever published, the docs state that nothing below JUnit 5 is supported.
  - **R-11 limit (main session, 2026-10-13):** detection follows only superclasses compiled in the reactor. A test class whose superclass comes from a jar or test-jar that reaches `TestCase` or carries JUnit 4 annotations, and a JUnit 3 `suite()` class, are not detected: the manifest is Computed without them. On a Jupiter-only classpath those tests never run, so nothing silently stops running; indexing dependency jars would close it and is out of M1 scope.
- **R-12 (owner, 2026-10-13), no backward compatibility until Aether GA.** Wire, schema and stored rows may change incompatibly; nothing is kept only to read older data. Existing deployments are reset or migrated destructively when that is simpler. This overrides the M0-row concern of the 2026-10-13 handover.
- **R-13 (main session, 2026-10-14; the retry schedule and the attempt timeout are marked guesses), a start that cannot be recorded.**
  - **Retry, then drop.** On every start path, hold and direct, a failed start write never resets the session. It is retried after pauses of 200 ms, 1 s, 2 s and 5 s (5 attempts, about 8 s of pauses, enough for a Postgres restart). Each attempt has its own 5 s timeout, so a hanging store bounds the whole start at attempts × timeout + pauses, about 33 s. Then the start is logged and dropped, and the collection completes the unit without start facts. A late write from a timed-out attempt is a no-op under the at-most-once guard, but it is not cancelled, so a hanging store can hold up to 5 connections per start.
  - **Ordering.** The direct path registers its pending start before `StartUnit` is sent, so the collection always waits for it to be recorded or dropped (`M1-R13-verification`, F1).
  - **Host.** `startable` also checks the unit's host, so a start from a host that no longer owns the unit is never recorded.
  - **Tests.** The pauses and the attempt timeout are part of `LinkTiming`. The fast harness uses 1 ms pauses and a 2 s attempt timeout.
  - **Accepted degradation**, which T1 should note when it appears. If the store is down longer than the retry window while a unit starts, the unit runs with no start facts (no `started` event, container, ticket or `portGroup`), and its job shows QUEUED until the exit completes it. If the worker's unit directory is also lost, reconcile requeues that unit rather than marking it lost, so it can run twice. The old reset healed the first case through reconcile, but it could livelock (`M1-round2-verification`, V2-1).
- **R-14 (main session, 2026-10-14, marked as a guess), a fork crash in a T1 SHA.** A SHA where either side shows a fork crash is reported as **not comparable**, with the crash lines and the missing classes listed and explained. It never passes, and it does not count toward T1's three SHAs. Crashed forks are T2's subject (§8.2). This keeps §8.1 step 4 ("recorded and explained, never waved away") without making a crashed SHA a permanent T1 failure (`M1-WP7-isolation-verification`, A5).
- **maintainer rulings, 2026-10-14 (on the M1 approval request):**
  - **T1 "red".** A comparable SHA counts as red only if bb-report lists at least one `FAILED:` class **and** that class is the test the red SHA deliberately breaks. The expected class is named in the T1 record. A red from any other class is "not the planted red": it is explained and not counted. rc≠0 alone never counts. R-14 is adopted: a fork crash on either side is not comparable, and an unwritten crash explanation makes the report INCOMPLETE.
  - **R-13, amended.** The retry schedule is adopted (pauses of 200 ms, 1 s, 2 s and 5 s, a 5 s timeout per attempt, then drop, no session reset). The accepted degradation is **not** adopted as worded: a job whose start facts were lost must not show QUEUED while it runs, because a status claiming a different state is a wrong fact. It shows `UNKNOWN` (start not recorded) until its exit facts arrive, and a test pins that.
  - **Main-session readings of these rulings, ADOPTED by the maintainer (2026-10-14).** The maintainer's condition on the second: a test pins the bound from both sides. QUEUED is never shown beyond 1 s once `UnitStarted` has arrived, and a healthy start never shows UNKNOWN.
    - **T1 disagreement always fails.** A SHA where temper's and bb's failed sets differ is FAIL, whatever the planted class. "Not the planted red, explain it and do not count it" applies only to a red on which both sides **agree** but which comes from another class. That SHA is listed, explained (INCOMPLETE until written) and not counted toward the three SHAs.
    - **UNKNOWN from controller memory.** A unit the controller knows to be running (its `UnitStarted` has arrived) whose start is not recorded shows UNKNOWN once its start write has been in flight for 1 s, or as soon as an attempt fails. It stays UNKNOWN in memory until the start is recorded or the unit completes, whatever happens to the mark loop. Reconcile after a controller restart adds to the same set. A healthy start therefore shows QUEUED for at most 1 s.
    - **The `planted` class is stored with each T1 run's evidence.** A re-run judges against the stored value, never a new argument.
  - **Owner decisions (2026-10-14, confirmed in the temper session):** the launcher upgrade is approved; the second acceptance-only key is approved, time-boxed and revoked once A11 passes; the window starts after the final verification.
