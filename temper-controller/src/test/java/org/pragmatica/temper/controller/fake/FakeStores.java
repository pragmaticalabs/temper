package org.pragmatica.temper.controller.fake;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.known.KnownEntry;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.domain.refusal.NewRefusal;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalRule;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.VerdictInput;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.LostSettled;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.AttemptRef;
import org.pragmatica.temper.store.api.StoredAttempt;
import org.pragmatica.temper.store.api.CancelOutcome;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventStore;
import org.pragmatica.temper.store.api.EvidenceRow;
import org.pragmatica.temper.store.api.EvidenceStore;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HoldingRow;
import org.pragmatica.temper.store.api.HostConfig;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.HostStore;
import org.pragmatica.temper.store.api.JobFinish;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.JobQuery;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.KnownListing;
import org.pragmatica.temper.store.api.KnownRow;
import org.pragmatica.temper.store.api.KnownStore;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.MaintenanceStore;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.Pending;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.RefusalRow;
import org.pragmatica.temper.store.api.RefusalStore;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.ResultStore;
import org.pragmatica.temper.store.api.BaseRead;
import org.pragmatica.temper.store.api.StoredSide;
import org.pragmatica.temper.store.api.StoredSides;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.StoredEvidence;
import org.pragmatica.temper.store.api.SubmitOutcome;
import org.pragmatica.temper.store.api.SubscriberRow;
import org.pragmatica.temper.store.api.UnitFinishRow;
import org.pragmatica.temper.store.api.UnitManifestRows;
import org.pragmatica.temper.store.api.UnitStartedRow;
import org.pragmatica.temper.store.api.UnitStore;
import org.pragmatica.temper.store.pg.WireRecords;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;

/// In-memory store fakes for unit tests: just enough behaviour, plus a record of every call.
public final class FakeStores {
    private FakeStores() {}

    /// `value`, resolved `delayMs` later on another thread (0: as it is). Fakes answer at once by default, which
    /// hides ordering bugs: a step attached as a detached callback then still runs before the caller continues.
    public static <T> Promise<T> later(long delayMs, Promise<T> value) {
        if (delayMs <= 0) {
            return value;
        }
        var deferred = Promise.<T> promise();
        SharedScheduler.schedule(() -> value.onResult(deferred::resolve), TimeSpan.timeSpan(delayMs).millis());
        return deferred;
    }

    /// A recorded `UnitStore.complete`; `attempt` is the attempt it was scoped to.
    public record Completion(UnitRef ref,
                             UnitFinishRow finish,
                             List<TestcaseRow> rows,
                             Option<JobFinish> decided,
                             Option<UnitManifestRows> manifest,
                             Option<StoredEvidence> evidence,
                             int attempt) {}

    public static final class Units implements UnitStore {
        public final Map<HostName, List<ActiveUnit>> active = new ConcurrentHashMap<>();
        public final List<QueuedUnit> queue = new CopyOnWriteArrayList<>();
        public final Map<Long, Granted> grantable = new ConcurrentHashMap<>();
        public final List<Completion> completions = new CopyOnWriteArrayList<>();
        public final List<UnitRef> requeued = new CopyOnWriteArrayList<>();
        /// The attempt of every `requeue` call, in order.
        public final List<Integer> requeuedAttempts = new CopyOnWriteArrayList<>();
        public final List<UnitRef> started = new CopyOnWriteArrayList<>();
        /// The attempt of every `started` call, in order.
        public final List<Integer> startedAttempts = new CopyOnWriteArrayList<>();
        public final List<UnitRef> holding = new CopyOnWriteArrayList<>();
        public final List<HoldingRow> holdingRows = new CopyOnWriteArrayList<>();
        public final List<UnitStartedRow> startedRows = new CopyOnWriteArrayList<>();
        /// When each `started` call (each start attempt) arrived, `System.nanoTime()`, in call order.
        public final List<Long> startNanos = new CopyOnWriteArrayList<>();
        public final List<List<AttemptRef>> leaseRefreshes = new CopyOnWriteArrayList<>();
        /// The refs of each `attempts` read, in call order.
        public final List<List<UnitRef>> attemptReads = new CopyOnWriteArrayList<>();
        public final List<Integer> grantCalls = new CopyOnWriteArrayList<>();
        /// The fence of every `grant` call, in call order (#111).
        public final List<Option<Instant>> grantFences = new CopyOnWriteArrayList<>();
        /// How each `grant` COMMIT ended, in order: `committed [ids]` or `fenced`.
        public final List<String> grantOutcomes = new CopyOnWriteArrayList<>();
        /// When set, `grant` decides at once and COMMITs only when this resolves: a held grant transaction (#111).
        public volatile Option<Promise<Unit>> grantHold = none();
        /// The database clock the COMMIT judges a fence by.
        public volatile Supplier<Instant> commitClock = Instant::now;
        public volatile boolean failActive = false;
        /// Answers `active` and `grant` this many milliseconds later.
        public volatile long delayMs = 0;
        public volatile boolean hangActive = false;
        /// `active` records at once and answers after this promise resolves (a reconcile held mid-way).
        public volatile Promise<Unit> activeGate = Promise.unitPromise();
        /// `settleLost` records at once and answers [#settleAnswer] after this promise resolves (a slow settle).
        public volatile Promise<Unit> settleGate = Promise.unitPromise();
        public volatile List<LostSettled> settleAnswer = List.of();
        /// The store calls of a LOST host's settle and of reconcile, in call order (m4-design §2.5: settle first).
        public final List<String> calls = new CopyOnWriteArrayList<>();
        public final List<HostName> lostCalls = new CopyOnWriteArrayList<>();
        public final java.util.concurrent.atomic.AtomicInteger failSettles = new java.util.concurrent.atomic.AtomicInteger();
        public volatile CompleteOutcome completeAnswer = null;
        /// `started` records at once and resolves with this promise (a slow start write).
        public volatile Promise<Unit> startGate = Promise.unitPromise();
        /// `holding` records at once and resolves with this promise (a slow holding write).
        public volatile Promise<Unit> holdingGate = Promise.unitPromise();
        public final java.util.concurrent.atomic.AtomicInteger failCompletes = new java.util.concurrent.atomic.AtomicInteger();
        /// Answers `complete` (a success or a `failCompletes` failure) this many milliseconds later.
        public volatile long completeDelayMs = 0;
        /// `started` fails ("store down") this many times before it answers with `startGate`.
        public final java.util.concurrent.atomic.AtomicInteger failStarts = new java.util.concurrent.atomic.AtomicInteger();
        /// The units whose `started` succeeded, in the order the writes resolved.
        public final List<UnitRef> recordedStarts = new CopyOnWriteArrayList<>();
        /// Every `startUnknown` call (each mark attempt), and the units whose mark succeeded.
        public final List<UnitRef> unknownAttempts = new CopyOnWriteArrayList<>();
        /// The attempt (its number) each `startUnknown` call named, in call order.
        public final List<Integer> unknownGrants = new CopyOnWriteArrayList<>();
        public final List<UnitRef> markedUnknown = new CopyOnWriteArrayList<>();
        /// `startUnknown` fails with a terminal cause (no retry changes it) while set.
        public volatile boolean terminalUnknowns = false;
        /// `startUnknown` fails ("store down") this many times before it succeeds.
        public final java.util.concurrent.atomic.AtomicInteger failUnknowns = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Promise<List<Granted>> grant(HostName host,
                                            Fn1<GrantDecision, List<QueuedUnit>> policy,
                                            TimeSpan lease,
                                            int maxGrants,
                                            Option<Instant> fence) {
            grantCalls.add(maxGrants);
            grantFences.add(fence);
            var decided = decided(policy, maxGrants);
            return grantHold.map(hold -> hold.flatMap(_ -> committed(host, fence, decided)))
                            .or(() -> later(delayMs, committed(host, fence, decided)));
        }

        /// The policy's rounds over the queue as it is now, as the store runs them inside its transaction.
        private List<Long> decided(Fn1<GrantDecision, List<QueuedUnit>> policy, int maxGrants) {
            var queued = new ArrayList<>(queue);
            var ids = new ArrayList<Long>();
            for (int round = 0; round < maxGrants; round++) {
                var id = policy.apply(List.copyOf(queued)).grant();
                if (id.isEmpty() || queued.stream().noneMatch(unit -> unit.unitId() == id.unwrap())) {
                    break;
                }
                queued.removeIf(unit -> unit.unitId() == id.unwrap());
                ids.add(id.unwrap());
            }
            return ids;
        }

        /// The COMMIT: as V18's trigger, refused whole when [#commitClock] is at or past the fence (#111); else every
        /// decided unit leaves the queue and becomes active on `host`.
        private Promise<List<Granted>> committed(HostName host, Option<Instant> fence, List<Long> decided) {
            var now = commitClock.get();
            var refused = !decided.isEmpty() && fence.filter(at -> !now.isBefore(at)).isPresent();
            grantOutcomes.add(refused ? "fenced" : "committed " + decided);
            if (refused) {
                return Causes.cause("grant fence passed").promise();
            }
            var granted = new ArrayList<Granted>();
            for (var id : decided) {
                queue.removeIf(unit -> unit.unitId() == id);
                var one = grantable.get(id);
                granted.add(one);
                activeOn(host).add(ActiveUnit.activeUnit(one.ref(), one.unitId(), UnitState.GRANTED, one.memoryMb(),
                                                         one.timeout(), none(), none(), one.needsPortGroup(), none(), none(), one.kind(),
                                                         some(one.grantedAt()), one.attempt()));
            }
            return Promise.success(List.copyOf(granted));
        }

        @Override
        public Promise<Unit> holding(HostName host, UnitRef ref, int attempt, HoldingRow row) {
            holding.add(ref);
            holdingRows.add(row);
            return holdingGate;
        }

        @Override
        public Promise<Unit> started(HostName host, UnitRef ref, int attempt, UnitStartedRow row) {
            startNanos.add(System.nanoTime());
            started.add(ref);
            startedAttempts.add(attempt);
            startedRows.add(row);
            if (failStarts.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return Causes.cause("store down").promise();
            }
            return startGate.map(done -> recorded(ref, done));
        }

        private Unit recorded(UnitRef ref, Unit done) {
            recordedStarts.add(ref);
            return done;
        }

        @Override
        public Promise<Unit> startUnknown(HostName host, UnitRef ref, int attempt) {
            unknownAttempts.add(ref);
            unknownGrants.add(attempt);
            if (terminalUnknowns) {
                return Causes.terminal("unit row unreadable").promise();
            }
            if (failUnknowns.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return Causes.cause("store down").promise();
            }
            markedUnknown.add(ref);
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> refreshLeases(HostName host, List<AttemptRef> running, TimeSpan lease) {
            leaseRefreshes.add(List.copyOf(running));
            return Promise.unitPromise();
        }

        /// The units active on any host, at their attempt.
        @Override
        public Promise<List<StoredAttempt>> attempts(List<UnitRef> refs) {
            attemptReads.add(List.copyOf(refs));
            return Promise.success(active.entrySet()
                                         .stream()
                                         .flatMap(entry -> entry.getValue()
                                                                .stream()
                                                                .filter(unit -> refs.contains(unit.ref()))
                                                                .map(unit -> StoredAttempt.storedAttempt(unit.ref(),
                                                                                                         unit.attempt(),
                                                                                                         some(entry.getKey()),
                                                                                                         unit.state())))
                                         .toList());
        }

        @Override
        public Promise<List<ActiveUnit>> active(HostName host) {
            calls.add("active");
            return hangActive
                   ? Promise.promise()
                   : failActive
                     ? Causes.cause("store down").promise()
                     : activeGate.flatMap(_ -> later(delayMs, Promise.success(List.copyOf(activeOn(host)))));
        }

        @Override
        public Promise<CompleteOutcome> complete(HostName host,
                                                 UnitRef ref,
                                                 int attempt,
                                                 UnitFinishRow finish,
                                                 List<TestcaseRow> rows,
                                                 Option<UnitManifestRows> manifest,
                                                 Option<StoredEvidence> evidence,
                                                 Fn1<Option<JobFinish>, VerdictInput> decideJob) {
            if (failCompletes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return later(completeDelayMs, Causes.cause("store down").promise());
            }
            var decided = decideJob.apply(VerdictInput.verdictInput(List.of(), false, false, none()));
            completions.add(new Completion(ref, finish, List.copyOf(rows), decided, manifest, evidence, attempt));
            active.values().forEach(units -> units.removeIf(unit -> unit.ref().equals(ref)));
            return later(completeDelayMs,
                         Promise.success(Option.option(completeAnswer)
                                               .or(() -> new CompleteOutcome.Completed(ref, UnitState.PASSED, none()))));
        }

        /// Settles nothing (the store ITs cover the rules); records the call in [#lostCalls] and in [#calls]. With
        /// [#failSettles] above zero the call fails (the store is down) and the count drops by one.
        @Override
        public Promise<List<LostSettled>> settleLost(HostName host,
                                                     Instant now,
                                                     Fn1<Option<JobFinish>, VerdictInput> decideJob) {
            calls.add("settleLost");
            lostCalls.add(host);
            return failSettles.getAndUpdate(n -> Math.max(0, n - 1)) > 0
                   ? Causes.cause("store down").<List<LostSettled>> promise()
                   : settleGate.map(_ -> settleAnswer);
        }

        @Override
        public Promise<Unit> requeue(HostName host, UnitRef ref, int attempt) {
            requeued.add(ref);
            requeuedAttempts.add(attempt);
            return Promise.unitPromise();
        }

        public List<ActiveUnit> activeOn(HostName host) {
            return active.computeIfAbsent(host, _ -> new CopyOnWriteArrayList<>());
        }
    }

    public static final class Jobs implements JobStore {
        public final AtomicLong ids = new AtomicLong(100);
        public final List<NewJob> submitted = new CopyOnWriteArrayList<>();
        public final List<NewJob> refused = new CopyOnWriteArrayList<>();
        public final List<Refusal> refusals = new CopyOnWriteArrayList<>();
        public final Map<JobId, JobRow> rows = new ConcurrentHashMap<>();
        /// `job` of these fails, as a store error or a recipe that does not decode would.
        public final Set<JobId> unreadable = ConcurrentHashMap.newKeySet();
        public volatile SubmitOutcome nextSubmit = null;
        public volatile CancelOutcome nextCancel = null;
        /// Answers `submit` and `requestCancel` this many milliseconds later.
        public volatile long delayMs = 0;
        /// Runs after each `list` read is taken: a write that commits between two reads.
        public volatile Consumer<JobQuery> afterList = _ -> {};
        /// What `gateCandidates` reads, in any order; it answers those at a SHA starting with the prefix, newest first.
        public final List<GateRow> gateRows = new CopyOnWriteArrayList<>();

        @Override
        public Promise<SubmitOutcome> submit(NewJob job) {
            submitted.add(job);
            return later(delayMs, Promise.success(Option.option(nextSubmit)
                                         .or(() -> new SubmitOutcome.Accepted(JobId.jobId(ids.incrementAndGet())
                                                                                   .unwrap(),
                                                                              List.of()))));
        }

        @Override
        public Promise<JobId> recordRefused(NewJob job, Refusal refusal) {
            refused.add(job);
            refusals.add(refusal);
            return Promise.success(JobId.jobId(ids.incrementAndGet()).unwrap());
        }

        @Override
        public Promise<Option<JobRow>> job(JobId id) {
            return unreadable.contains(id)
                   ? Causes.cause("job " + id + " does not decode").promise()
                   : Promise.success(Option.option(rows.get(id)));
        }

        /// As `PgJobStore.list`: the stored state filter, the keyset cursor, newest first, at most `limit`.
        @Override
        public Promise<List<JobRow>> list(JobQuery q) {
            var page = rows.values()
                           .stream()
                           .filter(row -> q.state().filter(state -> state != row.state()).isEmpty())
                           .filter(row -> q.before().filter(before -> row.id().value() >= before.value()).isEmpty())
                           .sorted(java.util.Comparator.comparingLong((JobRow row) -> row.id().value()).reversed())
                           .limit(q.limit())
                           .toList();
            afterList.accept(q);
            return Promise.success(page);
        }

        @Override
        public Promise<List<GateRow>> gateCandidates(ShaPrefix sha) {
            return Promise.success(gateRows.stream()
                                           .filter(row -> row.job().sha().filter(sha::isPrefixOf).isPresent())
                                           .sorted(java.util.Comparator.comparingLong((GateRow row) -> row.job().id().value()).reversed())
                                           .toList());
        }

        @Override
        public Promise<CancelOutcome> requestCancel(JobId id) {
            return later(delayMs, Option.option(nextCancel)
                                        .map(Promise::success)
                                        .or(() -> StoreError.JobNotFound.FACTORY.apply(id).promise()));
        }
    }

    public static final class Hosts implements HostStore {
        public final List<String> states = new CopyOnWriteArrayList<>();
        public final List<HostFacts> facts = new CopyOnWriteArrayList<>();
        public final List<HostConfig> upserted = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> upsert(HostConfig h) {
            upserted.add(h);
            return Promise.unitPromise();
        }

        /// Every `lost` write, in order; [#stored] is what `loss` reads (a LOST mark kept across a restart).
        public final List<HostLoss> losses = new CopyOnWriteArrayList<>();
        public volatile Option<HostLoss> stored = none();
        /// `loss` fails ("store down") this many times; every call is counted in [#lossReads].
        public final java.util.concurrent.atomic.AtomicInteger failLoss = new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.concurrent.atomic.AtomicInteger lossReads = new java.util.concurrent.atomic.AtomicInteger();
        /// The mark `recovered` last cleared: `lost` refuses one at or before it (the V12 guard, `HostLossIT`).
        public volatile Option<Instant> cleared = none();
        /// `recovered` records "UP" at once and commits after this promise resolves (a slow or hung UP write).
        public volatile Promise<Unit> recoveredGate = Promise.unitPromise();
        /// `lost` records at once, commits after [#lostGate] (a statement that reaches the store late) and answers
        /// after [#lostAnswerGate] too (a commit whose answer is late). Each call takes the gates set when it is made.
        public volatile Promise<Unit> lostGate = Promise.unitPromise();
        public volatile Promise<Unit> lostAnswerGate = Promise.unitPromise();
        /// `lost` fails ("store down") this many times.
        public final java.util.concurrent.atomic.AtomicInteger failLost = new java.util.concurrent.atomic.AtomicInteger();
        /// `lost` writes refused as stale.
        public final java.util.concurrent.atomic.AtomicInteger staleMarks = new java.util.concurrent.atomic.AtomicInteger();

        /// Like `PgHostStore`: a LOST host keeps its state and mark.
        @Override
        public Promise<Unit> state(HostName h, String state) {
            states.add(state);
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> lost(HostName h, HostLoss loss) {
            states.add("LOST");
            losses.add(loss);
            if (failLost.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return Causes.cause("store down").promise();
            }
            var answer = lostAnswerGate;
            return lostGate.flatMap(_ -> marked(h, loss))
                           .flatMap(_ -> answer);
        }

        private synchronized Promise<Unit> marked(HostName h, HostLoss loss) {
            if (cleared.filter(at -> !at.isBefore(loss.at())).isPresent()) {
                staleMarks.incrementAndGet();
                return StoreError.StaleLoss.FACTORY.apply(h).promise();
            }
            stored = stored.orElse(some(loss));
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> recovered(HostName h, HostLoss seen) {
            states.add("UP");
            return recoveredGate.flatMap(_ -> cleared(h, seen));
        }

        private synchronized Promise<Unit> cleared(HostName h, HostLoss seen) {
            if (!stored.equals(some(seen))) {
                return StoreError.StaleLoss.FACTORY.apply(h).promise();
            }
            stored = none();
            cleared = some(seen.at());
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<HostLoss>> loss(HostName h) {
            lossReads.incrementAndGet();
            return failLoss.getAndUpdate(n -> Math.max(0, n - 1)) > 0
                   ? Causes.cause("store down").<Option<HostLoss>> promise()
                   : Promise.success(stored);
        }

        @Override
        public Promise<Unit> facts(HostName h, HostFacts f) {
            facts.add(f);
            return Promise.unitPromise();
        }
    }

    public static final class Refusals implements RefusalStore {
        public volatile List<RefusalRule> rules = List.of();
        public final List<RefusalRow> rows = new CopyOnWriteArrayList<>();

        @Override
        public Promise<List<RefusalRule>> active() {
            return Promise.success(rules);
        }

        @Override
        public Promise<List<RefusalRow>> list() {
            return Promise.success(List.copyOf(rows));
        }

        @Override
        public Promise<RefusalRow> add(NewRefusal refusal) {
            var row = RefusalRow.refusalRow(refusal.rule(), refusal.issue(), refusal.reason(), refusal.until(), none());
            if (rows.stream().anyMatch(existing -> existing.rule().code().equals(refusal.code()))) {
                return StoreError.RefusalExists.FACTORY.apply(refusal.code()).promise();
            }
            rows.add(row);
            return Promise.success(row);
        }

        @Override
        public Promise<RefusalRow> expire(String code) {
            return Option.from(rows.stream().filter(row -> row.rule().code().equals(code)).findFirst())
                         .map(Promise::success)
                         .or(() -> StoreError.RefusalNotFound.FACTORY.apply(code).<RefusalRow> promise());
        }
    }

    /// The Postgres contract (`PgKnownStore`): ids count up from 1, an active entry for the test is `KnownExists`, an
    /// expired one does not block, and an expiry is kept once set. Answers `delayMs` later on another thread.
    public static final class Known implements KnownStore {
        public final List<KnownRow> rows = new CopyOnWriteArrayList<>();
        /// Rows the domain cannot read, as the Postgres store lists them (ruling R-M2-15).
        public final List<KnownListing.Unreadable> unreadable = new CopyOnWriteArrayList<>();
        private static final Instant CREATED_AT = Instant.parse("2026-10-19T10:00:00Z");
        private static final Instant EXPIRED_AT = Instant.parse("2026-10-19T11:00:00Z");
        public volatile long delayMs = 5;
        private final AtomicLong ids = new AtomicLong();

        @Override
        public Promise<List<KnownListing>> list() {
            return later(delayMs,
                         Promise.success(Stream.concat(rows.stream().map(KnownListing::readable), unreadable.stream())
                                               .sorted(Comparator.comparingLong(KnownListing::id))
                                               .toList()));
        }

        @Override
        public synchronized Promise<KnownRow> add(KnownEntry entry) {
            if (rows.stream().anyMatch(row -> row.expiredAt().isEmpty() && row.entry().testId().equals(entry.testId()))) {
                return later(delayMs, StoreError.KnownExists.FACTORY.apply(entry.testId()).promise());
            }
            var row = KnownRow.knownRow(ids.incrementAndGet(), entry, CREATED_AT, none());
            rows.add(row);
            return later(delayMs, Promise.success(row));
        }

        @Override
        public synchronized Promise<KnownListing> expire(long id) {
            return later(delayMs,
                         Option.from(rows.stream().filter(row -> row.id() == id).findFirst())
                               .map(this::expired)
                               .map(KnownListing::readable)
                               .orElse(() -> Option.from(unreadable.stream().filter(row -> row.id() == id).findFirst())
                                                   .map(this::expiredUnreadable))
                               .map(Promise::success)
                               .or(() -> StoreError.KnownNotFound.FACTORY.apply(id).<KnownListing> promise()));
        }

        private KnownListing expiredUnreadable(KnownListing.Unreadable row) {
            var expired = KnownListing.Unreadable.unreadable(row.id(), row.testId(), row.issue(), row.reason(), row.until(),
                                                             row.createdBy(), row.createdAt(),
                                                             row.expiredAt().orElse(() -> some(EXPIRED_AT)), row.problem());
            unreadable.set(unreadable.indexOf(row), expired);
            return expired;
        }

        private KnownRow expired(KnownRow row) {
            var expired = KnownRow.knownRow(row.id(),
                                            row.entry(),
                                            row.createdAt(),
                                            row.expiredAt().orElse(() -> some(EXPIRED_AT)));
            rows.set(rows.indexOf(row), expired);
            return expired;
        }
    }

    /// Runs in recording order; `latest` and `lastGood` pick by `ended_at` as the Postgres store does.
    public static final class Maintenance implements MaintenanceStore {
        public final List<MaintenanceRun> runs = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Long> record(MaintenanceRun run) {
            runs.add(run);
            return Promise.success((long) runs.size());
        }

        @Override
        public Promise<Option<MaintenanceRun>> lastGood(MaintenanceKind kind) {
            return Promise.success(newest(kind, true));
        }

        @Override
        public Promise<Option<MaintenanceRun>> latest(MaintenanceKind kind) {
            return Promise.success(newest(kind, false));
        }

        private Option<MaintenanceRun> newest(MaintenanceKind kind, boolean okOnly) {
            return Option.from(runs.stream()
                                   .filter(run -> run.kind() == kind && (run.ok() || !okOnly))
                                   .max(Comparator.comparing(MaintenanceRun::endedAt)));
        }
    }

    public static final class Results implements ResultStore {
        public final Map<JobId, ResultRecord> records = new ConcurrentHashMap<>();
        public final Map<JobId, String> json = new ConcurrentHashMap<>();
        public volatile Option<JobManifest> manifest = none();
        public volatile List<TestcaseRow> rows = List.of();

        @Override
        public Promise<Option<ResultRecord>> result(JobId id) {
            return Promise.success(Option.option(records.get(id)));
        }

        @Override
        public Promise<List<TestcaseRow>> testcases(JobId id, Option<Outcome> outcome) {
            return Promise.success(rows);
        }

        @Override
        public Promise<Option<String>> recordJson(JobId id) {
            return Promise.success(Option.option(json.get(id)));
        }

        @Override
        public Promise<List<JobId>> finishedSince(Instant since) {
            return Promise.success(java.util.stream.Stream.concat(records.keySet().stream(), json.keySet().stream())
                                                          .distinct()
                                                          .toList());
        }

        @Override
        public Promise<Option<JobManifest>> manifest(JobId id) {
            return Promise.success(manifest);
        }

        /// What `classDurations` knows, by class; it answers on another thread, as the Postgres driver does, and keeps
        /// only the classes asked for. `historyReads` records each call's classes and `lastJobs`.
        public final Map<String, Duration> durations = new ConcurrentHashMap<>();
        public final List<Map.Entry<List<String>, Integer>> historyReads = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Map<String, Duration>> classDurations(List<String> classNames, int lastJobs) {
            historyReads.add(Map.entry(List.copyOf(classNames), lastJobs));
            return later(5,
                         Promise.success(classNames.stream()
                                                   .filter(durations::containsKey)
                                                   .collect(Collectors.toUnmodifiableMap(name -> name,
                                                                                                          durations::get))));
        }

        /// What `diffSides` reads, by job. It answers on another thread (as the Postgres driver does), so a caller
        /// that read its result in an `on*` callback instead of the chain would see nothing. An unknown job fails as the
        /// Postgres store does: side A first, then side B, with that job's [StoreError.JobNotFound].
        public final Map<JobId, StoredSide> sides = new ConcurrentHashMap<>();
        public final List<List<JobId>> diffReads = new CopyOnWriteArrayList<>();

        @Override
        public Promise<StoredSides> diffSides(JobId a, JobId b) {
            diffReads.add(List.of(a, b));
            return later(5,
                         stored(a).flatMap(sideA -> stored(b).map(sideB -> StoredSides.storedSides(sideA, sideB)))
                                  .async());
        }

        /// Where `diffBase` reads its Gate candidates (the fake jobs' `gateRows`, as `gateCandidates` answers them).
        public volatile JobStore gates = new Jobs();

        /// As the Postgres store: side B first (an unknown job fails), the candidates, the selection, side A when named.
        @Override
        public Promise<BaseRead> diffBase(ShaPrefix sha, JobId job, Fn1<Result<Option<JobId>>, List<GateRow>> choose) {
            return later(5, stored(job).async())
                .flatMap(b -> gates.gateCandidates(sha)
                                   .flatMap(rows -> baseRead(rows, b, choose).async()));
        }

        private Result<BaseRead> baseRead(List<GateRow> rows, StoredSide b, Fn1<Result<Option<JobId>>, List<GateRow>> choose) {
            return choose.apply(rows)
                         .flatMap(base -> base.map(id -> withA(id, b)).or(() -> Result.success(Option.<StoredSides> none())))
                         .map(sides -> BaseRead.baseRead(rows, sides));
        }

        private Result<Option<StoredSides>> withA(JobId a, StoredSide b) {
            diffReads.add(List.of(a, b.job().id()));
            return stored(a).map(sideA -> Option.some(StoredSides.storedSides(sideA, b)));
        }

        private Result<StoredSide> stored(JobId id) {
            return Option.option(sides.get(id))
                         .toResult(StoreError.JobNotFound.FACTORY.apply(id));
        }
    }

    public static final class Events implements EventStore {
        public final List<EventRow> rows = new CopyOnWriteArrayList<>();
        public final Map<String, SubscriberRow> subscribers = new ConcurrentHashMap<>();
        public final AtomicLong seq = new AtomicLong();
        /// The retention floor: every event with `seq <= floor` counts as deleted.
        public volatile long floor = 0;

        @Override
        public Promise<Long> append(Option<JobId> job, EventRow.Kind kind, EventPayload payload) {
            var next = seq.incrementAndGet();
            rows.add(EventRow.eventRow(next, Instant.now(), job, kind, payload));
            return Promise.success(next);
        }

        @Override
        public Promise<List<EventRow>> after(long after, Set<String> kinds, int limit) {
            return Promise.success(rows.stream()
                                       .filter(row -> row.seq() > after)
                                       .filter(row -> kinds.isEmpty() || kinds.contains(WireRecords.eventKind(row.kind()).wire()))
                                       .limit(limit)
                                       .toList());
        }

        @Override
        public Promise<Pending> pending(long cursor, Set<String> kinds, int limit) {
            return floor > cursor
                   ? Promise.success(new Pending.Gap(cursor + 1, floor))
                   : after(cursor, kinds, limit).map(Pending.Events::new);
        }

        @Override
        public Promise<Long> floor() {
            return Promise.success(floor);
        }

        @Override
        public Promise<SubscriberRow> subscriber(String name) {
            return Promise.success(subscribers.computeIfAbsent(name,
                                                               n -> SubscriberRow.subscriberRow(n, Math.max(seq.get(), floor), List.of(), 0)));
        }

        @Override
        public Promise<Unit> ack(String name, long ackSeq) {
            return Option.option(subscribers.computeIfPresent(name,
                                                              (n, row) -> SubscriberRow.subscriberRow(n,
                                                                                                      Math.max(row.cursor(), ackSeq),
                                                                                                      row.selectors(),
                                                                                                      row.version())))
                         .map(_ -> Promise.unitPromise())
                         .or(() -> StoreError.SubscriberNotFound.FACTORY.apply(name).<Unit> promise());
        }

        /// The store's set algebra, atomically per subscriber (`compute`), as the one-statement UPDATE is.
        @Override
        public Promise<SubscriberRow> selectors(String name, List<Selector> add, List<Selector> remove) {
            return Promise.success(subscribers.compute(name, (n, row) -> changed(n, row, add, remove)));
        }

        private SubscriberRow changed(String name, SubscriberRow row, List<Selector> add, List<Selector> remove) {
            var current = Option.option(row).or(() -> SubscriberRow.subscriberRow(name, Math.max(seq.get(), floor), List.of(), 0));
            var removed = remove.stream().map(Selector::wire).collect(Collectors.toSet());
            var selectors = Stream.concat(current.selectors().stream(), add.stream())
                                  .collect(Collectors.toMap(Selector::wire, selector -> selector, (first, _) -> first, TreeMap::new))
                                  .values()
                                  .stream()
                                  .filter(selector -> !removed.contains(selector.wire()))
                                  .toList();
            return SubscriberRow.subscriberRow(name, current.cursor(), selectors, current.version() + 1);
        }

        /// The conditional skip-ack, atomically per subscriber as the one-statement UPDATE is.
        @Override
        public Promise<Unit> skipTo(String name, long version, long skipSeq) {
            subscribers.computeIfPresent(name, (n, row) -> skipped(row, version, skipSeq));
            return Promise.unitPromise();
        }

        private static SubscriberRow skipped(SubscriberRow row, long version, long skipSeq) {
            return row.version() == version && row.cursor() < skipSeq
                   ? SubscriberRow.subscriberRow(row.name(), skipSeq, row.selectors(), row.version())
                   : row;
        }
    }

    public static final class Evidence implements EvidenceStore {
        public record Recorded(UnitRef unit, EvidenceKind kind, StoredEvidence evidence) {}

        public final List<Recorded> recorded = new CopyOnWriteArrayList<>();
        public final Map<Long, EvidenceRow> rows = new ConcurrentHashMap<>();
        public final List<Long> deleted = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> recorded(UnitRef unit, EvidenceKind kind, StoredEvidence evidence) {
            recorded.add(new Recorded(unit, kind, evidence));
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EvidenceRow>> evidence(long id) {
            return Promise.success(Option.option(rows.get(id)));
        }

        @Override
        public Promise<Unit> deleted(long id, Instant storedAt) {
            Option.option(rows.get(id))
                  .filter(row -> row.storedAt().equals(storedAt))
                  .onPresent(_ -> deleted.add(id));
            return Promise.unitPromise();
        }
    }

    public static Duration hour() {
        return Duration.ofHours(1);
    }

    public static Unit nothing() {
        return unit();
    }

    public static Option<Instant> someNow() {
        return some(Instant.now());
    }
}
