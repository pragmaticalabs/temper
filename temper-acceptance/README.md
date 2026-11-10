# temper-acceptance (M1 WP7)

Acceptance and trust tooling for M1 (`docs/m1-design.md` §7, §8, §9). The module is built only with
`-Pacceptance`; the default reactor never sees it.

| Tier | Runner | Tests | Needs |
|---|---|---|---|
| unit | surefire, `*Test` | `BbReportParserTest`, `TrustComparisonTest`, `T1ArgsTest`, `T2CheckTest`, `ReactorSummaryTest`, `SizingReportTest` | nothing |
| docker | failsafe, `*IT` tagged `docker` | `ManifestOracleIT` (A12), `CrashedForkIT` (T2), `T1HarnessIT` (the T1 harness end to end), `ForeignMirrorIT` (F1: a worker mirror made from another origin), `MultiHostLostIT` and `SideEffectNotRequeuedIT` (M4: a host LOST and back, two hosts, m4-design §12.3) | a docker daemon, network for Maven Central |
| classpath | failsafe, `ClasspathIT` (untagged) | the controller and worker jars on the IT classpath hold their own classes only (F5) | nothing |
| host | failsafe, `*IT` tagged `host` | `BbHoldReattachIT` (A11) | bigboy, `-Dtemper.host`, see below |

```
env -u HCLOUD_TOKEN mvn install -Pacceptance        # from the root: default reactor + unit + docker tiers; host tier excluded
```

## The docker tier: the first real worker → controller crossing

`stack/AcceptanceStack` runs the real controller in the test JVM (real routes, real `GitCli` against a local bare
origin of the fixture project, Postgres 16 through Testcontainers) and the real worker as a separate JVM
(`stack/LocalWorker`) whose stdin/stdout are the frame stream, as under `ssh -T` (`stack/LocalSsh` replaces only
ssh). Units run in the runner image on the local daemon. Jobs are submitted and awaited through the CLI's shaded
jar, as the T1/T2 procedure does. The evidence pull runs the real `ops/worker-launch evidence` verb. What a
developer machine forces: `/proc` is a fixture directory, bb-hold and bb-queue are absent (`[portGroup] modules` is
empty), and the worker takes no host lock. The Maven repository a workspace starts from is cached in
`target/acceptance-seed-m2` (the first run downloads from Maven Central inside the unit container). The
harnesses' runnable jar is attached as `target/temper-acceptance-<ver>-all.jar`, never as the main jar: failsafe
puts the main artifact first on the test classpath, where a shaded copy of temper-domain would shadow the one under
test (it did, during the first probe round, until the jar was attached instead). temper-controller and temper-worker
attach their runnable jars as `-all` the same way (F5): their main jars, which are on this module's test classpath,
hold only their own classes, so a probe in temper-domain, -store or -protocol reaches the ITs whatever the dependency
order. The `-all` jars only launch separate JVMs (the worker jar the host tier installs, `temper.worker.jar`);
`ClasspathIT` pins it.

### The fixture project (`src/test/resources/manifest-fixture/`)

Built from the archived oracles; every class keeps its archive name where it came from one.

| Module | What it pins | Source in `~/temper-wt/scratch-archive-2026-10-13/` |
|---|---|---|
| `includes` | custom includes replace the defaults (`GammaTests` absent), an exclude (`ExcludedTest`), failsafe bound to phase `none` (`UnboundIT` absent) | `ca3422b0…/vm1int/oracle/fixture` profiles `inc`, `ex`; fixture3 `fsinc` |
| `groups` | `groups=fast`, `excludedGroups=slow`: class tag, mixed method tags, every method excluded | fixture `JTaggedTest`, `KMixedTest`, `LAllSlowTest`; scenarios `grp`, `exg` |
| `nested` | `@Nested` named by the outermost path, a `@Nested`-only outer is no entry, a static helper excluded by `**/*$*`; the R-6 bases in a test-jar | fixture `BTest`, `CTest`, `WHolderTest`; fixture7; fixture6 module `a` (`CmAbsBase`, `CmChainBase`, `CmJupBase`, `CmConcreteBaseTest`) |
| `failsafe` | failsafe bound here only: `*IT`, `IT*`, `*ITCase` | fixture `MyIT`, `ITFoo`, `FooITCase` |
| `skip` | `skipTests` in the module's own config | fixture3 profile `cfgskip` |
| `inherit` | inherited tests (abstract base, interface default); R-6 cross-module `@Nested` | fixture `AbstractBaseTest`, `DSubTest`, `ItfTests`, `EImplTest`; fixture6 module `b` (`CmSubTest`, `CmChainSubTest`, `CmJupSubTest`, `CmSubConcreteTest`, `CmLocalNestTest`) |
| `meta` | meta-annotated `@Test`, parameterized, repeated; factories and templates are optional | fixture `HParamTest`, `UFactoryTest`, `IFactoryTest`, `ZTemplateTest` |
| `disabled` | §2.4 rule 7: class-level `@Disabled`, disabled methods, an OS condition, a disabled `@Nested` | fixture `FDisabledTest`, `QDisabledMethodsTest`, `TCondTest` (here `WindowsOnlyTest`), `SOuterTest` |
| `display` | the reported name (T1 2026-10-14): a class's own `@DisplayName` value, raw, is the `classname` (top level, `@Nested` at each depth, special characters, padding, equal to the simple name, disabled, parameterized and factory tests); generators, inheritance and meta-annotations never rename; with `failsafe`'s `DisplayIT` | new (T1 reviewer notes: pragmatica `VerifyTest$VerifyMethodsTest` reports as `Verify class tests`) |
| `display352`, `display353` | the reported name by plugin version (T1 2026-10-14 run 2): surefire and failsafe 3.5.2 (with JUnit 5.11.4, pragmatica `jbct`'s pins, set per module through `maven-surefire-plugin.version`) and 3.5.3 write the binary name of display-named top-level and `@Nested` classes, next to `display`'s 3.5.4 in one reactor | new (T1 run 2: pragmatica `jbct`'s `CstLinterTest$X` reported by its binary name, missing 43 / drift 39) |

Overlays (`manifest-fixture-overlays/`, one branch each in the fixture origin):

| Overlay | Use | Source |
|---|---|---|
| `badpom` | Q-M1-7: `skip/pom.xml` is not a pom, so the effective-POM step fails: `ERROR(manifest)` and the build never runs | m1-design §2.3 |
| `junit4`, `junit3` | ruling R-11: a module with a JUnit 4 or JUnit 3 class is `ERROR(manifest: …)`; no Vintage engine anywhere | fixture `GJunit4Test`, `RJunit3Test`; fixture6's JUnit 4 runner cases (R-5) are gone with R-11 |
| `crash` | T2: `CrashedForkProbeTest` calls `System.exit(3)` in `includes`, the first module | m1-design §8.2 |
| `hold-long`, `hold-short` | A11 (host tier): module `hold` sleeps 120 s / 5 s; it is the host stack's `[portGroup] modules` | m1-design §9.1 |

The WP2 26-class surefire 3.5.4 oracle (`4d4552c4…/vm1wp2-oracle/`) and its baselines (`vm1wp2-harness/*.manifest`,
`*.reported`) are the same classes as `fixture/` plus JUnit 3/4 ones; its per-scenario `-Dtest`/profile matrix stays
in the archive (a Gate has no `-Dtest`; `SelectionsTest` and `SurefirePatternsTest` pin those in WP2's unit tier).

## Mutation probes

Unit and docker tiers (run with the WP7 delivery; each must turn the named test class red, then the source is
restored and the module reinstalled):

| Probe | Exact change | Red in |
|---|---|---|
| U1 | `TrustComparison.compare`: `counts().testcases()` → `counts().testsAttr()` | `TrustComparisonTest` (293 vs 177 on the S1 XML) |
| U2 | `ShaComparison.passed`: drop `&& failedEqual()` | `TrustComparisonTest` |
| U3 | `ShaComparison.manifestAgrees`: drop `&& drift.isEmpty()` | `TrustComparisonTest` |
| U4 | `ShaComparison.manifestAgrees`: `missing.isEmpty()` → `true` | `TrustComparisonTest` |
| U5 (R-14) | `ShaComparison.comparable` → `true`, or drop its `bbCrashEvidence` term; `TrustReport.overall` counts a crashed SHA | `TrustComparisonTest`, `TrustReportCrashTest` |
| D1 nested named by itself | `ManifestRules.reach`: `hostedDiscoveries(...)` → `Stream.<Discovery> empty()` | `ManifestOracleIT` |
| D2 outer-only-nested included | `ManifestRules.expectation`: `kept.isEmpty()` → `kept.isEmpty() && type.nesting() != Nesting.TOP_LEVEL`, and required = `kept.isEmpty() \|\| …` | `ManifestOracleIT` |
| D3 groups ignored | `ManifestRules.isKeptByGroups`: the `groups` term → `true` | `ManifestOracleIT` |
| D4 "all methods excluded" dropped | `ManifestRules.isKeptByGroups`: the `excludedGroups` term → `true` | `ManifestOracleIT` |
| D5 inheritance dropped | `TestClasses.hierarchy` → `Stream.of(type)` | `ManifestOracleIT` |
| D6 failsafe bound ignored | `Selections.isReachedPhase` → `true` | `ManifestOracleIT` |
| D7 display name under every version | `ReportNaming.reportNaming` → `Result.success(OWN_DISPLAY_NAME)` | `ManifestOracleIT` (`display352`, `display353` missing and drift) |
| D8 binary name under every version | `ReportNaming.reportNaming` → `Result.success(BINARY_NAME)` | `ManifestOracleIT` (`display` missing and drift) |
| S1 | `Finishing.input`: `Manifests.forVerdict(manifest)` → `Option.none()` (verdict `FAILED(rc)`) | `CrashedForkIT` |
| control | delete one class's report after the build | `ManifestOracleIT.control_aClassWhoseReportIsGone_isMissing` asserts `missing` names it |
| §9.3 instrument | doctor the T1 snapshot (one `<testcase>` removed, one failure flipped) | `T1HarnessIT.rerun_onADoctoredSnapshot_failsExactlyTheDoctoredChecks` asserts ✘ on exactly count and failed set |
| M4 rule 0 off | `Reconciler.superseded` → `false` | `MultiHostLostIT.recovery_discardsTheSupersededAttempt_thenUp` (X's attempt-1 directory survives) |
| M4 P3 | `PgUnitStore.GRANT` without `attempt = attempt + 1` | `MultiHostLostIT.executions_exactlyTwoStarts_attempt1ThenAttempt2_noneAfterReturn` (Y adopts X's attempt-1 container) |
| M4 P5 | `LostSettle.notCancelled` ignores `sideEffects` (an `external` unit is requeued) | `SideEffectNotRequeuedIT` |

Host tier (for the main session, after a green host run; each must turn `BbHoldReattachIT` red):

| Probe | Exact change | Expected |
|---|---|---|
| H1 disable `HoldMonitor` | `temper-worker/.../unit/HoldMonitor.java`: `case VOID_PORT_GROUP_LOST -> voidedAndKilled(ref, container).map(_ -> action);` → `case VOID_PORT_GROUP_LOST -> Promise.success(action);` | `holderKilled_isVoidPortGroupLost` red: the job ends PASSED, not VOID within 30 s |
| H2 delete Reconciler rule 3 | `temper-controller/.../policy/Reconciler.java`: `case WAITING, HELD -> running(unit);` → `case WAITING, HELD -> absent(unit, found);` | `reattach_whileWaiting_isNotLost` red: the waiter ends `ERROR(lost)` |
| H3 remove the reap | `temper-worker/.../unit/HoldMonitor.java`: `.fold(killed -> reapedAfter(ref, killed));` → `;` (in `killedThenReaped`) | `holderKilled_isVoidPortGroupLost` red on any host: no `bb-queue reap:` line for the unit after the kill; on an idle host the ticket is also still listed 10 s after VOID |

Rebuild after each change: `env -u HCLOUD_TOKEN mvn install -pl temper-worker,temper-controller -DskipTests` (the
host stack installs the worker jar of `temper-worker/target` itself), run the host command below with
`-Dit.test=BbHoldReattachIT#<method>`, then restore and rebuild.

## Host tier (A11): `BbHoldReattachIT`

Runs ON bigboy as the worker's user, from a checkout of this tree. The controller runs in the test JVM with its own
Postgres (Testcontainers on bigboy's docker) and a local bare origin of the fixture; it reaches the worker through
the real ssh forced command with a SEPARATE acceptance key, and installs THIS build's worker jar as
`worker-<version>.jar` first (`HostSettings.installWorker`).

**The run never touches the live `~/.temper`** (M1 WP7 verification, F1). The acceptance key's forced command sets
`TEMPER_WORKER_HOME`, which `worker-launch` and the worker use instead of `~/.temper`: the acceptance worker has its
own mirror, jobs, seed, `worker.lock` and installed jar under `~/temper-acceptance/.temper`. `HOME` stays the user's,
so bb-hold and bb-queue (`~/tools`) are the host's own. `HostSettings` guards the live home BEFORE anything is
installed (A2):
- it refuses a `temper.host.key` under `~/.temper` (the live controller key, or anything else there) and a
  `temper.host.workerHome` whose `.temper` is, or lies in, the live `~/.temper`, comparing real paths (a symbolic link
  to `~` is refused too). A key starting with `~user/` is refused outright: give its absolute path;
- it then proves the key routes to the acceptance home with a read-only `evidence` call for a sentinel unit
  (`jobs/9000000000000000000/units/routing-probe`) that it creates only under `<workerHome>/.temper`; a symbolic link
  anywhere on that path (`.temper`, `jobs`, the job, `units`, the unit or its `log.tail`) is refused before anything is
  created; a key routing to the live home answers 66, and the run fails with nothing installed;
- only then does it `install`, and afterwards it checks that the jar landed in the acceptance home.

### The acceptance key: maintainer approval required before anything is installed (Q-13)

The host tier needs a **second forced-command key** in bigboy's `~/.ssh/authorized_keys`. This is not routine setup.
Under Q-13 (m0-design §12.1, §12.2) adding a forced-command key needs maintainer approval, and this key departs from the
approved Q-13 conditions 1 and 3. **Do not generate, copy or append anything until the maintainer has approved every item
below** (the approved list of the M1 WP7 isolation verification):

1. A **second** forced-command key in `~/.ssh/authorized_keys`, exactly the line appended in step 3 of the setup below.
2. Its private key **outside `~/.temper`**: `~/temper-acceptance/controller_ed25519` (0600, directory 0700, no
   passphrase, generated on bigboy, never leaving it). This departs from Q-13 condition 1.
3. A second writable temper root, `~/temper-acceptance/.temper`, where `install` writes and where the acceptance
   jobs, mirror and seed live. This departs from Q-13 condition 3.
4. **The launcher the key runs is pinned** by commit and sha256 (below). Re-installing it, from any commit, needs the
   same approval.
5. The setup steps below, which apply Q-13 conditions 4 and 5 (backup, one line appended, recorded in
   the bigboy host record, the maintainer notified), and a check that `aether`'s login shell is POSIX (sh, bash, dash or zsh):
   the forced command relies on it expanding `$HOME` and `VAR=x exec`.
6. The acknowledgement that the key can run **arbitrary code as `aether`** (`install` + `run`), as the Q-13 key can.
7. The window rules for host-tier runs: the suite lock, the live controller stopped after its units finish (below),
   and A11's real `[port-group]` tickets.

**Pinned launcher.** The candidate is `ops/worker-launch` at commit `fcc3bd33496c32a8577ebf730c646ef0d74aa497`
(the last commit that changed it in this tree), sha256
`ed833618225181780b2f89e16f5140ac5651c7ca98054801898011dfaedc10f7`. The approval names the commit and the sha256;
compute both from the checkout before asking:

```
git -C <tree> log -1 --format=%H -- ops/worker-launch                    # the commit
git -C <tree> show <commit>:ops/worker-launch | sha256sum                # the sha256 (macOS: shasum -a 256)
```

**Setup, only after approval** (as `aether` on bigboy, from a checkout holding the approved commit):

```
# 1. acceptance home and the pinned launcher (from the approved commit, not the working tree)
A=~/temper-acceptance
mkdir -p $A/.temper/bin && chmod 700 $A $A/.temper
git -C <tree> show <approved commit>:ops/worker-launch > $A/.temper/bin/worker-launch && chmod 700 $A/.temper/bin/worker-launch
sha256sum $A/.temper/bin/worker-launch                                   # must print the approved sha256; stop if not
[ -f ~/.temper/java-home ] && cp ~/.temper/java-home $A/.temper/java-home
TEMPER_WORKER_HOME=$A/.temper <tree>/ops/seed-host.sh                    # reads ~/work/m2-seed, writes $A/.temper/seed-m2

# 2. the key, generated on bigboy
ssh-keygen -t ed25519 -N '' -C temper-acceptance -f $A/controller_ed25519
getent passwd aether | cut -d: -f7                                       # must be sh, bash, dash or zsh

# 3. back up, then append exactly one line (the live Q-13 line stays as it is)
cp -p ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak-temper-acceptance-$(date -u +%Y%m%dT%H%M%SZ)
tail -c1 ~/.ssh/authorized_keys | od -c                                  # \n expected; anything else: no final newline
[ -z "$(tail -c1 ~/.ssh/authorized_keys)" ] || echo >> ~/.ssh/authorized_keys   # else the new line glues onto the last
printf 'restrict,from="127.0.0.1,::1",command="TEMPER_WORKER_HOME=$HOME/temper-acceptance/.temper exec ~/temper-acceptance/.temper/bin/worker-launch" %s\n' \
    "$(cat $A/controller_ed25519.pub)" >> ~/.ssh/authorized_keys
wc -l ~/.ssh/authorized_keys                                             # one more line than the backup (two if a newline was added)
ssh-keygen -lf ~/.ssh/authorized_keys                                    # every key still parses: the live one and the new one

# 4. record
ssh-keygen -lf $A/controller_ed25519.pub                                 # the fingerprint
```

5. **Record** in the bigboy host record: the appended line, the fingerprint, the backup's name, the launcher's commit
   and sha256, the login-shell check, and how to revoke. Then **notify the maintainer**.

**Revoke:** delete that one line from `~/.ssh/authorized_keys` (or restore the backup), then `rm -rf ~/temper-acceptance`.
The live key's line and `~/.temper` are untouched either way.

### Running it

Before the run:
- take the suite lock (`mkdir ~/src/.aether-suite-lock`), and remove it after; A11 takes real
  `[port-group]` tickets (Q-M1-16), two at once in the waiting, cancel and lost-start cases (`maxHolding` 2);
- stop the live controller (`systemctl --user stop temper-controller`) after its running units finish. This is
  required, not a courtesy. The homes, locks and installed jars are separate, but the units are not fully apart:
  - unit containers and networks are named `temper-<job>-<unit>`, with **no instance in the name**, and a worker
    adopts, removes, times out and cancels a container by that name;
  - what keeps acceptance names from meeting live ones is the **acceptance job-id offset**:
    `AcceptanceStack.ACCEPTANCE_JOB_ID_OFFSET` (7e11) starts every acceptance store's ids far above the live ids
    (`AcceptanceJobIdTest`, and `ForeignMirrorIT` asserts it on a real job);
  - the `temper.instance` label (`acceptance` for the acceptance controller; `[server] instance`, default `temper`,
    for the live one; F2) only keeps reconcile from treating the other controller's *running, labelled* containers as
    its own orphans. An unlabelled container counts as the reader's own;
  - the two still share the `temper/runner:<version>` image tag, which the acceptance worker rebuilds when this tree's
    runner differs (a live unit started meanwhile would run the acceptance runner; the live worker rebuilds it again
    on its first unit), and the port group, whose tickets A11 counts;
- after the run, remove the acceptance job directories (`rm -rf ~/temper-acceptance/.temper/jobs`); the live jar and
  home are untouched, so the live controller restarts as it was.

```
cd <this tree on bigboy>
env -u HCLOUD_TOKEN mvn install -Pacceptance -DskipTests
env -u HCLOUD_TOKEN mvn -Pacceptance -pl temper-acceptance verify -Dtemper.host=bigboy \
    -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=BbHoldReattachIT
```

`-Dtemper.host` activates the module's `host-tier` profile (only `host` ITs run; `docker` ones are excluded), and
every host IT skips itself without it. Optional: `-Dtemper.host.address` (default `localhost`), `-Dtemper.host.user`
(default the user), `-Dtemper.host.workerHome` (`~/temper-acceptance`; refused if its `.temper` is, or lies in, `~/.temper`),
`-Dtemper.host.key` (`<workerHome>/controller_ed25519`; refused if under `~/.temper`), `-Dtemper.host.knownHosts` (`~/.temper/known_hosts`, read only),
`-Dtemper.version` (this build's version, D-6: `<project.version>+<judge12>`, `+unjudged` for a plain build, as the
worker jar derives its own).

Cases (m1-design §9.1 and the M1 integration verification's F1/F4):

| Method | Asserts |
|---|---|
| `reattach_whileRunning_keepsTheTicketAndOneStart` | kill the ssh pid and restart the controller while RUNNING: `bb-queue 0` lists the ticket until the container exits, then not within 10 s; PASSED; `startedAt`, container, `portGroupSince` and ticket unchanged (the re-sent `UnitStarted` of R-9 is the end-to-end double start, F1) |
| `reattach_whileWaiting_isNotLost` | the same while a second hold unit WAITS: it is re-attached, never `ERROR(lost)`, and PASSES once the group is free |
| `holderKilled_isVoidPortGroupLost` | `kill -9` of the holder: `VOID(port-group-lost)` within 30 s; `bb-queue 0` no longer lists the ticket within 10 s of it, and the worker logged, for this unit after the kill, a `bb-queue reap:` line of the reap contract (`reaped …` or `reap: nothing to move`: another queue client may reap the ticket first) (maintainer ruling A11, m1-design §14: bb-queue's `reap()` has no timer, so on an idle host only the worker's `bb-queue reap` releases it; the host needs that subcommand); the container gone |
| `cancelWhileWaiting_isCancelled_withNoContainerAndNoTicket` | CANCELLED, no container, the queued ticket (from `bbhold.err`) gone |
| `lostStartThenExit_isCollectedOnce` | F4: controller stopped while a unit waits; bb-hold grants and the container runs to its end with no worker attached; on restart it is collected once and PASSES. Pins the R-9 residual gap: no `startedAt` and no record `portGroup` (no worker saw the grant, so no `bbhold.ticket` and no re-sent start) |

## T1 (m1-design §8.1), on the controller host

```
temper-acceptance/scripts/t1.sh --controller http://127.0.0.1:7070 --out docs/trust-gate \
    --planted <the class the red sha breaks> \
    --sha ea364e8952 --bb-co ~/work/co/fdepfsm2c-gate --sha <red sha> --bb-co <its checkout> [--sha … --bb-co …]
```

`--planted CLASS` is required on a live run (maintainer ruling "T1 red", m1-design §14): the fully qualified test class the
red SHA deliberately breaks, as bb-report prints it after `FAILED: `. The report names it. Without it the harness
refuses to run (usage, exit 64). The live run stores it as `T1-<date>[.N]/planted` before it judges any SHA, and a
re-run judges against that stored class, never a new argument (F1): on `--rerun`, `--planted` may be left out, and
one that names another class is refused (usage, exit 64, no report written). Evidence without a stored class is not
re-run (exit 69).

Options: `--prio N` (5), `--date YYYY-MM-DD` (today, UTC), `--temper "<command>"` (the CLI command; it inherits
`TEMPER_URL` from `--controller` and `TEMPER_OWNER` from `--owner`, default `t1_harness`; `t1.sh` and `t2.sh` default
it to `java -jar <this checkout>/temper-cli/target/temper-cli-<ver>.jar`, the CLI of the same build as the controller,
never a `temper` on PATH, and print which one they use; `TEMPER_CLI_JAR` overrides the jar), `--bb-report PATH`
(`~/tools/bb-report`), `--token-file PATH` (`~/.config/temper/token`), `--queue-hours Q` (24), `--run-hours R` (4; both 1 or more). It writes
`T1-<date>.md` and keeps `T1-<date>/planted` and `T1-<date>/<sha7>/{bb/,bb-report.out,bb-report.exit,sha,job}`. It never
writes over an existing report or another run's evidence: each live run of a date takes the first free run number,
creating its evidence directory exclusively. Run 1 is `T1-<date>.md` over `T1-<date>/`, run N `T1-<date>.N.md` over
`T1-<date>.N/`, so a second run the same day (or two started together) never touches the first's report, explanations,
`job` files or snapshots. A number whose report exists is skipped. The harness prints the path it wrote.
A SHA from another host (Green 2 on cloudbb-1) needs its checkout copied to the controller host first; the
preflight checks the copy.

A SHA whose temper log tail or bb `.bb/build.log` shows a fork crash (`The forked VM terminated`) is reported
**NOT COMPARABLE (fork crash)** (ruling R-14), neither ✔ nor ✘: the report lists its crash lines and missing classes
and asks for their explanation, which the reviewer writes into the report. It never passes and does not count
toward the >= 3 SHAs, so pick another SHA to reach three comparable ones. Crashed forks are T2's subject.

The wait has two bounds (owner ruling, option A). After the submit the harness polls the job until it leaves QUEUED,
for at most `--queue-hours` (a unit's wait in bb-hold for the port group, Q-M1-17, is seen while the job is QUEUED,
so it is queue time too); then `temper wait <job> --timeout <run-hours>` runs, its bound counted from that moment.
One bound over both failed a healthy shadow head: at prio 1 behind prio-5 Gates (~92 min each), job 39 queued
4 h 40 min, ran 1 h 32 min, and the old 6 h `--wait-hours` expired while it was RUNNING. A job still QUEUED at the
queue bound makes its SHA **NOT COMPARABLE (capacity)** ("job N still QUEUED after Qh", or "job N still QUEUED
(waiting for the port group since T) after Qh"): not judged, never ✘, not counted toward the >= 3 SHAs, and with
nothing to explain. The job is left as it is, not cancelled, so a later `--rerun` can judge it: `--rerun` reads each
job once, never waiting, and judges only a job in a final state. One still QUEUED stays NOT COMPARABLE (capacity)
("job N still QUEUED at rerun"); one RUNNING or UNKNOWN is a failure ("job N not finished at rerun (state S)").
A `temper wait` that times out (exit 7, its line showing a non-terminal state) is a failure, "wait timed out after Rh
at state S", and the unfinished job is not judged. The `--run-hours` default, 4, is above the Gate unit's own 3 h
timeout, so an overrunning Gate ends with its own ERROR verdict, which is judged, before the harness could time out
at RUNNING. `--wait-hours` is refused (usage, 64).

Overall verdict and exit code: **FAIL** (1) unless there are >= 3 comparable SHAs, every one passed, and at least one
of them is the planted red: bb-report lists the `--planted` class among its `FAILED:` classes (spec T1 "including
>= 1 red"; temper's failed set must still equal bb's). rc != 0 without a `FAILED:` class never counts, and a red SHA
that crashed does not count; the report then says a comparable red SHA is needed. A comparable SHA on which temper and
bb disagree is ✘ and counted whatever class failed, so it always FAILs T1 (a red temper missed is what T1 exists to
catch). A red on which both sides agree, from any class other than the planted one, instead of it or beside it, is
**NOT THE PLANTED RED**: those classes are listed with an explanation to
write, and they never count as T1's red. A SHA red from other classes only is, like a crashed one, left out of the
>= 3 SHAs ("explained, and not counted"); a SHA red from the planted class beside others still counts. When the criterion
holds but a crash or red explanation is still unwritten, the report is **INCOMPLETE** (2), never PASS: write every
explanation into the report, then set its overall verdict. **PASS** (0) only with no crashed SHA and no red from
another class.

Before trusting it (§9.3), and for the independent verifier's re-run of step 4 from the snapshots:

```
temper-acceptance/scripts/t1.sh --controller http://127.0.0.1:7070 --out docs/trust-gate \
    --date <the run's date> --rerun [--run N]
```

judges exactly one run's evidence, run N (default 1: `T1-<date>/`; N: `T1-<date>.N/`), and writes
`T1-<date>[.N]-rerun.md`, or `-rerun.2.md`, … when that exists. `--run` is refused without `--rerun`. Doctor a copy of the snapshot (one `<testcase>` removed from one XML, one failure
flipped) and re-run: it must say ✘ on exactly those checks (`T1HarnessIT` does this on the fixture).

## T2 (m1-design §8.2)

```
temper-acceptance/scripts/t2.sh --controller http://127.0.0.1:7070 --sha <scratch/temper-t2-crashed-fork sha> \
    --parent-job <the T1 Gate job of its parent sha> --out docs/trust-gate \
    --probe-class <binary name of CrashedForkProbeTest in that commit>
```

Writes the first free `T2-<date>[.N].md` (run 1 `T2-<date>.md`, run N `T2-<date>.N.md`; created exclusively, never
written over): verdict `FAILED(missing-classes)`, `manifest.missing` names the probe, no row for it, a
unit rc ≠ 0, the fork-crash line in the log tail, and the control (the parent's Gate is not `missing-classes`).

## Sizing (m1-design §8.3)

```
temper-acceptance/scripts/sizing.sh --controller http://127.0.0.1:7070 --out docs/trust-gate --job <T1 job> [--job …] \
    [--ember-job <ModuleTest -pl aether/ember job>] [--memory-budget-mb 20480] [--bb-reactor-mb 8192] [--ember aether/ember]
```

Writes `T1-<date>-sizing.md`. The derived values are a marked guess of formula (the design names the inputs only):
`unit.memory` = the Ember job's `memory.peak` × 1.25 rounded up to 512 MiB; `gate.emberShards` = (budget − one
bb-* reactor) / `unit.memory`, at least 1; `rest` = Gate wall − Σ test time under `--ember`, flagged above 40 min.
