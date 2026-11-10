# temper — M4 design: multi-host (W1–W3) (draft, revision 2)

**Status.** Revision 2: the maintainer reviewed revision 1 (`341e793`) on 2026-10-25; the rulings are R-M4-13..R-M4-18
(§16), folded into the sections they change, and every Q-M4 is ruled (§15). Written against `main` at `7b29616`.
Design only; no production code. It covers losing a host (W1), per-host limits and CPU classes (W2), and retiring a
host (W3). §13's scope and order are accepted (R-M4-17). Host install scripts (W4), per-host acceptance (W5) and egress/firewall are out of scope and
listed in §14 only. It does not restate the spec (`docs/spec.md` v0.2), the architecture (`docs/architecture.md`) or
m0–m3 (cited as m0 §n … m3 §n).

**Markers** (as in m0–m3): **[V]** verified by reading code or docs in this tree; **[U]** unverified, the coder or a
probe must confirm it first; **[P]** a temper proposal; **[G]** a guess forced by an underdetermined requirement.
**Q-M4-n** are questions for the maintainer (§15); all ten were ruled on 2026-10-25. **R-M4-n** are rulings (§16).

**Binding rulings carried in** (unchanged): **R-12** no backward compatibility until Aether GA, every schema change
says what it does to existing rows; **R-10** `store.api` names no protocol type; **R-4** every new package is
classified in `jbct.toml` in the commit that creates it.

**New hosts.** `cloudbb-3` and `cloudbb-4` (user `builder`; addresses in the operator's host config): Hetzner cpx62, 16 shared
vCPU, 30 GB RAM, `bb-hold`/`bb-queue` installed, bb cap 3. A money watchdog **deletes both servers** at a spend cap
or on 2026-11-22, whichever comes first, so either host can vanish while units run on it.

---

## 0. Findings from the code that drive this design

| # | Finding (file) | Consequence for M4 |
|---|---|---|
| F1 | **No attempt identity exists in the schema or on the wire.** `units` has no attempt column (V1–V10) [V]. The controller identifies an attempt only in memory, as `UnitAttempt(ref, grantedAt)` (`controller/host/UnitAttempt.java`) [V]. The worker identifies a unit by `UnitRef` alone: container `temper-<job>-<unit>` (`domain/model/UnitRef.containerName`), unit dir, and `marker` epoch-ns (`worker/config/UnitFile.MARKER`) [V]. No frame carries an attempt (`protocol/message/Message.java`) [V] | Fencing needs an attempt number that the store increments, the worker stamps on disk and on the container, and every unit frame echoes (§4) |
| F2 | **Store completion does not check host or attempt.** `PgUnitStore.completeLocked` rejects only a QUEUED or terminal unit (`NotActive`, `AlreadyComplete`) [V]; the host check lives in `WorkerEvents.ownership`, a separate read before the completing transaction (`WorkerEvents.java:782-808`) [V]. Today this is safe only because `REQUEUE` touches nothing but `GRANTED AND started_at IS NULL` units (`PgUnitStore.java:127-130`) [V], so a started unit never moves | M4 requeues **started** units. Without an in-transaction `host = $h AND attempt = $a` check, a late report from attempt *n* completes attempt *n+1* when both run on the same host (§4.3) |
| F3 | **The worker adopts any container with the unit's name** (`worker/unit/UnitStarter.adoptedOrStarted`, by `docker.containerId(ref.containerName())`) [V] | If attempt *n+1* is granted to the host where attempt *n*'s container still exists, `StartUnit` adopts the old container and reports its start as the new attempt's. The adoption must check the attempt label (§4.4) |
| F4 | **`unit_facts` keeps the first attempt's start facts**: `INSERT … ON CONFLICT (unit_id, phase) DO NOTHING` (`PgUnitStore.INSERT_FACTS`) [V]; only `hold` facts are replaced on a requeue (`HOLD_FACTS`) [V] | A requeued unit would show attempt 1's start facts. `unit_facts` gains `attempt` in its key (§6) |
| F5 | **A vanished host today** (traced, §1): the link retries forever with backoff ≤ 60 s; the host's units keep their state; leases lapse and are only shown; nothing re-grants, nothing ends; a cancel leaves units CANCELLING until the host returns (`HostLink.java:42-55`, `HostLinks.java:27-29`, architecture §3 "Leases") [V] | The maintainer's R-M4-1/R-M4-2 replace "visible loss, never re-granted" for a LOST host only (§3) |
| F6 | **A host-key change is indistinguishable from an outage today.** ssh runs with `StrictHostKeyChecking=yes` and a per-host `UserKnownHostsFile` (`SshCommand.ssh`) [V]; a mismatch makes ssh exit before the remote command, and its stderr is only logged line by line (`Connection.drainStderr`, `Connection.java:265-272`) [V]. `hosts.host_key` exists but is never written ("not configured in M0", `Controller.java:247`) [V] | Key mismatch is detected from ssh's stderr on the link session (§2.2) [U: exact OpenSSH text on the controller host] |
| F7 | **No `ConnectTimeout` on the link** (`SshCommand.ssh` sets only `ServerAliveInterval=15`, `ServerAliveCountMax=3`) [V]. A blackholed address makes each connect wait for the OS TCP timeout or the 90 s `Hello` timeout (`LinkTiming.DEFAULT.hello`) [V] | Harmless for correctness (the LOST clock runs from the last UP, §2.1), but each probe of a dead host holds a process for ~90 s. Add `ConnectTimeout=15` [P] |
| F8 | **Config is read once at start**; no reload path exists (no SIGHUP, no reload in `controller/config`) [V]. `[[hosts]]` is upserted into `hosts` at start (`PgHostStore.upsert`; m0 §11) [V] | W3's `retireAt` is moved by editing the config and restarting the controller; that is how every host setting changes today (§10) |
| F9 | **Grant eligibility is per host, but starvation is too.** `GrantPolicy.choose` treats a head that does not fit as starving the host after `starvation` (1200 s) (`GrantPolicy.java:25-27`) [V] | A `--dedicated-cpu` unit at the head of the queue must be **filtered out** before the policy on a `sharedCpu` host, never "not fit", or it would starve the cloud hosts (§9.2) |
| F10 | **No recipe is designed with external side effects**, but `Mvn` can express some. `Gate`, `ModuleTest`, `Rerun` run build and test steps into the job's own workspace and forced `.m2-local` (`domain/model/Recipe.java`, `Planner.FORCED_REPO_LOCAL`) [V]. `Mvn` takes arbitrary argv: `deploy` is a phase `MvnStep` parses (`MvnStep.TEST_PHASES`) and nothing refuses it [V]. Shell-capable goals (`exec:exec`, `exec:java`, `antrun:run`, long forms) are **already refused** as R-SHELL (`Refusals.SHELL_GOAL`, `Refusals.checkSubmit`) [V] (revision 1 said otherwise; corrected). Pushes, GitHub writes and alarm issues happen only in the CLI (`cli/alarm/GithubIssueSink`) and in `ops/` scripts, never in a unit [V] | The side-effect property is a declaration on the recipe (§5.1), and an `Mvn` argv the classifier marks external is **refused at submit** as `R-EFFECT` (R-M4-14, §5.3). Honest limit: every unit is root-equivalent on its host (m3 F1), so the property states intent, it does not contain anything |
| F11 | **Host facts are stored per unit**, not per comparison: `unit_facts` (phases start/end/hold, jsonb) and the evidence tarball's `facts-*.json` [V]; `UnitEndFacts.memoryPeakBytes` is the last heartbeat sample (`domain/facts/UnitEndFacts.java`) [V]; the sizing harness reads them from evidence (`acceptance/sizing/JobSizing.java`) [V]; the result record names the unit's host (`ResultRecords.unitResult`, `UnitResultDto.host`) [V]. The rerun bound reads history test times across all hosts (`PgResultStore.CLASS_DURATION`) [V] | "Non-comparable" is recorded as a per-attempt snapshot of the host's `sharedCpu` at grant (§9.4), not looked up later from a config that can change |
| F12 | Memory budget counts **temper units only** (`Scheduler.snapshot` sums `ActiveUnit::memoryMb`) [V]; the cap counts **foreign reactors plus temper units** (`HostSnapshot.reactors`, architecture §3) [V] | On cloudbb-3/4 a busy bb-queue (3 reactors) leaves temper **zero** slots at cap 3; bb-* memory is outside the 18 GB budget (§9.1) |
| F13 | Protocol version is **2** (`Handshake.PROTOCOL_VERSION`) [V]; m3 §7.4 also claims "protocol version 3" for the log probe, not yet landed [V] | M4 takes version 3 unless the log probe lands first; whichever merges second takes 4 (§7) |

---

## 1. What happens today when a host vanishes (F5, traced)

1. **Detection.** The link's ssh dies after `ServerAliveInterval 15 × ServerAliveCountMax 3` ≈ 45 s of silence
   (`SshCommand.ssh`), or the controller closes it after `timing.silence` = 35 s with no frame (`HostLink.livenessTick`,
   `LinkTiming.DEFAULT`), whichever comes first [V].
2. **Link.** `HostLink.down` moves to DOWN and schedules a reconnect at `1 s · 2^(attempt−1)`, max 60 s, jitter;
   the attempt counter resets only after 60 s of stable UP (`HostLink.java:403-436`, `LinkTiming.stableUp`) [V].
   It retries **forever**. A session that ends before `Hello` with `LinkError.Down` sets `deployFirst`, so the next
   connect first runs `WorkerDeploy` (an ssh `install`, 5 min timeout) [V: `HostLink.noHello`; U: that a refused or
   timed-out ssh surfaces as `LinkError.Down`].
3. **Store.** Nothing changes. Units stay GRANTED / UNKNOWN / RUNNING / CANCELLING with `host` = the lost host.
   `lease_until` lapses 30 s later (`[scheduler] leaseSeconds`); views show `leaseExpired` (`api/Views.leaseExpired`)
   [V]. No code path reads `lease_until` to act (grep: only `Views`, `LEASES`, `GRANT`, `REQUEUE`) [V].
4. **Jobs.** A job with such a unit stays RUNNING (or QUEUED/UNKNOWN) indefinitely; its result never exists. A cancel
   moves the unit to CANCELLING and `HostLinks.cancelOne` finds no UP link; it stays CANCELLING until the host's next
   handshake reconciles it (`HostLinks.java:27-29`) [V].
5. **Scheduling.** The scheduler ticks only UP hosts (`Scheduler.tick`, `filter(HostLink::isUp)`) [V], so the other
   hosts are unaffected; the lost units occupy no one's capacity.
6. **Alarm.** `worker-lost` fires while the host is not UP, with no grace period (`cli/alarm/Conditions.workerLost`) [V].
7. **Host key change.** ssh exits 255 before the remote command; the stderr line is logged and discarded (F6). The link
   behaves exactly as in 2 (the deploy attempts fail the same way, since `SshCommand.install` uses the same options) [V].

---

## 2. W1: the LOST state

### 2.1 When a host is LOST (R-M4-1)

A host is **LOST** when either:
- **link-down:** its link has not been UP for a continuous `hostLostAfter` (default 10 min, `[scheduler]
  hostLostSeconds = 600` [P: the config style of `leaseSeconds`, `starvationSeconds`]). The clock `downSince` is set
  when the link leaves UP, and at controller start. **It resets only after the link has been UP for `stableUp`
  (60 s, the backoff's existing reset rule)** (R-M4-16, ruling Q-M4-1): a host whose link reaches UP for a second every few minutes
  and drops again is still down for this purpose. Without that rule a flapping host would never be declared LOST
  while its units never finish;
- **host-key:** an ssh session (link or deploy) of this host ends with ssh reporting a host-key failure (§2.2). This is
  declared **at once**, without waiting for `hostLostAfter` (R-M4-16, ruling Q-M4-2): a mismatch is positive evidence that the
  address now answers as another machine (the watchdog deleted the server and the IP was reassigned or a server was
  recreated on it).

**`hostLostSeconds` must exceed `stableUp` (WP-7; maintainer ruling 2026-10-27: refuse `≤ stableUp`, no `2 ×` margin, no
warn-only mode).** `ConfigLoader` refuses `hostLostSeconds ≤
stableUp` (60 s, `LinkTiming.DEFAULT`, not configurable): the clock clears only after an UP of `stableUp`, so a
smaller bound makes every host deferred within a minute of a start, and its first drop LOST at once, spending its
units' one requeue on a flap. Tests that need a short bound (`MultiHostLostIT`, 5 s) build `ControllerConfig`
directly and wait `stableUp` first. Approved by the maintainer as a refusal (no margin, no warn-only mode); the bound is
`LinkTiming.DEFAULT.stableUp()` itself, and the test takes both boundary values from it. **Necessary, not
sufficient:** the clock clears at start + connect time + `stableUp`, so a first drop is still LOST at once when that sum
exceeds `hostLost`; a config that passes the check is not thereby safe. The message says so ("must exceed stableUp
(60 s) plus the expected connect time"). A margin above `stableUp` may be added only from a measured connect time;
none was measured, so none is added.

**Controller restart (R-M4-16, ruling Q-M4-8).** `downSince` is in memory; at start it is `now` for every host, so a host down
across a restart is declared LOST `hostLostAfter` after the start, not earlier. The controller cannot have observed the
link while it was itself down. A host stored `LOST` stays LOST across the restart: the link reads the mark before it
connects or writes anything to `hosts` (a state write would erase it), retrying a failed read with the link's
backoff, and the level settle (§2.3) and recovery (§2.5) then run as before the restart.

**Deferred verdict (ruling Q-M4-12).** A link that is UP again when the clock reaches `hostLostAfter` (before its UP
has lasted `stableUp`) gets no verdict yet: the host is neither healthy nor LOST but *deferred*. While deferred, (a)
it takes no new grant (`HostLink.isUp` is false; the digest's skip reason says `link=DEFERRED`); (b) if the link leaves
UP, the host is LOST at once, the clock going on from where it was (no restart); (c) if the UP lasts `stableUp`, the
clock clears, the scheduler is woken, and no requeue is spent. Premise, checked in the code: a reconnect's reconcile
reattaches the host's running containers (`Reconciler` → `Reattach`), so a host that comes back keeps its units, and
settling them would requeue work that is still running.

### 2.2 Detecting a host-key failure

`Connection` already drains ssh's stderr line by line (F6). It gains a classifier, pure
(`controller/host/SshDiagnosis.java` **+**): a line containing `Host key verification failed` or `REMOTE HOST
IDENTIFICATION HAS CHANGED` [U: OpenSSH 9.x text on bigboy, pinned by `SshdHostKeyIT`, §12.2] resolves the session's
`hostKeyFailure` promise, and the link declares LOST at once (it does not wait for the close). `WorkerDeploy`
classifies its captured stderr the same way, but only when the deploy ssh exits 255 (ssh's own failure; any other code
is the remote command's), and the link decides before its next connect.

As built (two deviations from fix round 2's wording, recorded here):
- **Until a `Hello` arrives**, not until the hello promise resolves: after the first `Hello` frame stderr is the
  worker's and is never classified. A close also fails the hello promise, and ssh's exit can close the session before
  its stderr line is read, so "until resolved" would drop exactly the failure it is for.
- **Ownership:** a session's failure is ignored only when the link has moved past it: it holds another session, or it
  is stopped. A session that closed as its ssh exited still counts until then, for the same race.

A missing `known_hosts` entry also prints `Host key verification failed` [U]. That is treated the same: the controller
cannot authenticate the box. A newly configured host with a wrong `knownHosts` file is therefore LOST at its first
connect; it has no units, so nothing is requeued, and it recovers by §2.5 once the file is fixed (ssh reads it per
connect, no restart).

### 2.3 What LOST does to the host's units (R-M4-2)

On declaring LOST, the controller settles **every active unit on the host** (`state IN ('GRANTED','UNKNOWN','RUNNING',
'CANCELLING')`, `PgUnitStore.ACTIVE_STATES`). Per unit, one transaction (job lock, then unit lock, the existing order of
`JobLock`/`UnitLock`), first matching rule:

| # | Unit | Outcome | Why |
|---|---|---|---|
| L1 | CANCELLING | **CANCELLED** (`UnitFinishes.cancelled`, `killedBy = cancel`) | A cancel was requested and accepted; requeueing would run work the user cancelled. The cancel's goal (the unit does not produce a result) is reached by fencing: the lost attempt can never store anything (§4). The container may still run on the vanished host; that is the same "cannot kill it, can disown it" position as `KillOrphan` on return |
| L2 | `side_effects = external` (§5) | **ERROR(host-lost)**, never requeued | R-M4-5: a second execution could repeat an external effect; a human decides. After R-M4-14 no submittable recipe produces such a unit; L2 covers pre-M4 rows (backfilled `external`, §6) and any future recipe that declares `EXTERNAL` |
| L3 | `host_lost_requeues = 1` | **ERROR(host-lost)** | R-M4-2 as amended by R-M4-13: **one** requeue per unit; the second loss ends the unit. The watchdog deletes both cloud hosts at once, so a second requeue rarely finds a live host |
| L4 | otherwise (GRANTED, UNKNOWN, RUNNING) | the attempt ends ERROR(host-lost) in `unit_attempts`; the unit returns to **QUEUED** with `host_lost_requeues + 1` | R-M4-2 |

**The settle guard (fix round 1).** Under the unit lock a unit is settled only when it is still active on the host
**in the listed attempt** (the attempt `UnitStore.active(host)` returned) **and** the host is still `LOST`, read
(`hosts.state`) inside the same transaction. A stale tick, one whose listing predates a recovery and a regrant of the
unit as attempt 2 on the same host, therefore settles nothing; a host that recovered between the listing and the
transaction is left to its recovery (§2.5). Both guards are pinned by `HostLostSettleIT.settle_staleTick_*`.

L4 in detail, one statement: `WITH lost AS (INSERT INTO unit_attempts … SELECT` the lost attempt's host, attempt,
grant/start times, `shared_cpu`, outcome `host-lost` `FROM units WHERE id = $1 AND host = $2 AND attempt = $3 AND
state IN (…active…) RETURNING unit_id) UPDATE units SET state = 'QUEUED', host = NULL, granted_at = NULL,
granted_judge = NULL, lease_until = NULL, started_at = NULL, container_id = NULL, image_id = NULL, marker_epoch_ns =
NULL, port_group = NULL, bbhold_ticket = NULL, hold_since = NULL, shared_cpu = NULL, host_lost_requeues =
host_lost_requeues + 1 WHERE id IN (SELECT unit_id FROM lost)`, with `$3` the listed attempt. Its affected count must
be 1: only then are the `unit-lost` event and the job rule written; an unmoved unit gets no attempt row, no event and
is not reported. The `attempt` value stays; the **next grant** increments it (§4.1), so the requeued unit's next
attempt has a new number. One `unit-lost` event per unit (§6).

**The job.** A job's state is not moved back: a RUNNING job stays RUNNING while its unit waits QUEUED (R-M4-16,
ruling Q-M4-5) (its `started` event was already delivered; QUEUED would read as "never ran"). After **every** L2–L4
settle (fix round 1), an UNKNOWN job with no UNKNOWN unit left goes back to **QUEUED only if every unit of it is
QUEUED** (no unit active, none started or finished: nothing of it was recorded as running); otherwise a unit is active
or finished and the job becomes **RUNNING**. A job the settle finished is not UNKNOWN and is left alone. No `started`
event is appended for UNKNOWN → RUNNING [G: no start was reported]. Pinned: an UNKNOWN unit requeued with its sibling
GRANTED → RUNNING; L2 on an UNKNOWN `external` unit whose sibling is QUEUED → RUNNING; the only unit requeued → QUEUED. A unit ended by L1–L3 completes through
the existing `UnitStore.complete` path, so the job finishes when its last unit does, judged by `Verdicts`: any unit
`ERROR(host-lost)` makes the job `ERROR(host-lost)` unless a `VOID` unit ranks first (`domain/verdict/Verdicts.java`
rules 1–2) [V]. **Siblings of a unit that hit L2 or L3 keep running** to their own end (R-M4-16, ruling Q-M4-4); cancelling them
would save capacity but adds a second, unasked-for outcome to the job.

**Level, not edge [P].** The settle runs on the LOST transition **and on every liveness tick while the host is LOST**
(`LinkTiming.livenessCheck`, 5 s): each tick reads `UnitStore.active(host)` and settles what it finds. So a settle that
failed half-way (store down) completes later, and a grant transaction that committed just as the link dropped is
caught. Every rule is a conditional update, so a repeat is a no-op.

**Grants to a LOST host.** None: grants need UP (`Scheduler.tick`), and a LOST host is never UP until it recovers
(§2.5), which settles before it reconciles. A **deferred** host (§2.1, Q-M4-12) takes none either until its UP has
lasted `stableUp`; it keeps the units it has, and is not settled unless it leaves UP first.

### 2.4 The guarantee, stated per operation (R-M4-3)

- **Stored result:** **at most one stored result per unit.** Mechanism: a unit's terminal transition is an UPDATE made
  only after a check in Java (`PgUnitStore.current()`) under the unit row lock, which requires `host = $reporting host AND attempt = $reported attempt AND state` active
  (§4.3); testcases, manifest rows and the evidence row are written only in that same transaction, after the check;
  `results` and `testcases` are write-once (V1 trigger) [V].
- **Execution:** **a unit's steps run at most twice** (the first attempt plus one requeue, R-M4-13), on two host
  sessions, and the first attempt may run to its end on a host that comes back. Mechanism: the requeue is the only
  path that returns a started unit to QUEUED, and it requires `host_lost_requeues = 0` and sets it to 1; the
  column's CHECK (`BETWEEN 0 AND 1`, §6) makes a second requeue fail its transaction. Nothing a lost attempt does
  after its loss is stored; what it did to the outside world is not undone, which is why L2 exists.
- **Loss visibility:** every lost attempt that L2–L4 ends or requeues is a `unit_attempts` row and a `unit-lost`
  event; `GET /jobs/{id}` lists them per unit (§11). **L1 writes neither** (fix round 1): a CANCELLING unit ends
  CANCELLED with `killedBy = cancel`, because the cancel, not the loss, decided its end; the user asked for no result,
  and none can be stored (fencing, §4). The unit's own CANCELLED end is its record; a `unit-lost` row would read as an
  outcome the cancel had already superseded.
- **Spec amendment:** spec §6.4 ("at most once, visible loss") and the §13 non-goal line on execution count now read:
  *a unit on a host that stays connected or returns within `hostLostAfter` runs at most once; a unit on a LOST host
  runs at most `1 + 1` times, its result is stored at most once, and a unit with external side effects runs at most
  once and ends ERROR(host-lost) instead.* Architecture §3 "Leases" changes the same way (WP-7).

### 2.5 Recovery: when a LOST host is eligible again (R-M4-6, ruled by R-M4-16)

**Automatic** (ruling Q-M4-3), on the next handshake that completes:
1. ssh connects with the configured `knownHosts` (so the key matches, or ssh refuses: a recreated server stays LOST
   until the operator puts its new key in the file, which is the operator action that matters);
2. `Hello`/`Welcome` as today (protocol and build version equal);
3. **settle first:** the §2.3 settle runs to completion for the host (normally a no-op by then);
4. reconcile, which now **discards superseded attempts** (§4.5) before anything else on the host;
5. UP (`HostStore.recovered`): `hosts.state = 'UP'`, `lost_at`/`lost_reason` cleared and the mark kept in
   `cleared_lost_at`; **the UP write commits, then the flag clears** (until
   then the host takes no grant), and a log line `[host] recovered from LOST(<reason>)`. Only the mark the handshake
   saw is cleared; a loss declared during steps 3–4 closed the session and is settled, never cleared. A failed settle
   or UP write closes the session; the next handshake recovers again.

The settles and the UP write share one per-host write slot (`HostLink.hostWrites`), so no tick's mark or settle runs
between the UP write and the flag clearing, and a settle whose slot turn comes after the flag cleared does nothing.
Each slot step's store calls are bounded by the link's `request` timeout (30 s): a hung store fails the step, so the
next tick settles again and a recovery closes and retries, instead of the slot stalling settles and recovery for that
host for good.

**Late commits (fix round 4).** The bound fails the controller's promise, not the statement: a write it timed out can
still commit after the slot moved on. Memory alone cannot order that, so the store orders the two writes that matter:
- **A late `hosts.lost`** (it would make a recovered host LOST again: a restart would start it LOST, and the real settle
  guard, which reads `hosts.state = 'LOST'`, would requeue the units recovery reattached — a double run).
  `HostStore.recovered(host, seen)` clears the mark only while it is still `seen` and records it in
  `hosts.cleared_lost_at` (V12); `HostStore.lost` is refused (`StoreError.StaleLoss`) for a mark at or before the
  cleared one. A column is needed because a row before its first loss and a row after recovery are otherwise the same
  (not LOST, `lost_*` NULL); V11 is fixed, so it is V12.
- **A settle chained to a late mark write** never runs: each slot call is bounded before anything is chained to it, so a
  mark write that timed out never continues into `settleLost`, in the slot or out of it. (A settle that does run late
  is still refused unit by unit by the settle guard once the host is recovered.)
- **A late UP write** (it would leave the store recovered while memory is still LOST, and every later mark write,
  refused, would keep the host LOST in memory for good): a refused mark write clears the flag if it is still that mark —
  the store is the authority — so the next handshake is a healthy one. The recovery that wrote it had settled and
  reconciled, so nothing is lost by following it.
- `HostStore.state` (DOWN/CONNECTING/UP) never touches a LOST row, so a late link-state write cannot clear a mark
  either; only `recovered` leaves LOST.

Pinned by: the `lost` guard, `HostLossIT.lost_afterRecovery_refusedStale_hostStaysUp`; `recovered` keyed on the
seen mark, `HostLossIT.recovered_otherMark_refusedStale`; `state` keeps a LOST row, `HostLossIT.state_keepsLostRow`;
the per-call bound, `HostLostLinkTest.recovery_lateMarkAnswer_noSettleAfterUp`; the flag following a refusal,
`recovery_lateUpCommit_flagFollowsStore_nextHandshakeUp`; only while it is still the refused mark (a refusal of an
older mark arriving after a newer loss keeps the newer one), `staleRefusal_olderMark_newerLossKept`; only on a refusal
(a store failure keeps the flag), `lost_transientMarkFailure_flagKept`. The verifier's late-mark scenario end to end:
`recovery_lateMarkCommit_refused_storeStaysUp_noSettle`. Recovery writes UP through `recovered`, not `state`
(`recovery_settlesBeforeReconcile_storedLost`, "the mark cleared"). **Known limits:** the `lost` guard compares controller wall-clock marks: if the clock steps back by more
than the time between a recovery's mark and the next loss, that loss is refused as stale and the flag clears, so the
host's units are not requeued until a loss is declared after the clock passes the cleared mark. Meanwhile the host
behaves as before M4 (F5): it is down, so it takes no grant, and if it returns its reconcile reattaches its units. The
failure is a delayed requeue, never a double run. A timed-out settle that commits later requeues without the wake;
the scheduler's next tick picks the units up.

No operator command is required for an outage that heals; an operator who does not want the host back removes it from
`[[hosts]]` or sets `retireAt` in the past (§10) and restarts. (The rejected alternative was a sticky LOST cleared
by `temper host readmit <name>`.)

**The requeued unit is not barred from the host it was lost on** (ruling Q-M4-3): at the time of the requeue that host
is LOST and therefore ineligible; if it later recovers, fencing (§4) makes it as safe as any other host. A hard
exclusion list would strand a unit whose only eligible host (bigboy for `--dedicated-cpu`) is the one that lost it.

---

## 3. W1 state machine of a host link

The link's own states are unchanged (`LinkState`: Down, Connecting, Handshaking, Up, Stopped) [V]. LOST is a
**separate per-host flag** held by `HostLink` next to the state, because the link keeps reconnecting while the host is
LOST:

```
                 leave UP (or controller start)                       UP for stableUp
   healthy ───────────────────────────────────► down(since) ───────────────────────► healthy
                                                   │  now − since ≥ hostLostAfter
                                                   │  or ssh host-key failure
                                                   ▼
                                                 LOST ──(handshake + settle + reconcile + UP)──► healthy
   down(since) with the link UP again and now − since ≥ hostLostAfter: deferred (no grants)
       deferred ──UP for stableUp──► healthy        deferred ──leave UP──► LOST
```

- `hosts.state` gains the value `LOST` (written while the flag is set, whatever the link state); `GET /hosts` shows
  `state = LOST` and the link's own state in a new field `link` (§11).
- Pure decision (`controller/host/HostLiveness.java` **+**): `decide(downSince: Option<Instant>, now, lostAfter,
  hostKeyFailed, up) → Healthy | Down(since) | Deferred(since) | Lost(reason)`, with `up` the link UP on an open
  session. `Deferred` is the clock past `lostAfter` while UP (Q-M4-12, §2.1); `clock(downSince, upSince, now,
  stableUp)` moves the clock and `overdue(downSince, now, lostAfter)` is the no-grant test. Pure so that the boundary
  cases (exactly `lostAfter`, a flap shorter than `stableUp`) are unit-tested without clocks (§12.1).
- Who calls it: every liveness tick (`livenessCheck`) moves the clock and decides; a host-key failure decides at once;
  a link that leaves UP decides at once (Q-M4-12 (b)). While LOST the tick settles instead (§2.3, level).

---

## 4. W1: fencing superseded attempts (R-M4-4)

### 4.1 The attempt number

- `units.attempt int NOT NULL` (V11, §6): 0 for a unit never granted; **the `GRANT` statement sets `attempt =
  attempt + 1`** in the same UPDATE that sets `host` and `granted_at` (`PgUnitStore.GRANT`). Every grant is a new
  attempt, including the existing Q-11 requeue of a unit that never ran (`ReconcileAction.Requeue`), so the attempt
  number is "how many times this unit was granted" and only grows.
- `Granted` carries it; `UnitAttempt(ref, grantedAt)` becomes `UnitAttempt(ref, attempt)` (the F4-verification
  scoping by grant time becomes scoping by number; same property, an integer instead of an instant).

### 4.2 On the host (worker)

- `StartUnit.attempt` is written to a new unit file `.temper/attempt` **before** `marker` (so any unit with a marker
  has an attempt), and as the container label `temper.attempt=<n>`.
- Every worker → controller unit frame carries `attempt` (§7): `UnitHolding`, `UnitStarted`, `UnitStartFailed`,
  `UnitExited`, `UnitManifest`, `UnitReport`, the `Heartbeat` running list, `Failure.unit`, and the startup scan's
  `DiskUnitDto`.
- Every controller → worker unit request carries it: `CancelUnit`, `Reattach`, `Collect`, `LogTail`,
  `UnitReportAck`. The worker answers `Failure{stale-attempt}` when its unit dir holds another attempt.

### 4.3 In the store (the authority)

Every write about one unit attempt is conditional on `host = $h AND attempt = $a` under the unit row lock:

| Write (`PgUnitStore`) | Today's guard [V] | M4 guard |
|---|---|---|
| `START` (`startLocked`) | state ∈ {GRANTED, UNKNOWN, CANCELLING}, not started, `host = $h` | + `attempt = $a` |
| `START_UNKNOWN` | + `granted_at = $grantedAt` | `attempt = $a` replaces `granted_at` |
| `HOLDING` | state, not started, not holding | + `host = $h AND attempt = $a` |
| `LEASES` (heartbeat) | `host = $1` | + per-ref attempt (`job/unit/attempt` text key) |
| `complete` (`completeLocked`) | not QUEUED, not terminal; **no host check** (F2) | + `host = $h AND attempt = $a`, else new `CompleteOutcome.Superseded` |
| L1–L4 settle (§2.3) | — (new) | `host = $lost AND attempt = $a` |

`UnitStore.complete` gains `(HostName host, int attempt)` parameters. `ReconcileRun.finish` passes the store's own
attempt (it acts on the store's active rows). `WorkerEvents.collect`'s pre-transaction `ownership` read stays as an
early exit (it avoids pulling evidence for a report that cannot count) but is no longer the guard.

**`Superseded` is acked** [P]: `Settled.Superseded(attempt, current)`, its own outcome (WP-4 entry criterion (b)), acked
in the report's own attempt (the worker removes that attempt's container and network on `UnitReportAck`, and nothing
when it holds another attempt), nothing is written, an INFO line `stale report of <unit> attempt <a> (current <c>) discarded`. The
unit dir itself is removed by the next reconcile's discard (§4.5). This differs from today's `ELSEWHERE → IGNORED`
(not acked, container kept for reconcile): a superseded attempt has nothing left to reconcile.

### 4.4 The adoption guard (F3)

`UnitStarter.adoptedOrStarted` adopts an existing `temper-<job>-<unit>` container **only when its `temper.attempt`
label equals the request's attempt**. A pre-M4 unit is attempt 1 however it is found: a container without the label,
like a unit dir without `.temper/attempt`, reads as 1, as V11's backfill does in the store, so `StartUnit(1)` adopts a
running pre-M4 container. A container with a **lower** attempt is removed with its network and unit dir, logged WARN,
and the start proceeds [P]: attempt numbers only grow and the controller is the authority. A container with a
**higher** attempt than the request is refused with `UnitStartFailed(ERROR, "infra: stale-attempt")` [P]; it can only
arise from a restored database, and it must not be killed blind.

The guard runs before anything the worker process remembers of the unit (a cancel, a running `GrantWatcher`): that
state is per attempt, and a replace drops the old attempt's (its timer, exit watcher, hold and cancel) before removing
its container, so the old watcher never writes into, or reports from, the new attempt's dir.

### 4.5 Reconcile on return: discard superseded attempts

`Reconciler.decide` gains an input: for every disk unit the worker reports, the store's **current** attempt of that
unit and its host (a new read, `UnitStore.attempts(List<UnitRef>)` → `(ref, attempt, host, state)`). New rule 0, before
rules 1–6 [P]:

> a disk unit whose attempt is **not** the store's current attempt on **this** host (lower attempt; or the unit is now
> granted elsewhere; or terminal with a different attempt) → `ReconcileAction.Discard(ref, attempt)`.

`Discard` sends the new request `DiscardUnit{job, unit, attempt}`: the worker kills the container if it runs, removes
container and network, deletes the unit dir, then `Ack`. It never touches a unit dir whose attempt differs from the
request (an idempotent, attempt-scoped delete). It replaces today's `KillOrphan` for this case; `KillOrphan` remains for
units the store does not know at all. Reconcile runs before UP (`HostLink.reconciled → up`) [V], so **the host gets no
grant until every superseded attempt on it is gone**; a failed `Discard` fails the reconcile and the session reconnects
(existing semantics, `ReconcileRun.execute`) [V].

Implemented (WP-4b) [V]: the "current attempt on this host" test is `StoreAttempt.supersedes`: a disk attempt below the
store's, wherever the current one is, or equal to it once the store no longer has it on this host (a requeued unit has
no host). A disk attempt **above** the store's is discarded too, logged WARN with both attempts (team-lead ruling,
2026-10-26): only a grant the store lost (a restored database, a rolled-back transaction) leaves one, nothing in the
store owns or times its container, and its result could never be stored. This differs from §4.4's start-time refusal,
which only declines to *adopt* such a container. Discards run as a first phase, before every other action; a discarded unit is absent for rules 1–6, so the current
attempt that never reached this disk is decided as rule 6 decides a unit with no directory (`Requeue` when GRANTED and
never started, `ERROR(lost)` when started). `KillOrphan` stays for units the store does not know and for a running
container of a unit that ended in the same attempt here.

**The `stale-attempt` answer to a reconcile `Collect` or `Reattach`** (WP-4 entry criterion (c), decided in WP-4b) [P]:
after rule 0 it can only mean the host's disk changed after its `Hello` (a `StartUnit` of the previous session still
being served). The store's current attempt is not on that disk, so it
is settled exactly as rule 6 settles a unit with no directory (`Reconciler.notOnDisk`), never `ERROR(infra)`: nothing
failed in the infrastructure, and the unit's verdict must not say so.
- `Collect` (the host's attempt has exited): GRANTED and never started → `Requeue`. Reason: nothing of the current
  attempt ran there (its start writes `.temper/attempt` before the marker, replacing the lower attempt), so a requeue
  loses nothing; the exited older attempt can only be `Superseded` and the next reconcile discards it. Started →
  `ERROR(lost)`; CANCELLING and never started → CANCELLED. The reconcile goes on.
- `Reattach` (the host's attempt still runs, and no timer bounds it): the current attempt is settled the same way, then
  the action fails (`ReconcileError.StaleAttempt`), so the session reconnects and the fresh scan's rule 0 discards the
  running attempt before the host goes UP. Reason: settling alone would leave an untimed container running on an UP
  host until some later reconnect.
Tests: `HostLinkTest.Fencing` (each case), `ReconcilerRuleZeroTest` (`notOnDisk`). The `Requeue` it relies on is
attempt-scoped like `REQUEUE_LOST`: `UnitStore.requeue(host, ref, attempt)` changes only a unit GRANTED, never started,
on that host in that attempt (`StaleAttemptStoreIT`); reconcile passes its snapshot's attempt.

### 4.6 Late frames, one by one

| Late frame from attempt *n* (current *n+1*) | Where it stops |
|---|---|
| `UnitStarted` / `UnitHolding` | a `UnitStarted` of another attempt than the one the link knows current is dropped before it touches any per-unit start state (`WorkerEvents.recordStart`, WP-4b); behind it, and for `UnitHolding`, the store guard `attempt = $a` (§4.3); logged, no row changes |
| `Heartbeat` running list | `LEASES` matches no row for `(ref, n)` |
| `UnitExited` + manifest + report chunks | `WorkerEvents.ownership` (early) and `complete`'s guard → `Superseded`, acked, nothing stored |
| `Failure{lost}` for the unit | `endedLost` → `complete` guard → `Superseded` |
| evidence pull | only after a `Completed` outcome is impossible for *n*; the controller's evidence path gains the attempt, `jobs/<job>/units/<unit>/a<n>/` (`ResultRecords.jobDirectory` reads 4 levels instead of 3) [P], so even a pull racing a requeue cannot overwrite attempt *n+1*'s file. `worker-launch`'s `evidence <job> <unit> small\|full` verb is unchanged [V]: after §4.5 only the current attempt's dir exists on a host |

---

## 5. W1: units with external side effects (R-M4-5)

### 5.1 Where the property lives

- **On the recipe** [P]: `Recipe` gains `SideEffects sideEffects()` (enum `NONE`, `EXTERNAL`), **abstract on the
  sealed interface, no default**, so every present and future recipe kind must declare it to compile.
  - `Gate`, `ModuleTest`, `Rerun` → `NONE` (F10).
  - `Unsupported` → `NONE` (it is refused at submit and never planned).
  - `Mvn` → `NONE` **only when every phase, goal and `-P` profile of its argv, and every option of the user's argv
    (Q-M4-11d), is on the build-only allowlist** (maintainer ruling Q-M4-11, which replaced revision 2's deny list); anything
    else is `EXTERNAL`. Fail-safe: an unknown plugin, phase, profile or option is external, publishing or not. Goals are judged in prefix (`jar:jar`) and coordinate
    (`groupId:artifactId[:version]:goal`) form, coordinates matched on `groupId:artifactId`, with `@execution`
    stripped first (`site:deploy@x`, `jib:build@x` are external; `surefire:test@x` is not).
  - **The allowlist is data**: `temper-domain/src/main/java/org/pragmatica/temper/domain/mvn/SideEffectAllowlist.java`
    holds the list as a private text block and its parse, nothing else: a pure parse into a
    `Result<SideEffectAllowlist>` (phases, plugins, profiles), done once in the JVM that plans (the controller).
    `domain/mvn/SideEffectRule.java` only queries the parsed value and never reads the text (maintainer condition 1, fix
    round 2). Not a classpath resource: `temper-domain` is pure (`jbct.toml`: no I/O imports, no dependency on a
    `read/` adapter package), and reading a resource there would need the allowlist threaded through
    `Recipe.sideEffects()`, `Planner` and `Refusals` from the controller [G, fix round 1]. Lines: `phase <name>`,
    `plugin <prefix> <groupId:artifactId> <goal,goal,...>`, `profile <name>`; blank and `#` lines are skipped.
    **A malformed line fails the parse, and a failed parse makes every argv `EXTERNAL`**, the parse error in the
    R-EFFECT detail; there is no "every goal" form (`*` is malformed), so a goal a plugin gains later is external until
    judged. `SideEffectRuleTest.shippedAllowlist_parses` fails the build on a bad edit. Content [judgement recorded in
    the file's header; a goal is listed when it writes only inside the workspace and local repository and reaches the
    network only to resolve artifacts]: phases `pre-clean`…`post-clean` and `validate`…`install` (so `verify`; not
    `deploy`, not the site lifecycle); plugin goals clean `clean`; compiler `compile`, `testCompile`; resources
    `resources`, `testResources`; surefire `test`; failsafe `integration-test`, `verify`; jar `jar`, `test-jar`;
    source `jar`, `test-jar`, `jar-no-fork`, `test-jar-no-fork`; javadoc `jar`, `test-jar`, `javadoc`,
    `test-javadoc`; install `install`; dependency `tree`, `list`, `resolve`, `resolve-plugins`, `go-offline`,
    `analyze`, `analyze-only`, `build-classpath`, `sources`, `properties`; help `effective-pom`, `active-profiles`,
    `all-profiles` (`describe`, `evaluate` dropped, verify3 M1); enforcer `enforce`, `display-info`; jbct `check`, `format`,
    `format-check`, `lint`, `score`; jacoco `prepare-agent`, `prepare-agent-integration`, `report`,
    `report-integration`, `report-aggregate`, `check`, `merge`; pitest `mutationCoverage`, `report`,
    `report-aggregate`, `report-aggregate-module`; profiles `acceptance` (temper's own tier) and `with-e2e`
    (pragmatica). So external among listed plugins: `dependency:purge-local-repository`, `copy`, `unpack`,
    `copy-dependencies`, `unpack-dependencies`, `get`, `add`, `remove`; `jbct:deploy-slices`, `install-slices` and
    the other slice goals; `jacoco:dump` (a TCP connection to a running agent); `javadoc:aggregate*`;
    `help:system`, `help:effective-settings`. Not listed, so refused: deploy, release, scm, wagon, site, jib,
    docker, dockerfile, gpg (signing needs a key the hosts must not hold), nexus-staging, central-publishing,
    scm-publish, jreleaser, cargo, k8s, invoker, versions; `exec`/`antrun` are R-SHELL first (F10). Names are
    case-sensitive, as Maven's are (`DEPLOY`, `Install` are external). `SideEffectRule` lives in `domain/mvn`, not
    `domain/plan` (package cycle, `.wp-state.md` D1). Since R-M4-14 an `EXTERNAL` `Mvn` is refused at submit (§5.3),
    so an accepted `Mvn` unit is always `NONE`.
- **The planner copies it onto each `UnitSpec`** (`sideEffects` field), and `INSERT` of units writes
  `units.side_effects`. The store decides L2 from the **unit row** (never from re-parsing the recipe), so a settle needs
  no domain code in SQL.
- **Default for existing rows: `external`** (§6), so a pre-M4 unit active at deploy time is never requeued: fail-safe.
  New inserts must name the value (the column has no default after the backfill).

**Honest limit of the argv classifier.** It reads the argv only. A publishing goal bound by the project's pom, or by an
allowlisted profile, to an allowed phase (`deploy:deploy` bound to `install`, a `jib:build` execution in `package`), and
a pom `defaultGoal`, are not seen. Containment there is structural, not the classifier's: the build hosts hold no
publishing credentials (no `settings.xml` servers, registry logins, GPG keys or tokens; audited 2026-10-26), so such a
goal fails rather than publishes.

**`-D` properties (maintainer rulings Q-M4-11b, Q-M4-11c).** Every `-D` property of the **user's own** `Mvn` arguments must be
on the allowlist (`property <name> skip|boolean|selector`): exact names only, never a prefix (`surefire.*` would let in
`surefire.reportsDirectory`), and only keys that take a selector or a boolean, never a path or an address; a `skip` or
`boolean` key takes `true`/`false` or no value. All define forms are read (`-Dk=v`, `-Dk`, `-D k=v`, `--define k=v`,
`--define=k=v`, through `MvnStep`). Any other user property is `EXTERNAL`, the detail naming it (`property 'release' is
not on the build-only allowlist`), so `-Drelease=true` and `-Doutput=/x` are refused at submit. List (Q-M4-11c):
`skipTests`, `maven.test.skip`, `skipITs`, `ember.skipTests` (`skip`: skip tests when true); `test`, `it.test`, `groups`,
`excludedGroups`, `targetClasses`, `targetTests` (`selector`: narrow the tests run); `failIfNoSpecifiedTests`,
`surefire.failIfNoSpecifiedTests`, `it.failIfNoSpecifiedTests` (`boolean`). Refused: `jbct.skip` and
`maven.test.failure.ignore`, which turn a gate green while examining nothing (the rerun's own
`-Dmaven.test.failure.ignore=true`, `Planner.RERUN_SELECTION`, is a planner token and is not judged);
`additionalClasspathElements`, because classpath jars are code the build executes, taken from outside the workspace (to
be allowed later only for paths the planner resolves inside the workspace); `argLine`, `output`, `mdep.outputFile`,
report directories; `maven.repo.local*` stays R-M2's (Refusals' R-M2 check runs first). Each judgement is in the file's
header.
**Values a result line can show (verify4 B1).** A user property's value must hold no control (C0, DEL, C1), whitespace,
space-separator or format (bidi, zero-width) character, or the property is `EXTERNAL`, the detail naming the key, not
echoing the value (`property 'test' has a control, whitespace or format character in its value`). It sits in R-EFFECT,
not in `MvnArg` or another argv refusal, because R-EFFECT is the one rule that judges the user's property values (the
allowlist's kinds) and the values it accepts are exactly those the views show; `MvnArg` judges any argument and bars only
NUL. The second end is the render: every surface prints a property through `UserPropertyDto.token`, which always quotes
the shown text and escapes it (`LineText.quoted`: `\\`, `\"`, `\n`, `\r`, `\t`, `\uXXXX` for every other control,
format or separator character), so a recipe stored before this refusal still prints on one line and forges none.
**Options (maintainer ruling Q-M4-11d).** The user's options are under the same fail-safe allowlist as goals, profiles and
properties: `option <short> <long> <shape> [narrows]` lines of `SideEffectAllowlist`, read by `UserOptions`, judged by
`SideEffectRule`. An option is only an exact listed spelling, its short or its long form as one argv element; its value,
when its shape takes one, is the next element. Every other element of the user's argv that starts with `-` is
`EXTERNAL`, the refusal naming the token as written (`option '-pl=a' is not on the build-only allowlist`), ahead of any
goal its would-be value reads as. So single-dash long names (`-fail-never`, `-projects a`, `-resume-from b`, `-file x`,
`-non-recursive`, `-define k`, `-activate-profiles x`), `=` forms (`--fail-never=true`, `-pl=a`, `-f=a/pom.xml`,
`-rf=b`, `--define=k=v`, `--projects=a`) and attached values (`-fa/pom.xml`, `-T4`) are refused. Real Maven 3.9.12
honours every one of these (checked on cloudbb-3: `-fail-never` and `--fail-never=true` exit 0 on a failing build;
`-pl=a`, `-f=…`, `-fa/pom.xml`, `-rf=b`, `-non-recursive`, `-projects`, `-resume-from`, `-file` narrow the reactor;
`-define skipTests` skips the tests; `-activate-profiles acceptance` activates the profile), which is why a parser that
drops an option it does not know cannot be the judge. `MvnStep` still skips such an element without consuming a value
(so it never hides a goal); skipping is not accepting. The list, with the shape of each value:
- none: `-B`/`--batch-mode`, `-e`/`--errors`, `-X`/`--debug`, `-q`/`--quiet`, `-o`/`--offline`,
  `-U`/`--update-snapshots`, `-V`/`--show-version`, `-nsu`/`--no-snapshot-updates`, `-npu`/`--no-plugin-updates`
  (ineffective in 3.9.12 but still accepted by it, so kept), `-C`/`--strict-checksums`, `-c`/`--lax-checksums`,
  `-am`/`--also-make`, `-amd`/`--also-make-dependents`, `-fae`/`--fail-at-end`, `-ff`/`--fail-fast`;
  `-N`/`--non-recursive` (narrows).
- `threads`: `-T`/`--threads`, an integer from 1 or `<n>C` (`[1-9][0-9]*C?`; Maven's `1.5C` is refused).
- `modules`: `-pl`/`--projects` (narrows), a comma list of module selectors, each optionally `!`-prefixed. A selector
  is `:artifactId`, `groupId:artifactId` or a relative module directory; a name is `[A-Za-z0-9_.-]`, starts with
  `[A-Za-z0-9_]` and never holds `..`; a directory is names joined by `/`. So no leading `/`, no `.` or `..` segment, no
  empty entry, no `-x` exclusion (the `-`-prefixed form Maven also takes) and no `?` optional prefix (Maven 4).
- `module`: `-rf`/`--resume-from` (narrows), one selector of that grammar, no `!`.
- `file`: `-f`/`--file` (narrows), a relative path of such names, given at most once (verify6: Maven builds the first
  `-f`, while `MvnStep` judges module inclusion against the last, so a repeat is refused, the offence naming it,
  rather than given a position meaning). Rule: the planner does not prefix it; the unit's cwd
  is its workspace (`DockerRunArgs` runs the container with `-w <ws>`, the effective-POM container too), and a path of
  names with no `..` segment and no leading `/` cannot leave the directory it resolves from, so it resolves inside the
  workspace. A symlink in the repository is the repository's own content, as for every other file it builds.
- `profiles`: `-P`/`--activate-profiles`, judged by the `profile` lines; `define`: `-D`/`--define`, judged by the
  `property` lines. These two also take the value attached to the short form (`-Pacceptance`, `-Dk=v`): the reason
  for allowing an attached value is that it is Maven's documented and near-universal form for these two, every recipe
  and test of the repositories temper builds writes it so, and the attached value is judged by the same profile and
  property lists as a separate one. No other option takes an attached value.
Refused (not listed): `-fn`/`--fail-never` (its offence says `option '-fn' makes a failing build exit 0`, on every argv,
the planner's included; Maven 3.9.12 rejects abbreviations such as `--fail-n`, and they are unlisted, so refused),
and a single-dash long name whose value is a phase name (`-define verify`, `-activate-profiles install`: a parser
that drops the option reads its value as an allowed phase), `-s`/`-gs`
(`--settings`/`--global-settings`, R-M2 refuses them first), `-t`/`--toolchains`, `-gt`/`--global-toolchains`,
`-l`/`--log-file`, `-emp`/`-ep` (encryption), `-b`/`--builder`, `-ntp`, `--color`, `-itr`, `-llr`, `-cpu`, `-up`,
`-npr`, `-h`, `-v`, and every option Maven adds later. Why the failing-build ones matter: with `-fn` Maven exits 0
after a failed `jbct:check` or a module that failed to compile and whose tests never ran, the class Q-M4-11c refused
`jbct.skip` and `-Dmaven.test.failure.ignore` for. `-fae` still exits non-zero after building the modules that do not
depend on a failed one; `-ff` is the default. `-e`, `-X`, `-o`, `-U`, `-T`, `-B`, `-V`, `-nsu`, `-npu`, `-C`/`-c`,
`-am`/`-amd` change output, network or threading or widen the reactor; none turns a failing build green.
- `-q`/`--quiet` (maintainer check, 2026-10-26): accepted, because no verdict, count or summary is read from the Maven log.
  The verdict's inputs are `UnitOutcome` (`UnitOutcome.java:12`): the container's exit code (`DockerCli.java:88`,
  written to the unit file first, `UnitFile.java:20`), the kill cause, void/error reasons, and the testcase rows and
  `tests` attribute parsed from `target/{surefire,failsafe}-reports/TEST-*.xml` (`ReportCollector.java:37-41`); EMPTY
  and FAILED(rc) are judged from rc and those rows (`Verdicts.java:30-33`). A grep of every `src/main` for `BUILD
  SUCCESS`, `Tests run:`, `checked N files`/`Check results`, `Running JBCT` and `Reactor Summary` finds only
  `temper-acceptance`'s sizing report (`ReactorSummary.java:22`, per-module wall time for a sizing table, no verdict).
  jbct's "checked N files" line is never read by temper; a failed `jbct:check` reaches the verdict as rc ≠ 0. The log
  is served only as a tail (`temper log`). So `-q` blanks only what a human reads. `-Dorg.slf4j.simpleLogger.*` is off
  the property list, so already refused.
**Accepted skips and selections are shown (Q-M4-11c, verify4 B2, m1).** Every `-D` property the user wrote is shown on
the job's result, in argv order, a `skip` key set true (or with no value) marked *skipped* and a `selector` key marked
*narrowed*; among them, in the same argv order, every option the list marks `narrows`, with its value, marked
*narrowed*: `-pl`/`--projects` (selections and `!x` exclusions alike), `-rf`/`--resume-from`, `-f`/`--file` (a sub-pom
builds only its subtree) and `-N`/`--non-recursive` (`UserOptions` over the parsed list, `UserProperties`); and every
element starting with `-` that is no listed spelling, as written, marked *unknown* (`tests-unknown="-pl=a"`): a job
stored before Q-M4-11d, or any job while the list does not parse (no option is listed then). So a green run with tests
skipped or narrowed by a property or by these options, or run under an option temper does not know, never reads as
tested; narrowing by anything else (a profile's modules, the
repository's own `.mvn/maven.config`) is the known limit below. Derived from the stored recipe whenever a view is built
(no column, no migration; a record written before the field shows it too): the controller's `GET /jobs/{id}`,
`GET /jobs` and `GET /jobs/{id}/result` JSON carry `userProperties`
(`[{"define": "-DskipTests=true", "tests": "skipped"}, {"define": "-Dtest=FooTest", "tests": "narrowed"},
{"define": "-DfailIfNoSpecifiedTests=false", "tests": null}, {"define": "-pl core", "tests": "narrowed"}]`; `define` is
the argument as shown, a narrowing option included, so the wire shape is unchanged), as do the digest's `finished`
events. Every one-line surface ends the job's facts with the same tokens, value always quoted:
`tests-skipped="-DskipTests=true" tests-narrowed="-Dtest=FooTest" property="-DfailIfNoSpecifiedTests=false"
tests-narrowed="-pl core"`, on `temper result`, `temper wait`, the not-yet-terminal `result`/`wait` line, `temper jobs`,
the digest text's `finished`/`cancelled` lines, and every `temper watch`/subscriber event line that states a verdict
(`unit-finished`, `finished`, `cancelled`; `EventFeed` reads the job's recipe when it delivers the event, so the
subscriber JSON's `line` carries them too; a job removed by retention shows none). Liveness (Q-M4-11d): when that read
fails (a store error, or a stored recipe that does not decode), the line carries the fail-safe mark
`tests-unknown="recipe-unreadable"`, the controller logs a WARN naming the job and the event (`EventFeed`; no operator
event or alarm channel in the controller fits a per-event read failure: `temper alarm` judges dumps, disk and the
controller), and the event is delivered as usual, so the cursor moves past it and one unreadable job never stalls the
feed. A transient database error on that lookup is treated the same way: the line is marked `tests-unknown`, not
retried (verify6 agreed): `ops/post-gate-status.py` reads the job record, not the watch line, so the only cost is a
spurious fail-safe mark on one delivered line. `ops/post-gate-status.py` refuses to post a `temper/gate` status for a job with any marked entry (`skipped`,
`narrowed` or `unknown`). Surfaces that need nothing: the alarm (`temper alarm`
judges dumps, disk and the controller, never a job), its GitHub issue (the alarm's text), `temper gate` (Gate
candidates are `recipe_kind = 'gate'`, a typed recipe with no user arguments), `temper diff` (its `a=`/`b=` verdicts sit
beside the test sets: the tests a skipped or narrowed side did not run are listed and counted as `removed`/`added`). A skip key set false, a `boolean` key and a key a job stored before the list carried are
shown as `property=`. A key given twice is marked per occurrence though Maven applies the last (`-DskipTests
-DskipTests=false` is shown skipped): it errs toward marking and is left so (verify4 m3). Only the `mvn` recipe carries
user arguments; the typed recipes' `-D` tokens and `-pl` are the planner's and are not shown.
**File options (verify3 M3, Q-M4-11d).** `-l`/`--log-file`, `-t`/`--toolchains` and `-gt` are now refused (not
listed); `-f`/`--file` takes only a workspace-relative path (above) and is shown narrowed. Containment beyond the
argv stays structural: the unit holds the docker socket and is root-equivalent on its host (m3 F1, the container
bind-mounts the workspace and the socket, `DockerRunArgs.java:22`), so no path rule is a sandbox; no outside code runs
on bigboy, and the cloud hosts hold no credentials. `-dskipTests` (lowercase) is an unlisted option, refused (Maven
3.9.12 rejects it too). Dropped from
`help` (verify3 M1, maintainer-confirmed): `evaluate` and `describe`, which need an unlisted property (`-Dexpression`,
`-Dplugin`) and could print `${settings.servers…}` into the log; `effective-pom`, `active-profiles` and `all-profiles`
run without one and stay.
**Scope: the user's argv only.** The user's arguments and the planner's tokens are joined in `Planner.step`
(`Planner.java`, `mvn -B <args> -Dmaven.repo.local=${ws}/.m2-local`); an `Mvn` unit has no effective-POM step
(Q-M1-8), and the typed recipes' effective-POM step (`pomArgv`, `-Doutput=…`) and `-D` tokens (`-DskipTests`, `-Dtest`, `-Dit.test`, the rerun's tolerance switches)
are the planner's too. So `SubmitShape` carries the user's `Mvn` arguments (`SubmitShape.userArgs`, taken from
the recipe by `SubmitShape.submitShape`), and R-EFFECT judges properties there, before the join; goals, phases and
profiles are still judged on every planned argv (`SideEffectRule.plannedStepOffence`).

**Known limits, beside the pom bindings.** The repository's own `.mvn/maven.config` and `.mvn/jvm.config` (Maven adds
their arguments to every run; temper never parses them), profiles the pom activates by default (`activeByDefault`) or by
a file, and what the pom binds to a listed phase are not seen. Containment for all of them is structural: no outside code
runs on bigboy, and the cloud hosts hold no credentials.

**Honest limit.** The property is a declaration plus a classifier. Every unit has the docker socket and is
root-equivalent on its host (m3 F1); a `ModuleTest` whose test code pushes to GitHub is classified `NONE` and would be
requeued. No present test suite does that [U: pragmatica's tests were not audited for network writes].

**Why both the property and the refusal (R-M4-14).** The refusal closes the only way a submit can create an external
unit today. `Recipe.sideEffects()` stays abstract with no default, the `external` backfill stays, and settle rule L2
stays with its tests, because they cover pre-M4 rows and any future recipe kind that is external by design (an M3b
push or PR verb would be one).

### 5.2 How the tests exercise both ways

- Domain: `SideEffectRuleTest`, a table of argv → `NONE`/`EXTERNAL` (`deploy`, `clean deploy`,
  `org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy`, `release:perform`, `site:deploy`, `install`,
  `verify`, `jbct:check`, `pitest:mutationCoverage`, `gpg:sign`).
- Domain: `RefusalsTest` + R-EFFECT rows (§5.3).
- Compile-time: `Recipe` has no default, so a new kind without `sideEffects()` does not compile (shown by the type,
  not a test).
- Store IT (`HostLostSettleIT`): two units on one host, one `none`, one `external`; declare LOST; the first is QUEUED
  with `host_lost_requeues = 1`, the second is `ERROR(host-lost)` with no new attempt.
- End-to-end (`SideEffectNotRequeuedIT`, §12.2): **a unit row inserted at the store level with
  `side_effects = 'external'`**, the shape of a pre-M4 row after the V11 backfill, granted and started on host A
  through the real store calls; A is made LOST. Chosen over a test-only recipe because `Recipe` is a sealed interface
  in production code: a test-only kind would have to ship in `temper-domain`, and it would also be a submittable kind
  unless guarded, which is the opening R-M4-14 closes. The row is exactly what L2 exists for today.

### 5.3 R-EFFECT: refusing an external `Mvn` at submit (R-M4-14)

- **+** `RefusalCode.R_EFFECT("R-EFFECT")`; **+** `Refusals.effect(List<UnitSpec>)` judged by
  `SideEffectRule`'s allowlist (§5.1, ruling Q-M4-11) over each step's parsed `MvnStep`
  (`refuseIf(firstOffence(argvs(units), …), R_EFFECT)`), the detail naming the step and the first phase, goal or
  profile off the list (`phase 'deploy' is not on the build-only allowlist`, then `goal 'release:perform' …`, then
  `profile 'x' …`).
- It goes **into `Refusals.checkSubmit`'s list after `shell(...)`** (`Refusals.java:38-46`) [V: the list is the single
  place submit refusals are composed, and its order decides which code wins]. So `mvn exec:exec deploy` is `R-SHELL`,
  `mvn clean deploy` is `R-EFFECT`. The refused job is recorded like every refusal (`jobs.state = 'REFUSED'`,
  `refusal_code`), never planned to a unit row.
- Judged over the planned `UnitSpec`s like R-M2 and R-SHELL, so the planner's own steps (Gate, ModuleTest, Rerun)
  are judged too, and a planner change that ever emitted `deploy` would be refused, not run.
- The argv-only limit of §5.1 applies: R-EFFECT refuses what the argv names, not what the pom binds.
- Tests (fix round 1): `SideEffectRuleTest` (refused: `gpg:sign-and-deploy-file`, `nexus-staging:deploy`,
  `nexus-staging:release`, `central-publishing:publish`, `scm-publish:publish-scm`, `dockerfile:push`,
  `docker:build -DpushImage`, `jreleaser:*`, `cargo:deploy`, `k8s:apply`, `invoker:run`, `site:deploy@x`,
  `jib:build@x`, unlisted profiles; accepted: `surefire:test@x`, `-Pacceptance`, `-P with-e2e`, the planner's steps;
  fix round 2: `dependency:purge-local-repository` and the other unlisted goals of listed plugins, `javadoc:aggregate`,
  `exec:exec`, an unlisted plugin, `-Prelease`, `-Pacceptance,release`, `DEPLOY`, `Install` external; a malformed
  allowlist line fails the parse and every step is external; the shipped list parses; Q-M4-11b: `-Drelease=true`,
  `-Doutput=/x`, `-Dsurefire.reportsDirectory=/x`, a non-boolean value of a boolean key, every define form external;
  listed keys, a bare boolean and the planner's own `-Dmaven.repo.local`/`-Doutput` accepted, through
  `RefusalsTest$Effect` and the full submit path in `SubmitJobTest`; `ControllerIT` rows `-Drelease=true`,
  `-Dsurefire.reportsDirectory=/x`; Q-M4-11c: `-Djbct.skip`, `-Dmaven.test.failure.ignore` and
  `-DadditionalClasspathElements` external naming the key, the shipped keys with their kinds, `UserPropertiesTest`'s
  marks, `UserPropertiesViewTest` (job and result JSON) and `UserPropertiesAgainstControllerTest` (`result`, `wait`,
  `result --json`, a pending `result`, `jobs`; none shown without a property), `DigestTextTest`); verify4:
  `RefusalsTest$Effect` rows for `-fn`, `--fail-never` (refused naming the option; abbreviations unlisted since Q-M4-11d) and
  `-fae`, `--fail-at-end`, `-ff`, `--fail-fast` (accepted), one row per unprintable code point in a `-Dtest`
  value (newline, CR, tab, space, NBSP, DEL, U+0085, ESC, U+2028/2029, U+202E, U+200B, U+FEFF) and every define form;
  `UserPropertiesTest` narrowing rows (`-pl`, `--projects`, `-rf`, `--resume-from`, `-f`, `--file`, `-N`,
  `--non-recursive` in argv order, a `-T -N` value not read as one); the escape on every surface: `DigestTextTest` (a stored newline,
  quote, ESC, bidi and backslash stay on one finished line), `EventLinesTest`, `EventFeedTest` (a delivered finished line
  of an `mvn` job carries the marks, a `queued` line and a job not stored none), `UserPropertiesAgainstControllerTest`
  (a stored newline prints one line; `-pl core` shown); `ops/test-post-gate-status.sh` (skipped, narrowed, unknown
  refused); Q-M4-11d: `SideEffectRuleTest` `options_offTheList_areExternal_namingTheToken` (every verifier bypass,
  `-s`/`-gs`/`-l`/`-t`/`-gt`/`-emp`/`-ep`/`-b`/`-ntp`/`--color` and the rest, `-`, `--`), `options_onTheList_areNone`
  (every listed spelling and value shape), `options_valueOfTheWrongShape_isExternal_namingTheOption` (`-T` values,
  the selector grammar for `-pl`/`-rf`, the path rule for `-f`), `options_valueMissing_isExternal`,
  `options_valueIsConsumedAsMavenDoes_soTheGoalAfterItIsJudged`, `shippedAllowlist_optionsAreTheRuledOptionsWithTheirShapes`,
  malformed `option` lines; `RefusalsTest$Effect` `-fail-never`, `--fail-never=true`, `--fail-`, `--fail-n`, `--fail-neve`, `-pl=core`, `-ntp`;
  verify5 gap rows `-f=x`, `-define verify`, `-activate-profiles verify` (phase-named values);
  `UserPropertiesTest` unknown marks (the bypasses as stored recipes; every option when the list does not parse);
  `EventFeedTest.next_marksAnUnreadableJobsVerdictLine_unknown_warnsAndMovesOn` (the mark, the WARN naming the job, the
  next event delivered);
  `ControllerIT.refusedSubmit_rEffect_forArgvOffTheAllowlist`, one row per refused goal through the real store.
- Tests: `RefusalsTest` rows: `mvn deploy` → R-EFFECT; `mvn -B clean deploy -DskipTests` → R-EFFECT;
  `mvn exec:exec` → R-SHELL (order); `mvn install` → accepted; a Gate and a ModuleTest plan → accepted.
  `controller/api/SubmitJobTest` (existing submit-path test [V]) + one row: a submit of `mvn deploy` answers the
  refusal and records `REFUSED(R-EFFECT)`. Probe P17.

---

## 6. Schema: V11 (`V11__m4_hosts.sql`)

```sql
-- hosts: LOST, retirement, CPU class (§2, §9, §10). Existing rows: state unchanged, shared_cpu false (bigboy is
-- dedicated), retire_at null, lost_* null.
ALTER TABLE hosts DROP CONSTRAINT hosts_state_check;
ALTER TABLE hosts ADD CONSTRAINT hosts_state_check CHECK (state IN ('DOWN','CONNECTING','UP','LOST'));
ALTER TABLE hosts
  ADD COLUMN shared_cpu boolean NOT NULL DEFAULT false,
  ADD COLUMN retire_at timestamptz,
  ADD COLUMN lost_at timestamptz,
  ADD COLUMN lost_reason text CHECK (lost_reason IN ('link-down','host-key')),
  ADD CONSTRAINT hosts_lost_pair CHECK ((lost_at IS NULL) = (lost_reason IS NULL));

-- units: attempt fencing (§4), requeue bound (§2.3, R-M4-13: one requeue), side effects (§5), CPU class of the
-- current attempt (§9.4). Existing rows: attempt 1 for a unit ever granted, else 0; no requeues; side_effects
-- 'external' (fail-safe: an active pre-M4 unit is never requeued); shared_cpu false where granted (bigboy was the
-- only host), else null.
ALTER TABLE units
  ADD COLUMN attempt int NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  ADD COLUMN host_lost_requeues int NOT NULL DEFAULT 0 CHECK (host_lost_requeues BETWEEN 0 AND 1),
  ADD COLUMN side_effects text NOT NULL DEFAULT 'external' CHECK (side_effects IN ('none','external')),
  ADD COLUMN shared_cpu boolean;
UPDATE units SET attempt = 1, shared_cpu = false WHERE granted_at IS NOT NULL OR started_at IS NOT NULL;
ALTER TABLE units ALTER COLUMN side_effects DROP DEFAULT;

-- The lost attempts (§2.3, §2.4), write-once.
CREATE TABLE unit_attempts (
  unit_id bigint NOT NULL REFERENCES units(id), attempt int NOT NULL CHECK (attempt >= 1),
  host text NOT NULL REFERENCES hosts(name), granted_at timestamptz NOT NULL, started_at timestamptz,
  ended_at timestamptz NOT NULL, shared_cpu boolean NOT NULL,
  outcome text NOT NULL CHECK (outcome IN ('host-lost')),
  PRIMARY KEY (unit_id, attempt));
CREATE TRIGGER unit_attempts_write_once BEFORE UPDATE OR DELETE ON unit_attempts
  FOR EACH ROW EXECUTE FUNCTION temper_write_once();

-- unit_facts per attempt (F4). Existing rows belong to attempt 1.
ALTER TABLE unit_facts ADD COLUMN attempt int NOT NULL DEFAULT 1;
ALTER TABLE unit_facts DROP CONSTRAINT unit_facts_pkey;
ALTER TABLE unit_facts ADD PRIMARY KEY (unit_id, attempt, phase);

-- jobs: the dedicated-CPU request (§9.2). Existing rows: false.
ALTER TABLE jobs ADD COLUMN dedicated_cpu boolean NOT NULL DEFAULT false;

-- events: the unit-lost kind (§2.3).
-- (the CHECK is re-created with the current list plus 'unit-lost'; the coder reads the current list from the
--  latest migration that redefined events_kind_check [U])
```

- **The bound is in the database:** `host_lost_requeues BETWEEN 0 AND 1`. A code path that requeued a second time
  fails its transaction instead of looping (§12.4 probe P4).
- **The rerun bound's filter column is `units.shared_cpu`** (§9.4): the completing attempt is the unit's current
  attempt, so the snapshot on the unit row describes the host that produced its `testcases` rows.
- **`side_effects` has no default after V11**, so an `INSERT INTO units` that forgets it fails at once.
- The `UPDATE units SET attempt = 1 …` predicate is [G]: "ever granted" is `granted_at IS NOT NULL OR started_at IS
  NOT NULL`; only `REQUEUE` clears `granted_at`, and only for a never-started unit (`PgUnitStore.REQUEUE`) [V], so an
  active unit is always caught [U: that no completion path clears `granted_at`]. A wrong 0 on a terminal unit is
  harmless: nothing fences a terminal unit again.
- Retention (`PgRetentionStore`) must delete `unit_attempts` rows with their units [U: the retention path's table list].
- Index: none new; `units_host_active` already serves the settle's `WHERE host = $1 AND state IN …` [V].
- **V12 (`V12__m4_host_recovery.sql`, WP-4a fix round 4):** `hosts.cleared_lost_at timestamptz`, the mark recovery last
  cleared; existing rows NULL. It lets `HostStore.lost` refuse a mark that commits after recovery (§2.5 "Late commits").
  **Version collision:** the unmerged `m3a-wp4` branch also adds a `V12` (`V12__m3_ci.sql`); whichever of the two
  lands on `main` second renumbers its migration to the next free version.

---

## 7. Protocol v3 (worker frames)

`Handshake.PROTOCOL_VERSION` 2 → 3 (F13). Worker and controller are always the same build (`Handshake.compatible`
requires equal versions) [V], so there is no mixed-version window to design for.

| Message | Change |
|---|---|
| `StartUnit` | + `int attempt` |
| `UnitHolding`, `UnitStarted`, `UnitStartFailed`, `UnitExited`, `UnitManifest`, `UnitReport` | + `int attempt` |
| `UnitRefDto` (in `Heartbeat.running`, `Failure.unit`) | + `int attempt` |
| `DiskUnitDto` | + `Option<Integer> attempt` (absent: a unit dir without `.temper/attempt`, i.e. pre-M4 → read as attempt 1 [P]) |
| `CancelUnit`, `Reattach`, `Collect`, `LogTail`, `UnitReportAck` | + `int attempt`; the worker answers `Failure{stale-attempt}` when it holds another (its dir's attempt; with no dir, the container's label); a `CancelUnit` above the held attempt is remembered for its start; `UnitReportAck` of another attempt is ignored |
| **+** `DiscardUnit(long job, String unit, int attempt)` C→W | kill if running, remove container, network and unit dir of exactly that attempt; replies `Ack` |

Worker files: **+** `UnitFile.ATTEMPT("attempt")`, written before `MARKER`. Container label **+**
`temper.attempt=<n>` (`DockerRunArgs`, and the `-pom` probe's label too). The pre-M4 startup scan (`StateScan`) reads
`attempt` or reports none. A pre-M4 unit is attempt 1 on both sides: a dir without `attempt` and a container without
the label (§4.4). Reconcile's `Collect` and `Reattach` name the store's current attempt, `KillOrphan` and `Discard` the
disk attempt. The worker also checks `Welcome.protocol` and ends a session with a controller of another protocol.

---

## 8. W2: per-host limits (R-M4-7)

`[[hosts]]` for cloudbb-3 and cloudbb-4 [P, from R-M4-7]:

```toml
[[hosts]]
name = "cloudbb-3"  address = "198.51.100.3"  user = "builder"  key = "~/.ssh/temper_worker"
knownHosts = "~/.temper/known_hosts"  cap = 3  memoryBudgetMb = 18432  portIsolated = false
sharedCpu = true  retireAt = "2026-11-13T00:00:00Z"
```

(cloudbb-4 the same with its own address; the values here are documentation addresses.) `portIsolated` stays false: bb-hold is installed, so port-group units go
through it as on bigboy [U: W5 verifies bb-hold on these hosts].

What the existing policy makes of these numbers [V: `GrantPolicy`, `Fit`, `[units] memoryMb = 6144`,
`gateMemoryMb = 16384`]:
- three ordinary units: 3 × 6144 = 18432 ≤ 18432, fits exactly; a fourth is stopped by the cap anyway;
- a Gate: 16384 ≤ 18432 fits **only alone** (16384 + 6144 = 22528 > 18432), so "16 GB Gates allowed, one at a time"
  needs no new rule;
- the cap counts bb-* reactors (F12): **when maintainer agents fill the 3 bb reactors on a host, temper gets zero slots
  there. That is correct by design** (R-M4-18): the host's CPU and memory belong to whoever runs on it, and the cap
  is the one count of them temper has. With the head blocked by the cap, `GrantPolicy` rule 1 grants nothing and
  leaves starvation unchanged [V], so a busy cloud host does not start a starvation clock. WP-6 writes this into
  the host docs (the cloudbb host record, §13), so a QUEUED job with only cloud hosts UP is read as expected
  behaviour, not a fault;
- memory used by bb-* is outside the budget (F12): 30 GB − 18 GB leaves ~12 GB for bb-* and the OS [U: bb-* peak on
  cpx62 is not measured].

No code change for R-M4-7 beyond the config entries; the gap is test coverage: `GrantPolicyTest` gains the rows above
(§12.1).

---

## 9. W2: CPU classes (R-M4-8, R-M4-9)

### 9.1 `sharedCpu` (host)

`ControllerConfig.Host` + `boolean sharedCpu` (default false; `ConfigLoader` optional key), `HostConfig` +
`sharedCpu`, `hosts.shared_cpu` (upserted at start).

### 9.2 `--dedicated-cpu` (job)

- CLI: `--dedicated-cpu` flag on every submit verb (`CommandLine` common flags) and on `rerun`; `SubmitRequest` +
  `boolean dedicatedCpu`; `JobRequest` + `dedicatedCpu`; `jobs.dedicated_cpu`.
- **A rerun inherits its parent's `dedicated_cpu`** whatever its own flag says [P]: it re-judges the parent's failures
  and must run under the same CPU class; `--dedicated-cpu` on `rerun` can only add it.
- **Placement is a filter, not a fit** (F9): the grant's candidate query takes the host's class as a parameter,
  `… WHERE u.state = 'QUEUED' AND (NOT j.dedicated_cpu OR NOT $sharedCpu) …` (`PgUnitStore.QUEUED_UNITS`/`CANDIDATES`).
  A dedicated unit is never in a shared host's candidate list, never locked by its grant transaction, and never its
  head, so it cannot starve that host.
- **No dedicated host UP → the job stays QUEUED**, indefinitely, as any job does when no eligible host is UP today. The
  digest explains it per host: `shared-cpu` on cloudbb-3/4, the existing skip reason (`no-link` …) on bigboy
  (`GrantExplain`, §11). There is no timeout and no fallback to a shared host.

### 9.3 Dedup

**`dedicatedCpu` enters the dedup key [P]** through `RecipeOptions.canonical` (omitted when false, never `false`): the
same argument as Q-6's timeout override, "it can change the verdict" (timing-sensitive tests on a shared CPU), and
`DedupKey` already says only what does not change what runs stays out (`DedupKey.java:20-21`) [V]. Consequence: a plain
resubmit does not reuse a dedicated job's result (one extra run); the symmetric rule is simpler than an asymmetric
"plain may reuse dedicated" lookup, which `jobs_dedup` cannot express as key equality.

The **supersede** key (`(ref, canonical recipe)`, R-M2-8) does not include it (R-M4-16, ruling Q-M4-9): a newer push
supersedes an older same-ref job of either class.

### 9.4 Non-comparable facts

- At grant, the `GRANT` statement writes `units.shared_cpu = <host's sharedCpu>` (a snapshot, like `granted_judge`);
  the lost-attempt row copies it (`unit_attempts.shared_cpu`).
- `UnitResultDto` (the immutable result record) + `sharedCpu`; `UnitView` + `sharedCpu`. A record says, per unit,
  whether its wall times and `memory.peak` came from a shared-CPU host.
- **§12.1 sizing runs on bigboy only** (R-M4-9): sizing jobs are submitted with `--dedicated-cpu` (bigboy is the only
  dedicated host), and `acceptance/sizing/JobSizing` **refuses** a unit whose record says `sharedCpu = true`
  (`SizingReport` names it and excludes it). The flag is the mechanism; the refusal is the check.
- **No timeout scaling** (R-M4-8): `Timeouts`, `UnitTimeout`, `RerunBounds` are unchanged.
- **The rerun bound excludes shared-CPU history** (R-M4-15, ruling Q-M4-6): data that cannot be compared does not
  size anything, the same logic as R-M4-8/9. `PgResultStore.CLASS_DURATION` joins the row's unit and filters on the
  per-attempt snapshot:
  `… FROM testcases t JOIN units u ON u.id = t.unit_id JOIN results r … JOIN jobs j … WHERE j.rerun_of IS NULL AND
  u.shared_cpu IS NOT TRUE AND (t.class = $1 OR …)`. `IS NOT TRUE` keeps pre-M4 rows (backfilled `false`, §6) and
  would keep a NULL [P: NULL cannot occur for a completed unit, since every grant writes the snapshot]. A class
  whose only history is shared-CPU has **no history**, so its bound falls back to the existing no-history path
  (`PgResultStore` "max over no rows is NULL") [V]. The window (`LIMIT $3` jobs) counts only qualifying jobs, so a
  burst of cloud runs cannot push bigboy runs out of it. **Revisit with 2 weeks of data** (R-M4-15).
- The known cost: a rerun that itself runs on a shared host gets a bound derived from dedicated-CPU history and may
  time out there. It is visible as a timeout on a unit whose record says `sharedCpu = true`.

---

## 10. W3: `retireAt` (R-M4-10)

- `[[hosts]] retireAt` (optional ISO instant), `ControllerConfig.Host` + `Option<Instant> retireAt`, `hosts.retire_at`.
- **No new grants at or after `retireAt`:** `Scheduler.tick` skips a host with `now ≥ retireAt` (the scheduler's
  injected `clock`), and `GrantExplain` names the skip `retired` with detail `since=<instant>` (§11). The check is in the tick, before the
  host's grant transaction, so it holds per tick; a grant transaction already running when the instant passes may
  still commit [V: the tick reads the clock once, `clock.get()`]. Bound: one round, `ROUND_BOUND_TICKS × tickBound`.
- **Retire window (#107):** the tick refuses new grants from `retireAt − retireWindow` (`[scheduler] retireWindowSeconds`,
  default 600, global), by the scheduler's clock at decide time. A unit that is granted but slow to start does not matter
  here: GRANTED counts as active, so the link stays until the unit ends. It is not a grant-to-start bound. Units already
  on the host are untouched; a refused unit stays QUEUED for other hosts.
- **Grant fence (#111):** the decide-time check alone does not bound when a grant COMMITS: the round's
  `ROUND_BOUND_TICKS × TICK_BOUND` timeout only releases the host, it does not cancel the store call, so a hung grant
  transaction could commit after `retireAt`, onto a host whose link was already stopped as drained. So the scheduler
  passes the host's fence, `retireAt − retireWindow` (`Host.grantFence`), into `UnitStore.grant`; `PgUnitStore` sets it
  transaction-locally (`set_config('temper.grant_fence', …, true)`), and the deferred constraint trigger
  `units_grant_fence` (V18) runs at COMMIT for every unit the transaction moved QUEUED → GRANTED and raises when
  `clock_timestamp() ≥ fence`. The COMMIT then fails and the whole transaction rolls back: every unit stays QUEUED with its
  attempt unchanged, no `checkouts` row is left, the round fails (logged), nothing is launched, and another host may
  grant the unit. **Fails closed (maintainer ruling on #114):** the trigger also raises when the fence is UNSET (NULL or '') on a
  QUEUED → GRANTED commit, and when it is malformed; a host without `retireAt` passes the explicit sentinel `none`
  ("unfenced, deliberately"), which `PgUnitStore` sets from `Option.none()`. `UnitStore.grant` has no overload without a
  fence, so no caller can grant without choosing one. Reason: a guarantee that holds only if every future GRANT path
  remembers to call `set_config` is a convention, not a mechanism. Database-clock lag is not detected: the guarantee assumes the lag is less than N. No other path puts a unit on a host: `GRANT` is the only statement that sets `units.host` or the
  state GRANTED; `started`/`startUnknown`/`holding` act only on a unit already on that host in its current attempt,
  `refreshLeases` only extends active attempts, reconcile reattaches units already there, and requeue, LOST settle and
  rerun only move units to QUEUED.
  - **Clock: the database's, read at COMMIT.** `retireAt` is config, not a column, so the fence is a parameter; it is
    compared with `clock_timestamp()` inside COMMIT, never with a time computed when the grant was decided (the decision
    was valid then, so that check could never fail for the hung case) and never with `now()` (the transaction's start
    time, which a hung transaction carries past the fence unchanged).
  - **What it guarantees:** every grant to the host that lands committed before `retireAt − N` by the database clock,
    whatever the store call's latency. The link judges `retireAt` by the controller's clock, so after `retireAt` the
    host's active set only shrinks while the database clock lags the controller's by less than N (minus the time from
    the trigger's check to the commit record, a WAL flush). On bigboy the controller and Postgres read one clock. A
    database clock AHEAD of the controller's only refuses more (the grant fails, the unit waits for another host).
  - **What it does not:** it does not cancel the hung call. Until that transaction ends, its row locks keep the units it
    granted from other hosts' candidates (`SKIP LOCKED`); they become grantable when it commits-and-fails or its
    connection dies. A grant decided before the window that commits in time lands as before.
- **Running units finish:** the link stays connected, heartbeats, collects, cancels and reconciles as before. If a
  retired host vanishes with units on it, §2 applies.
- **Moving it:** edit the config and restart the controller (F8). A restart does not disturb running units (they run on
  the hosts; reconcile reattaches them, m0 §6.4) [V]. Setting it in the past retires the host at once.
- **Alarm:** `worker-lost` ignores a host whose `retireAt` has passed and that has no active units (R-M4-16, ruling
  Q-M4-10), so the
  watchdog's deletion on 2026-11-22 does not raise an alarm forever; until the operator removes the `[[hosts]]` entry
  the host shows `LOST` in `GET /hosts`.
- Timeline for both cloud hosts: retire 2026-11-13T00:00Z; deletion by 2026-11-22: 9 days for units to drain, against
  unit timeouts of hours.

---

## 11. API and CLI surface

| Surface | Change |
|---|---|
| `GET /hosts` → `HostView` | `state` gains `LOST`; + `link` (DOWN/CONNECTING/UP, the link's own state), + `lost: {at, reason}?`, + `sharedCpu`, + `retireAt?`, + `retired` (bool, `now ≥ retireAt`) |
| `GET /jobs/{id}` → `JobView` | + `dedicatedCpu` |
| `UnitView` | + `attempt`, + `sharedCpu?`, + `lostAttempts: [{attempt, host, grantedAt, startedAt?, endedAt}]` |
| result record `UnitResultDto` | + `attempt`, + `sharedCpu`; the job's `ERROR(host-lost)` reason is the existing verdict-reason path |
| events | + `unit-lost` `{unit, attempt, host, outcome: requeued\|error\|cancelled, reason: link-down\|host-key}` |
| `GET /digest` | per-host skip reasons `lost <reason>`, `retired` (detail `since=<instant>`, the implemented wire form: reason and detail are separate digest fields); per-unit reason `shared-cpu` on a shared host |
| CLI | `--dedicated-cpu` on submit verbs and `rerun`; job/unit renderers show `attempt N (lost: cloudbb-3 a1)`; the hosts lines (`Lines`, digest) show `LOST(link-down since …)`, `retired`, `shared-cpu` |
| config | `[[hosts]] sharedCpu`, `retireAt`; `[scheduler] hostLostSeconds` |

No new verb (recovery is automatic, R-M4-16). The refusal code list gains `R-EFFECT` (§5.3); the CLI prints it like
every refusal.

**As built (WP-6).** Additions and exact forms, recorded here so the table stays true:
- `UnitView` also carries `sideEffects` (`none`/`external`), and the result record's `UnitResultDto` also carries
  `lostAttempts` (the same entries as the view), so the immutable record alone says a unit ran twice. The record's
  `attempt` is absent for a unit never granted; its `sharedCpu` is absent in a record written before M4 (read as
  comparable by `JobSizing`: bigboy was the only host then) [G].
- `GET /hosts`: `state` is `LOST` exactly while `lost` is present (one factory derives it). `retired` is judged at the
  request's clock, the digest's at its `asOf`. A view written without the M4 fields reads them defaulted (`link` =
  `state`, not LOST, dedicated, not retired, attempt 0, no lost attempts), as a view without `reruns` already did: the
  booleans and the attempt are boxed and defaulted in the compact constructor, since the mapper refuses an absent
  primitive.
- Digest skip reason `lost`, detail `<link-down|host-key> since=<instant>`, checked after `retired` and before the
  link; a deferred host stays `no-link link=DEFERRED` (§2.1). Host line: `LOST(<reason> since=<instant>)`, `retired
  since=<instant>` (or `retire-at=<instant>` before it passes), `shared-cpu`.
- CLI: `result`, `wait` and a non-terminal job's line add ` attempt(<unit>)="2 (lost: cloudbb-3 a1)"` per unit that
  lost an attempt (a quoted value, as every value with spaces on these lines).
- The digest's candidate window (WP-5 review item a): **the `NotRead` doc was corrected, the read kept**. The digest
  reads the first `CANDIDATE_LIMIT` queued units of every class (`QUEUED_UNITS`), which is exactly a dedicated host's
  window; a shared host's real window skips dedicated units, so the units the digest explains for it are a prefix of
  that window. The only error is a `not-read` for a unit a shared host's grant would read, possible only with more than
  `CANDIDATE_LIMIT` (200) queued units, dedicated ones among the first 200; never a false "would start". A per-class
  read would need a second snapshot query and a per-host window in the pure explanation for a queue that has never
  reached 200.

---

## 12. Test plan

### 12.1 Unit tests (pure, no Docker)

- `HostLivenessTest`: `downSince + lostAfter` exactly, one ms before; a flap UP for `< stableUp` keeps the clock; UP
  for `stableUp` resets it; `hostKeyFailed` → LOST at once; controller start sets `downSince = start`.
- `SshDiagnosisTest`: the two OpenSSH lines (verbatim from `SshdHostKeyIT`'s capture), an unrelated stderr line, an
  empty stream.
- `ReconcilerTest` + rule 0: disk attempt lower than current → `Discard`; granted elsewhere → `Discard`; same attempt
  → rules 1–6 unchanged; a pre-M4 dir (no attempt) vs current 1 → rules 1–6; vs current 2 → `Discard`.
- `LostSettleTest` (pure decision of §2.3, `policy/LostSettle.java` **+**): each row L1–L4 with the bound at 1
  (`host_lost_requeues` 0 → requeue, 1 → ERROR), and precedence (CANCELLING + external → CANCELLED; external + bound
  reached → ERROR).
- `SideEffectRuleTest` (§5.2); `RefusalsTest` + the R-EFFECT rows and the R-SHELL-before-R-EFFECT order row (§5.3). `GrantPolicyTest` + the R-M4-7 rows (§8). `DedupKeyTest`: dedicated changes the key;
  absent equals pre-M4 canonical. `RecipeOptions` canonical omits `false`.
- `UnitStarterTest` (worker): adopt same attempt; remove lower; refuse higher; a pre-M4 unlabelled container is attempt 1:
  `StartUnit(1)` adopts it, `StartUnit(2)` removes it.
- Worker `DiscardUnit` handler: deletes only the named attempt; idempotent on a missing dir.

### 12.2 Integration tests

| IT | Setup | Asserts |
|---|---|---|
| `HostLostSettleIT` (store, Testcontainers PG) | units on host A in each active state, `none`/`external`, requeues 0/1 | L1–L4 rows exactly; `unit_attempts` rows; `unit-lost` events; job states; a repeat settle changes nothing |
| `RequeueBoundIT` (store) | one unit lost twice, on hosts A then B | attempts 1–2 in `unit_attempts`, the second loss → `ERROR(host-lost)`, job `ERROR(host-lost)`, `attempt = 2` (never granted a third time); a hand-written second requeue UPDATE (`host_lost_requeues = 2`) fails the CHECK |
| `RerunBoundSharedCpuIT` (store) | history for class C: bigboy jobs at 10 min, a cloud job (`units.shared_cpu = true`) at 40 min; a second class D with cloud history only | `CLASS_DURATION` for C = 10 min (not 40); for D = no history (NULL); with `LIMIT` = 2 and three newer cloud jobs, the two bigboy jobs still form the window |
| `StaleAttemptStoreIT` (store) | unit requeued and re-granted (attempt 2) on the **same** host | `started`, `holding`, `refreshLeases`, `complete` for attempt 1 change nothing (`Superseded`); for attempt 2 they apply |
| `HostLostLinkIT` (controller, `FakeWorker`, `hostLostAfter` = 2 s, `stableUp` = 1 s) | kill the fake worker's process and make respawn fail (blackhole) | host LOST after the bound, not before; units settled; second fake host gets the requeued unit as attempt 2 |
| `LateReportRejectedIT` (controller, two `FakeWorker` hosts) | host A LOST, unit regranted on B; A returns and sends `UnitExited` + report for attempt 1 | A's report is acked and discarded (no testcases/result rows, unit still RUNNING on B); A's reconcile sends `DiscardUnit(…,1)` before A is UP; B's report completes the unit |
| `SameHostRegrantIT` (controller) | A LOST, recovers, gets the unit's attempt 2 while A's attempt-1 dir exists | `Discard` precedes UP; `StartUnit(attempt 2)`; a late attempt-1 `UnitStarted` is ignored |
| `SshdHostKeyIT` (Docker: an `sshd` container from a test Dockerfile) | known_hosts pinned to key K1; container recreated with key K2 on the same port | the real ssh client's stderr classifies as host-key; the host goes LOST at once (`lost_reason = host-key`); captures the exact OpenSSH lines for `SshDiagnosisTest` |
| `SshdBlackholeIT` (Docker) | `docker pause` the sshd container (blackhole), then `docker stop` (killed sshd) | LOST after `hostLostAfter` in both; with `ConnectTimeout=15` each probe ends within ~15 s |
| `RetireAtIT` (controller, fake clock) | host with `retireAt = now + 1 s`, unit running | no grant after the instant; the running unit completes; digest says `retired` |
| `DedicatedPlacementIT` (store + scheduler) | shared host A UP, dedicated host B DOWN; a dedicated and a plain job | A grants the plain unit only, never locks the dedicated one, no starvation clock on A; B comes UP → dedicated granted on B; with B never UP the job stays QUEUED |
| `SideEffectNotRequeuedIT` (acceptance, §12.3 as built) | a unit row inserted with `side_effects = 'external'` (pre-M4 shape, §5.2), granted and started on A through the store; A LOST | `ERROR(host-lost)`, `attempt = 1`, no QUEUED transition ever recorded, job `ERROR(host-lost)` |
| `SubmitJobTest` + row (controller) | submit `mvn deploy` | `REFUSED(R-EFFECT)` recorded; no unit row |

### 12.3 Acceptance (`temper-acceptance`, `-Pacceptance`, Docker)

`MultiHostLostIT` on the `LocalSsh` stack (real controller, two real `LocalWorker`s) [P]: `LocalSsh` gains a per-host
"blackholed" switch that kills the `run` session pids it already records and refuses new `run` sessions. A real
ModuleTest on host A → blackhole A → after `hostLostAfter` (test value) the unit runs on B as attempt 2 and PASSES →
un-blackhole A → A's reconcile discards attempt 1 (container and dir gone, checked with `docker ps -a` and the worker
home) → the job record shows one result, `attempt 2`, one `lostAttempts` entry.

**As built (WP-7).** Both ITs run on `AcceptanceStack.twoHosts` (hosts `a`, `b`, each a `LocalWorker` with its own
HOME, behind `LocalHosts`, which routes an ssh argv by its `user@address`; `hostLostSeconds` = 5 s, production
`LinkTiming`), with the fixture overlay `slow` (`includes` + a 20 s `SlowProbeTest`, so attempt 1 still runs when its
host goes dark). The blackhole switch (`LocalSsh.blackhole`/`restore`) kills the host's `run` sessions and makes every
ssh to it exit 255 after 1 s with ssh's connect-timeout line, so the link and the deploy fail as `link-down`, never as
`host-key`. It involves no network, so it is deterministic on any host with docker (no netns/iptables, unlike
`SshdBlackholeIT`). The host that got the first grant is the one blackholed. Both ITs first wait `stableUp` (60 s) +
2 liveness ticks after the hosts are UP: until then the LOST clock started at controller start is still running, a
host past `hostLost` is deferred, and leaving UP makes it LOST at once (Q-M4-12 (b); the first run showed exactly
that, the mark 64 ms after the blackhole), so "LOST after `hostLost`, not before" holds only for a healthy host.
- `MultiHostLostIT`: X LOST (`link-down`) with `lost.at − blackhole ≥ hostLost`, link not UP; `temper digest` shows
  `state=LOST … LOST(link-down since=…)`; the unit PASSES on Y as attempt 2 (`host_lost_requeues = 1`, one
  `unit_attempts` row `host-lost`), the record holds one result with `attempt 2` and X's attempt 1 as its one
  `lostAttempts` entry, `SlowProbeTest` is stored once; on X's return X's attempt-1 unit directory (present while X was
  dark) is gone, no attempt-1 container exists, X is UP with `cleared_lost_at` set and the digest line `link=UP
  state=UP` without `LOST`; 12 s later the unit is still attempt 2. **Deviation:** both workers share the local docker
  daemon, so the unit's container has one name on both hosts and Y's `StartUnit(2)` removes X's attempt-1 container
  (adoption guard, §4.2) before X returns; X's discard is therefore proven on X's unit directory, which only X sees.
  Rule 0 off leaves that directory (probe, `Reconciler.superseded` → false). **No double run is not read off the
  daemon:** executions are the unit's container STARTS in the daemon's event log (`docker events`, which keeps a
  start after the container is removed): exactly `1`, `2` by `temper.attempt` label, and no start after X's return;
  completions are the store's: `SlowProbeTest` stored once, evidence under `a2` only (none under `a1`). P3 (`GRANT`
  without `attempt + 1`) makes Y adopt X's attempt-1 container: the start count goes red.
  **Known limit:** the blackhole kills X's worker, so attempt 1 never reports after the loss; a stale attempt-1
  report from a worker that outlives the outage is proven at the controller (`LateReportRejectedIT`), not here. A run
  on two real docker daemons, where X's own discard removes its container, is W5 (per-host acceptance, §14.2).
- `SideEffectNotRequeuedIT` (§5.2; in the acceptance tier rather than as a store + controller IT, so the grant and
  start are the real ones): the `external` row is a real ModuleTest unit, RUNNING in attempt 1 on X, set to
  `side_effects = 'external'` by SQL, which is exactly the V11 backfill of a pre-M4 row (the alternative §5.2 rejected
  was a test-only recipe). X LOST → the unit `ERROR(host-lost)`, `attempt = 1`, one lost attempt on X,
  `host_lost_requeues = 0`, job `ERROR(host-lost)`; after two more liveness ticks still attempt 1 and Y's HOME has no
  directory of the job; X's return changes none of it. Probe P5 (`LostSettle` ignores `sideEffects`) turns it red.

### 12.4 Mutation probes that must go red

| # | Mutation | Red test |
|---|---|---|
| P1 | `complete` without `AND attempt = $a` | `StaleAttemptStoreIT` (`LateReportRejectedIT` stays green: the link fence, `WorkerEvents` report path, drops the stale frame first) |
| P2 | `START` without the attempt guard | `StaleAttemptStoreIT` (`SameHostRegrantIT` stays green: the link drops the late start first; red only with the link's start fence also off) |
| P3 | `GRANT` does not increment `attempt` | `SameHostRegrantIT`, `HostLostLinkIT` |
| P4 | settle ignores the bound (always L4), or checks `host_lost_requeues = 2` instead of 1 | `RequeueBoundIT` (second loss: CHECK violation surfaces as a failed settle, unit not ERROR), `LostSettleTest` |
| P5 | settle ignores `side_effects` | `HostLostSettleIT`, `SideEffectNotRequeuedIT` |
| P6 | CANCELLING requeued instead of CANCELLED | `HostLostSettleIT` |
| P7 | `HostLiveness` resets the clock on any UP (no `stableUp`) | `HostLivenessTest` flap row |
| P8 | host-key failure not classified (diagnosis returns false) | `SshdHostKeyIT` (LOST only after `hostLostAfter`, asserted at once) |
| P9 | reconcile rule 0 removed | `LateReportRejectedIT`, `SameHostRegrantIT` (UP before `Discard`) |
| P10 | adoption ignores the attempt label | `UnitStarterTest` (`SameHostRegrantIT` runs a FakeWorker and cannot see it) |
| P11 | grant query without the `dedicated_cpu` filter | `DedicatedPlacementIT` |
| P12 | scheduler ignores `retireAt` | `RetireAtIT` |
| P13 | `dedicatedCpu` dropped from `RecipeOptions.canonical` | `DedupKeyTest` |
| P14 | `JobSizing` accepts a `sharedCpu` unit | `JobSizingTest` (+ row) |
| P15 | settle not re-run while LOST (edge only) | `HostLostLinkTest` (`HostLostSettleIT` stays green) |
| P16 | recovery runs reconcile before the settle | `HostLostLinkTest` (`HostLostLinkIT` stays green) |
| P17 | `effect(...)` removed from `Refusals.checkSubmit`'s list (or returns success) | `RefusalsTest` R-EFFECT rows, `SubmitJobTest` R-EFFECT row |
| P18 | `CLASS_DURATION` without `u.shared_cpu IS NOT TRUE` | `RerunBoundSharedCpuIT` |
| P111 | V18 trigger never raises (fence removed from the COMMIT) | `GrantFenceIT` (store) |
| P111s | `Scheduler.grantOn` passes no fence | `GrantFenceSchedulerTest` |
| P111f | V18 trigger accepts an unset fence (fail open restored) | `GrantFenceIT.grantWithNoFenceSet_isRefused_unitStaysQueued_noCheckout` |

PIT (D-10) runs on every changed class per WP, as BRIEF-RULES requires.

---

## 13. Work packages and order

Each WP is sized for one agent (≤ ~120 tool calls). V11 is written **whole** in WP-1 so no later WP races the migration
number; later WPs only use its columns.

```
WP-1 schema+store fencing ──► WP-2 settle+side effects ──┐
        │                                                ├──► WP-4 link LOST/recovery/reconcile ──► WP-6 views, sizing, alarm, docs ──► WP-7 acceptance
        ├──► WP-3 protocol v3 + worker ───────────────────┘                                              ▲
        └──► WP-5 placement (sharedCpu, dedicated, retireAt) ────────────────────────────────────────────┘
```

| WP | Content | Depends on | Est. tool calls |
|---|---|---|---|
| **1** | V11 whole (§6); `GRANT` increments `attempt`, writes `shared_cpu`; attempt-guarded `START`, `START_UNKNOWN`, `HOLDING`, `LEASES`, `complete` → `Superseded`; `unit_facts` per attempt; `UnitStore.attempts(refs)`; `Granted.attempt`. ITs: `StaleAttemptStoreIT`; probes P1–P3 | — | ~100 |
| **2** | `Recipe.sideEffects()`, `SideEffectRule`, planner → `UnitSpec.sideEffects` → `units.side_effects`; `RefusalCode.R_EFFECT` + `Refusals.effect` in `checkSubmit` (§5.3); pure `LostSettle` (bound 1); `UnitStore.settleLost(host, now)` (L1–L4, `unit_attempts`, `unit-lost` event, job state rules). Tests: `SideEffectRuleTest`, `RefusalsTest` + `SubmitJobTest` R-EFFECT rows, `HostLostSettleIT`, `RequeueBoundIT`; probes P4–P6, P17 | 1 | ~115 |
| **3** | Protocol v3 (§7) incl. `DiscardUnit`; worker: `.temper/attempt`, `temper.attempt` label, attempt on every frame, adoption guard, `stale-attempt` answers, `DiscardUnit` handler, `StateScan` attempt. Tests: `UnitStarterTest`, discard tests; probe P10 | 1 (for `Granted.attempt` only at the controller seam; worker side independent) | ~110 |
| **4** | `HostLiveness`, `SshDiagnosis`, `HostLink` LOST flag + level-triggered settle + recovery order, `ConnectTimeout=15`, `[scheduler] hostLostSeconds`; `Reconciler` rule 0 + `ReconcileAction.Discard`; `WorkerEvents`/`StartReplies`/`UnitLauncher` attempt plumbing; `UnitAttempt(ref, attempt)`; evidence path `a<n>`. Tests: `HostLostLinkIT`, `LateReportRejectedIT`, `SameHostRegrantIT`, `SshdHostKeyIT`, `SshdBlackholeIT`; probes P7–P9, P15, P16 | 1, 2, 3 | ~120 (split 4a link/liveness, 4b reconcile/fencing if it runs long) |
| **5** | `sharedCpu`, `retireAt` config + upsert; `--dedicated-cpu` CLI/`SubmitRequest`/`JobRequest`/`jobs.dedicated_cpu`; rerun inheritance; dedup option; grant query filter; scheduler `retired` skip; `GrantExplain` reasons; `CLASS_DURATION` excludes `units.shared_cpu` rows (§9.4). Tests: `DedicatedPlacementIT`, `RetireAtIT`, `RerunBoundSharedCpuIT`, `DedupKeyTest`, `GrantPolicyTest` rows; probes P11–P13, P18 | 1 | ~105 |
| **6** | Views (§11), result record fields, digest/CLI rendering, `worker-lost` retired filter, `JobSizing` refusal; docs: spec §6.4/§13 amendment (1 + 1), architecture §3 "Leases" and §2 host states, the cloudbb host record with the config entries and the F12 statement (3 bb reactors busy → zero temper slots, by design, R-M4-18), R-EFFECT in the refusal list of `docs/architecture.md`. Probe P14. From the WP-5 review: (a) the digest's candidate window (`QUEUED_UNITS`) counts every class while a shared host's real `CANDIDATES` window skips dedicated units: read the window per class, or correct the `NotRead` doc; (b) §11's digest `retired` form was amended to the implemented `retired` + `since=<instant>` | 4, 5 | ~90 |
| **7** | `MultiHostLostIT` + `LocalSsh` blackhole switch; `SideEffectNotRequeuedIT` (store-inserted `external` row, §5.2); independent verifier runs P1–P18 red/green into `M4-verification-<date>.md` | 6 | ~90 |

**WP-4 entry criteria** (from the WP-1 review, one line each):
- (a) M2: the frame paths take the attempt from a store read (`WorkerEvents.ownership`), so a same-host stale report
  still completes attempt n+1 until the frames carry their own attempt; P1/P2 are proven at the store only.
- (b) A `Superseded` outcome must not be `ACK_ONLY` by name: the ack carries the attempt (WP-3 does the frame side).
- (c) From the WP-3 review: a reconcile `Collect`/`Reattach` names the store's attempt, and a host holding an older one
  answers `stale-attempt`, which `ReconcileRun.settledOrFailed` settles `ERROR(infra)` today. That is wrong in two
  cases: n+1 was granted but never started while the disk still holds n (`Collect`: a requeue is right, nothing of
  n+1 ran), and `Reattach` (n's container keeps running with no timer). Rule 0 (§4.5, discard first) must land before
  this path is relied on, and with it the stale-attempt answer's settlement must be decided.

Merge order is the numeric order; WP-3 and WP-5 may be built in parallel with WP-2 and merge after it. Handoff
contract as m2 §8: `jbct-reviewer` per WP, an independent verifier (never the author) for the probe table, root
`mvn -B install` through `build-runner`, PIT per D-10.

---

## 14. Out of scope now (await the owner)

### 14.1 W4: host install scripts
Installing the worker prerequisites on cloudbb-3/4 (docker group, `~/.temper` layout, `worker-launch` forced command,
authorized key with `restrict` and `command=`, runner image build, seed). Not designed here.

### 14.2 W5: per-host acceptance
Per-host S1 (spec §11), bb-hold reattach on these hosts, a measured bb-* memory peak on cpx62 (§8). Not designed here.

### 14.3 Firewall and egress
**Open since 2026-10-25** (R-M4-17): the operator added `22/tcp` from bigboy's egress address on both
cloudbb-3 and cloudbb-4, and the team lead checked with `nc` from bigboy that `:22` answers on both. The controller
on bigboy can therefore reach both hosts' sshd; whether the worker runs there is W4/W5 (§14.1, §14.2). Any further
egress rule (e.g. the hosts' own outbound access for Maven and GitHub) is not designed here.

---

## 15. Questions for the maintainer (all ruled 2026-10-25)

| # | Question | Revision 1 guess | Ruling (2026-10-25) |
|---|---|---|---|
| Q-M4-1 | Does a link that reaches UP briefly reset the LOST clock? | Only after `stableUp` (60 s) of UP | **Ruled: guess accepted** (R-M4-16); §2.1 |
| Q-M4-2 | Host-key change: LOST at once, or after `hostLostAfter` like an outage? | At once | **Ruled: guess accepted** (R-M4-16); §2.1 |
| Q-M4-3 | Recovery from LOST: automatic on the next completed handshake (+ settle + discard), or sticky until an operator `temper host readmit`? May a requeued unit return to the host that lost it? | Automatic; no per-unit host exclusion | **Ruled: guess accepted** (R-M4-16); §2.5 |
| Q-M4-4 | When a unit hits the bound or is side-effecting (ERROR(host-lost)), cancel its siblings? | No, they run to their end | **Ruled: guess accepted** (R-M4-16); §2.3 |
| Q-M4-5 | A RUNNING job whose only running unit is requeued: stays RUNNING? | Yes; an UNKNOWN-only job goes back to QUEUED | **Ruled: guess accepted** (R-M4-16); §2.3 |
| Q-M4-6 | Rerun bound history: include shared-CPU rows? | Include (max can only raise the bound) | **Ruled: overturned. Exclude** shared-CPU rows; revisit with 2 weeks of data (R-M4-15); §9.4 |
| Q-M4-7 | The `Mvn` side-effect classifier's deny list; or refuse such `Mvn` jobs at submit (`R-EFFECT`) instead? | Classify and never requeue | **Ruled: refuse at submit as `REFUSED(R-EFFECT)`**; keep `sideEffects()`, the `external` backfill and L2 (R-M4-14); §5.1, §5.3 |
| Q-M4-8 | Controller restart: does the LOST clock resume from a stored `down_since`? | No, it restarts at controller start | **Ruled: guess accepted** (R-M4-16); §2.1 |
| Q-M4-9 | Supersede: does `--dedicated-cpu` separate supersede keys? | No | **Ruled: guess accepted** (R-M4-16); §9.3 |
| Q-M4-10 | `worker-lost` for a retired host with no units: silence it? | Yes | **Ruled: guess accepted** (R-M4-16); §10 |

Still marked in the text and open to correction by the coder's findings, not to a ruling: the `SideEffectRule` deny
list's exact content [G, §5.1], `ConnectTimeout=15` [P, F7], the V11 attempt backfill predicate [G, §6], the evidence
path `a<n>` [P, §4.6], and the [U] items (OpenSSH stderr text, the events CHECK list, retention of `unit_attempts`).

---

## 16. maintainer rulings (2026-10-25)

| # | Ruling | Where |
|---|---|---|
| **R-M4-1** | A host is **LOST** when its link has been continuously down ≥ `hostLostAfter` (10 min, config). A **host-key change** is also LOST (a recreated server on the same IP) | §2.1, §2.2, §3 |
| **R-M4-2** | Every active unit (GRANTED, UNKNOWN, RUNNING, CANCELLING) on a LOST host ends its attempt `ERROR(host-lost)` and is requeued as a new attempt on another host; **bound: 1 requeue per unit** (as amended by R-M4-13; revision 1 had 2); beyond → job `ERROR(host-lost)`. CANCELLING: the design decides (→ CANCELLED, §2.3 L1) | §2.3 |
| **R-M4-3** | The guarantee to state exactly: **at most one stored result per unit; execution may happen twice** | §2.4 |
| **R-M4-4** | **Fencing:** when the host returns, reconcile kills containers of superseded attempts and never stores their result; the design says how a late report or evidence from an old attempt is rejected | §4 |
| **R-M4-5** | A unit with **external side effects** (push, GitHub write, alarm issue, deploy) is **never requeued**: `ERROR(host-lost)`, a human decides. Design the property, its home, its default and tests both ways | §5 |
| **R-M4-6** | LOST-host recovery: propose how and when it becomes eligible again, guesses marked | §2.5, Q-M4-3 |
| **R-M4-7** | Per-host **cap 3**, **`memoryBudgetMb` 18432** for cloudbb-3/4; 16 GB Gates allowed (the budget fits one at a time) | §8 |
| **R-M4-8** | Host flag **`sharedCpu`**; submit flag **`--dedicated-cpu`**: such a job is never granted to a `sharedCpu` host. **No timeout scaling** | §9 |
| **R-M4-9** | §12.1 sizing runs on **bigboy only**; wall times and `memory.peak` from `sharedCpu` hosts are recorded as non-comparable | §9.4 |
| **R-M4-10** | Per-host **`retireAt`** (2026-11-13T00:00Z for cloudbb-3 and cloudbb-4): no new grants at or after it; running units finish; config + restart is an acceptable way to move it if that is how config works today (it is, F8) | §10 |
| **R-M4-11** | Out of scope until the owner rules: W4 host install scripts, W5 per-host acceptance, firewall/egress (bigboy's egress blocked at revision 1; opened 2026-10-25, R-M4-17) | §14 |
| **R-M4-12** | Never describe guarantees with one-word system labels; name the per-operation guarantee and its mechanism | §2.4, §4.3 |

**Rulings on revision 1 (maintainer, 2026-10-25, review of `341e793`):**

| # | Ruling | Where | Replaces |
|---|---|---|---|
| **R-M4-13** | The requeue bound is **1**, so a unit's steps run **at most twice**, which is what R-M4-3 says. Reason: the watchdog deletes both cloud hosts at once, so a second requeue rarely finds a live host | §2.3 L3, §2.4, §6 CHECK `BETWEEN 0 AND 1`, §12.2 `RequeueBoundIT`, §12.4 P4 | revision 1's bound of 2 in R-M4-2 |
| **R-M4-14** | `Mvn` argv that `SideEffectRule` classifies `EXTERNAL` is **refused at submit, `REFUSED(R-EFFECT)`**, judged over the parsed `MvnStep` in `Refusals.checkSubmit` with the other refusals. `Recipe.sideEffects()` stays abstract with no default, the `external` default for pre-M4 rows stays, settle rule L2 and its tests stay (future recipes). The end-to-end test uses a store-inserted `external` row | §5.1, §5.2, §5.3, §12, P17 | Q-M4-7 |
| **R-M4-15** | **Exclude shared-CPU rows from the rerun bound** (`PgResultStore.CLASS_DURATION`, filtered on `units.shared_cpu`): data that cannot be compared does not size anything (as R-M4-8/9). Revisit with 2 weeks of data | §6, §9.4, `RerunBoundSharedCpuIT`, P18 | Q-M4-6 |
| **R-M4-16** | The guesses of Q-M4-1, 2, 3, 4, 5, 8, 9 and 10 are **accepted** as written | §2.1, §2.3, §2.5, §9.3, §10, §15 | Q-M4-1..5, 8..10 |
| **R-M4-17** | §13's scope and order are **accepted**. The firewall is **open since 2026-10-25**: `22/tcp` from bigboy's egress address on both cloud hosts, checked by the team lead with `nc` from bigboy | §13, §14.3 | revision 1's §14.3 ("egress blocked") |
| **Q-M4-11** | `SideEffectRule` is an **allowlist** in a data file, fail-safe: build-only phases (clean…install), build-only plugin goals, listed `-P` profiles; everything else `EXTERNAL` → `REFUSED(R-EFFECT)`; `goal@execution` stripped before judging; the argv-only limit is stated, containment is structural (no publishing credentials on build hosts) | §5.1, §5.3 | revision 2's deny list |
| **Q-M4-11 conditions** (fix round 2, 2026-10-26) | The allowlist stays a data-only Java text block on three conditions: the file holds only the text and its parse, `SideEffectRule` only queries the parsed value; a malformed line refuses (parse fails, every argv `EXTERNAL`, the parse error in the refusal); every listed plugin names its goals (no `*`) | §5.1 | fix round 1's skip-a-malformed-line and `*` goals |
| **Q-M4-11b** | `-D` properties get a fail-safe allowlist: exact names that take a selector or a boolean, never a path or an address; any other user `-D` is `EXTERNAL` → `REFUSED(R-EFFECT)` naming the property. Only the user's argv is judged, never the planner's own tokens (`-Dmaven.repo.local`, `-Doutput`). `additionalClasspathElements` is left out (a path; ruled in Q-M4-11c). `.mvn/maven.config`, `.mvn/jvm.config`, pom default/file activation and pom bindings stay known limits, contained structurally (no outside code on bigboy, no credentials on the cloud hosts) | §5.1, §5.3 | — |
| **Q-M4-11d** (2026-10-26) | Options join the fail-safe allowlist (`option` lines, same parse rules): only exact short or long spellings, each with a value shape (none, `threads`, `modules`, `module`, `file`, `profiles`, `define`); single-dash long names, `=` forms and attached values refused (only `-Dk=v` and `-Pname` attached, judged by the property and profile lists); any user token starting with `-` that is not a listed spelling is `EXTERNAL`, named. `-pl`/`-rf` take module selectors only (grammar in §5.1), `-f` a workspace-relative path (cwd is the workspace). `-pl`, `-rf`, `-f`, `-N` always marked narrowed from the parsed options; unlisted tokens in stored recipes marked `unknown`. Liveness: a verdict event whose job cannot be read is delivered marked `tests-unknown`, with a WARN naming the job; the feed never stalls | §5.1, §5.3 | per-spelling `-fn`/narrowing checks (`MvnStep.narrowingArgs`); unjudged `-l`, `-t`, `-gt`, `-f` |
| **Q-M4-11c** (2026-10-26) | Property list ruled: accepted `skipTests`, `maven.test.skip`, `skipITs`, `test`, `it.test`, `groups`, `excludedGroups`, `failIfNoSpecifiedTests`, `surefire.failIfNoSpecifiedTests`, `it.failIfNoSpecifiedTests`, `ember.skipTests`, `targetClasses`, `targetTests`; `jbct.skip` and `maven.test.failure.ignore` removed (a gate green while examining nothing); `additionalClasspathElements` stays refused (classpath jars are code from outside the workspace; allow later only for paths the planner resolves inside it). Condition: every user skip or select property is shown on the job's result (`result`, `wait`, `jobs`, digest, job and result JSON), marked `tests-skipped` / `tests-narrowed`, derived from the stored recipe. Verify4 (fix round 4): also on every watch/subscriber verdict line, with `-pl`/`-rf`/`-f`/`-N` shown narrowed, values always quoted and escaped on every surface; values with control, whitespace or format characters and `-fn`/`--fail-never` refused at submit (R-EFFECT) | §5.1, §5.3 | Q-M4-11b's `jbct.skip`, `maven.test.failure.ignore` |
| **Q-M4-12** (2026-10-26) | **Option A.** While the link is UP but the `hostLost` clock is overdue, before `stableUp`, the verdict is **Deferred**, on three conditions: (a) no new grants; (b) leaving UP declares LOST at once, with no clock restart; (c) UP through `stableUp` clears the clock and spends no requeue. Premise: a reconnect reattaches running containers (`Reconciler` → `Reattach`), and a new session still keeps the units | §2.1, §2.3, §3 | WP-4a's LOST while UP before `stableUp` |
| **R-M4-18** | F12 is stated in the host docs (WP-6): when maintainer agents fill the 3 bb reactors on a host, temper gets **zero slots** there, correct by design | §8, §13 WP-6 | — |
