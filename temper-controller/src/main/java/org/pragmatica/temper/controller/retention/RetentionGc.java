package org.pragmatica.temper.controller.retention;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.controller.evidence.EvidenceLayout;
import org.pragmatica.temper.controller.evidence.ResultFiles;
import org.pragmatica.temper.controller.flow.Batches;
import org.pragmatica.temper.controller.host.Connection;
import org.pragmatica.temper.controller.host.HostLink;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.maintenance.PgDump;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.retention.DumpFile;
import org.pragmatica.temper.domain.retention.JobRetention;
import org.pragmatica.temper.domain.retention.RetentionAction;
import org.pragmatica.temper.domain.retention.RetentionPlan;
import org.pragmatica.temper.domain.retention.RetentionPolicy;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.EvidenceRow;
import org.pragmatica.temper.store.api.EvidenceStore;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.RetentionStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The retention GC (m1-design §4.2), every `[retention] intervalMinutes` behind a non-reentrant guard:
///
/// 1. candidates: the store's collectable jobs (paged by id), the event horizon, the verified dump files on disk;
/// 2. [RetentionPlan#plan] (pure; nothing of a `keep` or non-terminal job is ever planned);
/// 3. the actions, in batches of [#BATCH]: a tarball's file is deleted, then its `evidence` row marked deleted; a
///    job's rows through `RetentionStore.deleteJobRows`; events through `deleteEventsUpTo` (floor and delete in one
///    transaction); a dump file deleted; host checkouts sent as one `GcJobs` per UP host, its `GcDone.deleted`
///    recorded with `checkoutGone`.
///
/// The `result.json` sweep ([ResultFiles#sweep]) runs on its own schedule and its own non-reentrant guard: at start,
/// then every `interval`. It shares nothing with the actions, so a store failure in the plan or a slow host clean-up
/// (`GcJobs` may take up to [#GC_TIMEOUT]) never delays it, which is what keeps the m1-design §3.3 bound "within one
/// sweep interval" (as long as the sweep's own reads succeed).
///
/// FER for every action: a failed one is logged and counted; the store state it would have changed is unchanged, so
/// the next tick plans it again (a host checkout of a DOWN host waits for the host the same way). Nothing here deletes
/// a `result.json`, a `results`, `jobs` or `units` row.
public final class RetentionGc {
    private static final Logger log = LoggerFactory.getLogger(RetentionGc.class);
    public static final int PAGE = 500;
    public static final int BATCH = 50;
    /// Jobs per `GcJobs` request, and how long the worker may take to delete them.
    public static final int GC_JOBS = 20;
    public static final TimeSpan GC_TIMEOUT = timeSpan(30).minutes();

    private final RetentionStore retention;
    private final EvidenceStore evidence;
    private final HostLinks links;
    private final ResultFiles results;
    private final EvidenceLayout layout;
    private final RetentionPolicy policy;
    private final Supplier<Instant> clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private final AtomicReference<Option<ScheduledFuture<?>>> periodic = new AtomicReference<>(none());
    private final AtomicReference<Option<ScheduledFuture<?>>> sweeps = new AtomicReference<>(none());

    /// What one run did: the actions planned and how many of them failed.
    public record Summary(int planned, int failed) {
        public static Summary summary(int planned, int failed) {
            return new Summary(planned, failed);
        }
    }

    private RetentionGc(RetentionStore retention,
                        EvidenceStore evidence,
                        HostLinks links,
                        ResultFiles results,
                        EvidenceLayout layout,
                        RetentionPolicy policy,
                        Supplier<Instant> clock) {
        this.retention = retention;
        this.evidence = evidence;
        this.links = links;
        this.results = results;
        this.layout = layout;
        this.policy = policy;
        this.clock = clock;
    }

    public static RetentionGc retentionGc(RetentionStore retention,
                                          EvidenceStore evidence,
                                          HostLinks links,
                                          ResultFiles results,
                                          EvidenceLayout layout,
                                          RetentionPolicy policy,
                                          Supplier<Instant> clock) {
        return new RetentionGc(retention, evidence, links, results, layout, policy, clock);
    }

    /// The `result.json` sweep now (controller start) and every `interval`; a retention run every `interval`.
    public Unit start(TimeSpan interval) {
        sweeps.set(some(SharedScheduler.scheduleAtFixedRate(this::sweepTick, timeSpan(0).millis(), interval)));
        periodic.set(some(SharedScheduler.scheduleAtFixedRate(this::tick, interval, interval)));

        return unit();
    }

    public Unit stop() {
        sweeps.getAndSet(none()).onPresent(future -> future.cancel(false));
        periodic.getAndSet(none()).onPresent(future -> future.cancel(false));

        return unit();
    }

    /// One run unless one is in flight (non-reentrant: a slow run is never overlapped by the next tick).
    public Unit tick() {
        return guarded(running, this::run, "retention run");
    }

    /// One sweep unless one is in flight; independent of [#tick].
    public Unit sweepTick() {
        return guarded(sweeping, this::sweep, "result.json sweep");
    }

    /// One full run; answers what it did.
    public Promise<Summary> run() {
        var now = clock.get();

        return candidates(now).map(candidates -> candidates.planned(policy, now))
                         .flatMap(this::executed)
                         .map(executed -> Summary.summary(executed.planned(),
                                                          executed.failed()))
                         .onSuccess(summary -> log.info("retention: {}", summary));
    }

    /// One `result.json` sweep; answers how many files it wrote.
    public Promise<Integer> sweep() {
        return results.sweep()
                      .onSuccess(written -> log.info("result.json sweep wrote {} file(s)",
                                                     written));
    }

    // ---- candidates --------------------------------------------------------------------------------------------
    /// The inputs of the plan.
    record Candidates(List<JobRetention> jobs, Option<Long> eventHorizon, List<DumpFile> dumps) {
        static Candidates candidates(List<JobRetention> jobs, Option<Long> eventHorizon, List<DumpFile> dumps) {
            return new Candidates(jobs, eventHorizon, dumps);
        }

        List<RetentionAction> planned(RetentionPolicy policy, Instant now) {
            return RetentionPlan.plan(policy, now, jobs, eventHorizon, dumps);
        }
    }

    /// The jobs whose earliest clock (the shortest of the job lifetimes) ran out, the events older than their
    /// lifetime, and the dumps on disk; the plan applies each clock itself.
    private Promise<Candidates> candidates(Instant now) {
        return Promise.all(jobs(now.minus(shortestJobLifetime()),
                                none(),
                                List.of()),
                           retention.eventHorizon(now.minus(policy.events())),
                           dumps().async())
                      .map(Candidates::candidates);
    }

    private Duration shortestJobLifetime() {
        return Stream.of(policy.checkout(),
                         policy.full(),
                         policy.evidence())
                     .min(Duration::compareTo)
                     .orElse(policy.checkout());
    }

    /// Pages by id until a page is shorter than [#PAGE].
    private Promise<List<JobRetention>> jobs(Instant finishedBefore, Option<JobId> after, List<JobRetention> found) {
        return retention.jobs(finishedBefore, after, PAGE)
                        .flatMap(page -> nextPage(finishedBefore, page, found));
    }

    private Promise<List<JobRetention>> nextPage(Instant finishedBefore,
                                                 List<JobRetention> page,
                                                 List<JobRetention> found) {
        var all = Stream.concat(found.stream(), page.stream()).toList();

        return page.size() < PAGE
               ? Promise.success(all)
               : jobs(finishedBefore,
                      some(page.getLast().job()),
                      all);
    }

    /// Verified dumps only: a `.partial` file or anything else does not match [PgDump#DUMP].
    private Result<List<DumpFile>> dumps() {
        return FileOps.isDirectory(layout.pgDumps())
               ? FileOps.list(layout.pgDumps()).map(RetentionGc::dumpFiles)
               : success(List.of());
    }

    private static List<DumpFile> dumpFiles(List<Path> files) {
        return files.stream()
                    .map(RetentionGc::fileName)
                    .map(RetentionGc::dumpFile)
                    .flatMap(Option::stream)
                    .toList();
    }

    private static String fileName(Path file) {
        return file.getFileName()
                   .toString();
    }

    private static Option<DumpFile> dumpFile(String name) {
        return PgDump.takenAt(name).map(takenAt -> DumpFile.dumpFile(name, takenAt));
    }

    // ---- actions -----------------------------------------------------------------------------------------------
    /// The planned count and how many failed.
    record Executed(int planned, int failed) {
        static Executed executed(int planned, int failed) {
            return new Executed(planned, failed);
        }
    }

    private Promise<Executed> executed(List<RetentionAction> actions) {
        var checkouts = actions.stream().flatMap(RetentionGc::checkout).toList();
        var others = actions.stream().filter(action -> !(action instanceof RetentionAction.DeleteCheckout)).toList();

        return Batches.run(others, BATCH, this::performed)
                      .flatMap(done -> withCheckouts(failures(done),
                                                     checkouts))
                      .map(failed -> new Executed(actions.size(),
                                                  failed));
    }

    /// Checkouts last: the rows and files of a job go before its host directory does.
    private Promise<Integer> withCheckouts(int failed, List<RetentionAction.DeleteCheckout> checkouts) {
        return checkoutsDeleted(checkouts).map(gc -> failed + gc);
    }

    private static Stream<RetentionAction.DeleteCheckout> checkout(RetentionAction action) {
        return action instanceof RetentionAction.DeleteCheckout checkout
               ? Stream.of(checkout)
               : Stream.empty();
    }

    private Promise<Unit> performed(RetentionAction action) {
        return perform(action).onFailure(cause -> log.warn("retention {} failed: {}", action, cause.message()));
    }

    private Promise<Unit> perform(RetentionAction action) {
        return switch (action) {
            case RetentionAction.DeleteEvidence delete -> evidenceDeleted(delete.evidenceId(), delete.storedAt());
            case RetentionAction.DeleteTestcases delete -> rowsDeleted(delete.job());
            case RetentionAction.DeleteEventsUpTo delete -> eventsDeleted(delete.seq());
            case RetentionAction.DeletePgDump delete -> dumpDeleted(delete.name());
            case RetentionAction.DeleteCheckout _ -> Promise.unitPromise();
        };
    }

    private Promise<Unit> rowsDeleted(JobId job) {
        return retention.deleteJobRows(job)
                        .mapToUnit();
    }

    private Promise<Unit> eventsDeleted(long upTo) {
        return retention.deleteEventsUpTo(upTo)
                        .mapToUnit();
    }

    /// `name` came from listing the dump directory and matched [PgDump#DUMP], so it names a file there.
    private Promise<Unit> dumpDeleted(String name) {
        return fileDeleted(layout.pgDumps().resolve(name)).async();
    }

    /// The file first, then the row, both only for the tarball the plan read (`storedAt`, m1-design §4.1): a row
    /// re-pulled after the plan names a new file, which is left alone, and the store marks a row deleted only while
    /// its `stored_at` still matches, so a pull landing between this read and the mark keeps its row live for the
    /// next tick. A row already marked deleted needs nothing.
    private Promise<Unit> evidenceDeleted(long id, Instant storedAt) {
        return evidence.evidence(id)
                       .flatMap(row -> rowDeleted(id, storedAt, row));
    }

    private Promise<Unit> rowDeleted(long id, Instant storedAt, Option<EvidenceRow> row) {
        return row.filter(found -> isPlanned(found, storedAt))
                  .map(found -> fileThenRow(id, storedAt, found))
                  .or(Promise::unitPromise);
    }

    private static boolean isPlanned(EvidenceRow row, Instant storedAt) {
        return row.storedAt()
                  .equals(storedAt) && row.deletedAt()
                                          .isEmpty();
    }

    private Promise<Unit> fileThenRow(long id, Instant storedAt, EvidenceRow row) {
        return fileDeleted(row.path()).async()
                          .flatMap(_ -> evidence.deleted(id, storedAt));
    }

    private static Result<Unit> fileDeleted(Path file) {
        return FileOps.deleteIfExists(file).mapToUnit();
    }

    /// One `GcJobs` per host and [#GC_JOBS] jobs; answers how many requests failed.
    private Promise<Integer> checkoutsDeleted(List<RetentionAction.DeleteCheckout> checkouts) {
        var byHost = checkouts.stream()
                              .collect(Collectors.groupingBy(RetentionAction.DeleteCheckout::host,
                                                             Collectors.mapping(RetentionAction.DeleteCheckout::job,
                                                                                Collectors.toList())));

        return Batches.run(requests(byHost), 1, this::gcOnHost).map(RetentionGc::failures);
    }

    /// A host and up to [#GC_JOBS] of its jobs.
    record GcRequest(String host, List<JobId> jobs) {
        static GcRequest gcRequest(String host, List<JobId> jobs) {
            return new GcRequest(host, List.copyOf(jobs));
        }
    }

    private static List<GcRequest> requests(Map<String, List<JobId>> byHost) {
        return byHost.entrySet()
                     .stream()
                     .sorted(Map.Entry.comparingByKey())
                     .flatMap(RetentionGc::chunks)
                     .toList();
    }

    private static Stream<GcRequest> chunks(Map.Entry<String, List<JobId>> hostJobs) {
        var jobs = hostJobs.getValue();

        return IntStream.range(0,
                               (jobs.size() + GC_JOBS - 1) / GC_JOBS)
                        .mapToObj(index -> chunk(hostJobs.getKey(),
                                                 jobs,
                                                 index));
    }

    private static GcRequest chunk(String host, List<JobId> jobs, int index) {
        var from = index * GC_JOBS;

        return GcRequest.gcRequest(host,
                                   jobs.subList(from,
                                                Math.min(jobs.size(), from + GC_JOBS)));
    }

    private Promise<Unit> gcOnHost(GcRequest request) {
        return HostName.hostName(request.host())
                       .async()
                       .flatMap(host -> gcOn(host,
                                             request.jobs()))
                       .onFailure(cause -> log.warn("checkout GC on {} for {} failed: {}",
                                                    request.host(),
                                                    request.jobs(),
                                                    cause.message()));
    }

    /// A host that is not UP is skipped: its checkouts stay listed and the next tick asks again.
    private Promise<Unit> gcOn(HostName host, List<JobId> jobs) {
        return links.link(host)
                    .filter(HostLink::isUp)
                    .map(link -> gcVia(link, host, jobs))
                    .or(Promise::unitPromise);
    }

    private Promise<Unit> gcVia(HostLink link, HostName host, List<JobId> jobs) {
        return link.request(new Message.GcJobs(jobs.stream().map(JobId::value).toList()),
                            GC_TIMEOUT)
                   .flatMap(reply -> Connection.expect(reply, Message.GcDone.class).async())
                   .map(Message.GcDone.class::cast)
                   .flatMap(done -> recordedGone(host, done));
    }

    /// `deleted` is recorded; `kept` is logged with the worker's reason (a job is never half-deleted).
    private Promise<Unit> recordedGone(HostName host, Message.GcDone done) {
        done.kept().forEach(kept -> log.info("[{}] checkout of job {} kept: {}", host, kept.job(), kept.reason()));

        return Batches.values(done.deleted(),
                              BATCH,
                              job -> checkoutGone(host, job))
                      .mapToUnit();
    }

    private Promise<Unit> checkoutGone(HostName host, long job) {
        return JobId.jobId(job)
                    .async()
                    .flatMap(id -> retention.checkoutGone(host, id));
    }

    // ---- guard -------------------------------------------------------------------------------------------------
    private static <T> Unit guarded(AtomicBoolean guard, Supplier<Promise<T>> work, String what) {
        return guard.compareAndSet(false, true)
               ? released(guard, work.get(), what)
               : skipped(what);
    }

    /// Fire-and-forget: the work logs its outcome, and the guard is released as a step of its chain.
    private static <T> Unit released(AtomicBoolean guard, Promise<T> work, String what) {
        work.onFailure(cause -> log.warn("{} failed: {}", what, cause.message())).withResult(_ -> guard.set(false));

        return unit();
    }

    private static Unit skipped(String what) {
        log.info("{} still in flight; tick skipped", what);

        return unit();
    }

    private static int failures(List<Result<Unit>> results) {
        return (int) results.stream()
                            .filter(Result::isFailure)
                            .count();
    }
}
