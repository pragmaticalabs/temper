package org.pragmatica.kiln.service.github;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.BotIssue;
import org.pragmatica.kiln.core.github.BranchRead;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.CheckCells;
import org.pragmatica.kiln.core.github.CheckRun;
import org.pragmatica.kiln.core.github.CommitStatus;
import org.pragmatica.kiln.core.github.GitHubError;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRead;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.github.PullsPage;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.Rulesets;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.service.config.ConfigError;
import org.pragmatica.kiln.service.config.KilnConfigLoader;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.unitPromise;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.utils.Causes.cause;


/// The GitHub loop (design §3.1, spec §4). A cycle runs only when the budget covers its need (else `RATE-LIMITED`,
/// nothing sent), then reads in order:
/// - **pulls:** the one-time backfill, paged to the end, then incrementally, paged while the page's oldest `updated_at`
///   is at or after the high-water mark; the PRs go to the store, which never deletes one (K-13);
/// - **per open PR** (open in the full table, never counted from the pages): `pulls/{n}` for mergeability, and
///   `commits/{sha}` once per head SHA for its date;
/// - **required lists** of every configured base and of each open PR's other base (stacked, read on demand);
/// - **cells** of every open PR whose base requires checks, from its head's check runs and statuses, each paged and
///   joined in read order;
/// - **base reads:** the runs of each configured base, the scheduled runs, and the bot's open issues;
/// - **the change feed** ([GitHubFeed], CI interface T1 WP2), when one is given:
///   the feed reads every repository through its own path, and last its bounded re-read.
///
/// A failed read is noted and degrades forward per its rule: the old PR rows, cursors and base sets stay with their
/// age; a failed required read is written `UNKNOWN(<status>)`; a failed check read gives `UNKNOWN` cells for that
/// head. No failure makes a count read 0 (§3.4). The cycle's noted failures decide the source state: `RATE-LIMITED`
/// for a refused request (the reserve, or a secondary limit), or a 403/429 with nothing remaining or with a secondary
/// limit, else `GITHUB-DOWN(<status>)`. A clean cycle reads `Incomplete(skipped #N: <reason>)` while any PR a page
/// skipped has not been decoded since, else `Ok`: the skipped set outlives the cycle that found it ([#skippedAfter]),
/// and is reloaded after a start from that stored `Incomplete` reason. Residual: a start while the stored state is a
/// failure finds no set to reload (its detail holds the status or the reset), so a PR skipped before it reads again
/// only when its page is re-read.
///
/// A tick runs a cycle only when the stretched period has passed ([RateBudget#period]), and is single-flight: a tick
/// while a cycle runs is skipped and counted ([#skippedTicks]). So `CheckStore.cells` has one writer per PR at a time
/// (a WP5 precondition: concurrent writes for one PR fail or record a wrong `before`), and within a cycle each PR's
/// cells are written one after another ([#sequentially]). Every exit settles the tick and frees the next: a
/// synchronous throw fails it, and a cycle past its `deadline` reads `GITHUB-DOWN(cycle timed out)`; its late result
/// reaches nothing, and its late steps fail at once ([Cycle#live]). A request or write already in flight at the deadline
/// still completes, and the guard stays held until it has ([#held]), so no next cycle overlaps it.
public record GitHubPoller(GitHubReads reads,
                           KilnStore store,
                           String repo,
                           List<BranchName> bases,
                           Supplier<Instant> clock,
                           Duration deadline,
                           AtomicReference<Pace> pace,
                           AtomicReference<Option<SourceState>> last,
                           Map<Sha, Instant> committed,
                           AtomicReference<Option<Map<PrNumber, String>>> skippedPrs,
                           AtomicBoolean running,
                           AtomicLong skipped,
                           Option<GitHubFeed> feed,
                           GitHubWatch watch) {
    public static final Duration PERIOD = Duration.ofSeconds(60);
    /// A tick this close to the due time runs: the loop's own jitter must not cost a whole period.
    static final Duration SLACK = Duration.ofSeconds(5);
    /// A cycle still running this long after it started reads `GITHUB-DOWN(cycle timed out)` and frees the next tick.
    public static final Duration DEADLINE = Duration.ofMinutes(10);
    static final String TIMED_OUT = "cycle timed out";
    /// The pulls pages a cycle is estimated to read: an incremental cycle reads one. A longer read (the backfill) is
    /// still held at the reserve, request by request ([RateBudget#spend]).
    static final int PULLS_PAGES = 1;
    /// Per configured base: the branch, its rulesets, its runs.
    static final int PER_BASE = 3;
    /// Per stacked base: the branch and its rulesets.
    static final int PER_STACKED = 2;
    /// Per open PR: `pulls/{n}`, the check runs and the statuses, one page each.
    static final int PER_OPEN = 3;
    /// Requests every cycle sends besides: the scheduled runs and the issues.
    static final int FIXED_NEED = 2;
    static final Comparator<PrNumber> BY_NUMBER = Comparator.comparingLong(PrNumber::value);

    /// One entry of the skipped set as `Cycle#clean` writes it: `skipped #N: <reason>`, entries joined by `; `.
    static final Pattern SKIPPED = Pattern.compile("skipped #(\\d{1,18}): (.*?)(?=; skipped #\\d{1,18}: |$)",
                                                   Pattern.DOTALL);

    static final String PULLS = "pulls?state=all&sort=updated&direction=desc&per_page=100";
    static final String SCHEDULE = "schedule";
    static final Pattern BLANKS = Pattern.compile("\\s+");

    /// When the next cycle is due, and the period it was paced by.
    public record Pace(Instant due, Duration period) {
        static Pace pace(SourceState state, Instant at, Duration period) {
            return switch (state) {
                case SourceState.RateLimited(var reset, var _) -> new Pace(reset, period);
                default -> new Pace(at.plus(period), period);
            };
        }

        boolean due(Instant now) {
            return ! now.plus(SLACK)
                        .isBefore(due);
        }
    }

    /// The failures one cycle noted, and whether it passed its deadline. Confined to the cycle; a queue because the
    /// steps' continuations run on the client's threads, one after another.
    record Cycle(Instant at, Queue<Cause> problems, AtomicBoolean expired) {
        static Cycle cycle(Instant at) {
            return new Cycle(at, new ConcurrentLinkedQueue<>(), new AtomicBoolean());
        }

        <T> Result<T> noted(Result<T> read) {
            return read.onFailure(problems::add);
        }

        /// FER: a failed read is noted (it decides the source state) and degrades to none, so the step writes nothing
        /// and the old rows stay with their age.
        <T> Promise<Option<T>> attempt(Promise<T> read) {
            return read.withFailure(problems::add)
                       .map(Option::some)
                       .recover(_ -> none());
        }

        /// A step of a cycle past its deadline fails at once: it sends and writes nothing.
        <T> Promise<T> live(Supplier<Promise<T>> step) {
            return expired.get()
                   ? GitHubReadError.General.LATE.promise()
                   : step.get();
        }

        SourceState state(RateBudget budget, Map<PrNumber, String> skipped, Option<String> drift) {
            return Option.from(problems.stream().findFirst())
                         .map(first -> failed(first, budget))
                         .or(() -> clean(skipped, drift));
        }

        private SourceState failed(Cause first, RateBudget budget) {
            return problems.stream()
                           .anyMatch(problem -> rateLimited(problem, budget, at))
                   ? new SourceState.RateLimited(budget.until(at, 1), at)
                   : new SourceState.GithubDown(describe(first), at);
        }

        /// The drift text comes first: [#SKIPPED] reads each skipped reason up to the next `; skipped #`, so text after the
        /// last one would be taken for part of it on reload.
        private SourceState clean(Map<PrNumber, String> skipped, Option<String> drift) {
            return skipped.isEmpty() && drift.isEmpty()
                   ? new SourceState.Ok(at)
                   : new SourceState.Incomplete(Stream.concat(drift.stream(),
                                                              skipped.isEmpty()
                                                              ? Stream.<String> empty()
                                                              : Stream.of(skippedText(skipped)))
                                                      .collect(Collectors.joining("; ")),
                                                at);
        }

        private static String skippedText(Map<PrNumber, String> skipped) {
            return skipped.entrySet()
                          .stream()
                          .map(found -> "skipped " + found.getKey() + ": " + found.getValue())
                          .collect(Collectors.joining("; "));
        }
    }

    /// A cycle that ran to its end: its state, and the need it was gated and paced by.
    record Ran(SourceState state, int need) {
        static Ran ran(SourceState state, int need) {
            return new Ran(state, need);
        }
    }

    public static Result<GitHubPoller> gitHubPoller(GitHubReads reads,
                                                    KilnStore store,
                                                    String repo,
                                                    List<BranchName> bases,
                                                    Supplier<Instant> clock) {
        return gitHubPoller(reads, store, repo, bases, clock, DEADLINE);
    }

    public static Result<GitHubPoller> gitHubPoller(GitHubReads reads,
                                                    KilnStore store,
                                                    String repo,
                                                    List<BranchName> bases,
                                                    Supplier<Instant> clock,
                                                    Duration deadline) {
        return gitHubPoller(reads, store, repo, bases, clock, deadline, none());
    }

    /// With the change feed's reads (CI interface T1 WP2) in every cycle.
    public static Result<GitHubPoller> gitHubPoller(GitHubReads reads,
                                                    KilnStore store,
                                                    String repo,
                                                    List<BranchName> bases,
                                                    Supplier<Instant> clock,
                                                    Duration deadline,
                                                    Option<GitHubFeed> feed) {
        return Verify.ensure(repo,
                             Verify.Is::matches,
                             KilnConfigLoader.REPO,
                             ConfigError.Invalid.FACTORY.apply("github",
                                                               "repo",
                                                               cause("must be owner/name")))
                     .map(valid -> new GitHubPoller(reads,
                                                    store,
                                                    valid,
                                                    List.copyOf(bases),
                                                    clock,
                                                    deadline,
                                                    new AtomicReference<>(new Pace(Instant.MIN, PERIOD)),
                                                    new AtomicReference<>(none()),
                                                    new ConcurrentHashMap<>(),
                                                    new AtomicReference<>(none()),
                                                    new AtomicBoolean(),
                                                    new AtomicLong(),
                                                    feed,
                                                    GitHubWatch.gitHubWatch()));
    }

    /// One loop tick: a cycle when it is due, none when the stretched period has not passed (nothing is sent).
    public Promise<Option<SourceState>> tick() {
        var now = clock.get();

        return pace.get()
                   .due(now)
               ? guarded(now)
               : Promise.success(none());
    }

    /// The ticks skipped because a cycle was still running.
    public long skippedTicks() {
        return skipped.get();
    }

    /// The rate limit as GitHub's last answer stated it, for the sources view (WP9 open item 2).
    public RateBudget.Rate rate() {
        return reads.budget()
                    .rate()
                    .get();
    }

    /// What `GET /api/v1/github-budget` publishes: kiln's rate sample as GitHub last stated it, the credential mode, and
    /// the drift reads.
    public GitHubBudget budget() {
        var rate = rate();

        return new GitHubBudget(rate.at().flatMap(at -> sample(rate, at)),
                                reads.http().mode(),
                                watch.drift());
    }

    private Option<GitHubBudget.Sample> sample(RateBudget.Rate rate, Instant at) {
        return Option.all(rate.remaining(),
                          rate.limit(),
                          rate.reset())
                     .map((remaining, limit, reset) -> new GitHubBudget.Sample(Credential.APP_IDENTITY,
                                                                               limit,
                                                                               remaining,
                                                                               limit - remaining,
                                                                               reset,
                                                                               at,
                                                                               watch.fromProbe(at)
                                                                               ? GitHubBudget.Sample.RATE_LIMIT
                                                                               : GitHubBudget.Sample.HEADER));
    }

    /// The PRs a page skipped that no read has decoded since, each with its reason (`skipped #N: <reason>`), observed at
    /// the time of the last recorded state, for the sources view (WP9 open item 2). None before the first cycle ends.
    public List<Observed<PullsPage.Skipped>> skippedNotes() {
        return last.get()
                   .map(GitHubPoller::recordedAt)
                   .map(at -> notes(skippedPrs.get().or(Map.of()),
                                    at))
                   .or(List.of());
    }

    private static List<Observed<PullsPage.Skipped>> notes(Map<PrNumber, String> skipped, Instant at) {
        return skipped.entrySet()
                      .stream()
                      .map(entry -> Observed.observed(PullsPage.Skipped.skipped(entry.getKey(),
                                                                                entry.getValue(),
                                                                                none()),
                                                      at))
                      .toList();
    }

    /// When a state was recorded: a failure's start, or the read itself.
    static Instant recordedAt(SourceState state) {
        return switch (state) {
            case SourceState.Ok(var at) -> at;
            case SourceState.Incomplete(var _, var at) -> at;
            case SourceState.Unreachable(var _, var since) -> since;
            case SourceState.RateLimited(var _, var since) -> since;
            case SourceState.GithubDown(var _, var since) -> since;
            case SourceState.TemperDown(var _, var since) -> since;
            case SourceState.TemperSchema(var _, var since) -> since;
        };
    }

    /// One cycle now, due or not, bypassing the single-flight guard: tests only, one call at a time.
    Promise<SourceState> cycle() {
        var cycle = Cycle.cycle(clock.get());

        return cycle(cycle, started(cycle));
    }

    private Promise<Option<SourceState>> guarded(Instant now) {
        return running.compareAndSet(false, true)
               ? held(Cycle.cycle(now))
               : Promise.success(skippedTick());
    }

    /// Single writer (lead ruling): the tick settles by the deadline, but the guard is freed only
    /// when both the tick and the cycle's own run have settled, so a write the run still has in flight at the deadline
    /// completes before another cycle can start; [Cycle#live] fails every later step of the expired run at once. A run
    /// that never settles keeps the guard: every tick is skipped and counted, and GitHub's `last_success_at` ages, so the
    /// source reads stale by age. Pinned by `GitHubPollerIT.timedOutCycle_holdsTheGuard_untilItsWriteSettles`.
    private Promise<Option<SourceState>> held(Cycle cycle) {
        var holds = new AtomicInteger(2);

        return cycle(cycle,
                     started(cycle).withResult(_ -> released(holds))).map(Option::some)
                    .withResult(_ -> released(holds));
    }

    /// The second of the run and the tick to settle frees the guard; true when this call freed it.
    private boolean released(AtomicInteger holds) {
        return holds.decrementAndGet() == 0 && running.compareAndSet(true, false);
    }

    private Option<SourceState> skippedTick() {
        skipped.incrementAndGet();

        return none();
    }

    /// The period the last cycle was paced by: [#PERIOD], or longer when the rate limit demands it (Q-7).
    public Duration period() {
        return pace.get()
                   .period();
    }

    /// The requests the coming cycle can send ([#need(int, int, int, int)]) for the open PRs in the table: a stacked
    /// base is one an open PR targets that is not configured, and a new head is one whose date is not known.
    int need(List<PullRequest> open) {
        return need(bases.size(), stackedBases(open).size(), open.size(), newHeads(open)) + feedNeed(open) + watch.driftNeed();
    }

    /// What the change feed adds ([GitHubFeed#need]); nothing without one.
    private int feedNeed(List<PullRequest> open) {
        return feed.map(reading -> reading.need(open.size()))
                   .or(0);
    }

    /// The pulls pages ([#PULLS_PAGES]), per configured base its branch, rulesets and runs, per stacked base its branch
    /// and rulesets, per open PR its detail, check runs and statuses, the date of each new head, then the scheduled runs
    /// and the issues.
    static int need(int bases, int stacked, int open, int newHeads) {
        return PULLS_PAGES + PER_BASE * bases + PER_STACKED * stacked + PER_OPEN * open + newHeads + FIXED_NEED;
    }

    private List<BranchName> stackedBases(List<PullRequest> open) {
        return open.stream()
                   .map(PullRequest::base)
                   .distinct()
                   .filter(base -> !bases.contains(base))
                   .toList();
    }

    private int newHeads(List<PullRequest> open) {
        return (int) open.stream()
                         .map(PullRequest::head)
                         .distinct()
                         .filter(head -> !committed.containsKey(head))
                         .count();
    }

    /// Every exit settles the tick: a synchronous throw fails the cycle, and a cycle past the deadline is settled as timed
    /// out. `run` itself is never failed by the deadline: it settles when its own steps do.
    private Promise<SourceState> cycle(Cycle cycle, Promise<Ran> run) {
        return bounded(run).fold(ran -> settled(cycle, ran))
                      .flatMap(state -> recorded(cycle, state).timeout(TimeSpan.fromDuration(deadline)));
    }

    /// The cycle's run, a synchronous throw as its failure.
    private Promise<Ran> started(Cycle cycle) {
        return Result.lift(Causes::fromThrowable,
                           () -> run(cycle))
                     .async()
                     .flatMap(run -> run);
    }

    /// The run with the deadline as a [CoreError.Timeout], on a promise of its own: `Promise.timeout` fails the promise it
    /// is called on, so applied to `run` it would settle the run, and free the guard, at the deadline.
    private Promise<Ran> bounded(Promise<Ran> run) {
        return run.map(ran -> ran)
                  .timeout(TimeSpan.fromDuration(deadline));
    }

    /// The skipped set (reloaded after a start), then a cycle gated by its need.
    private Promise<Ran> run(Cycle cycle) {
        return skippedSet().flatMap(_ -> openPrs())
                         .map(this::need)
                         .flatMap(need -> gated(cycle, need));
    }

    /// Condition: a cycle whose need the budget does not cover sends nothing.
    private Promise<Ran> gated(Cycle cycle, int need) {
        return reads.budget()
                    .covers(need,
                            cycle.at())
               ? observed(cycle).map(_ -> Ran.ran(cycle.state(reads.budget(),
                                                              skippedPrs.get().or(Map.of()),
                                                              watch.staleReason()),
                                                  need))
               : Promise.success(Ran.ran(budgetShort(cycle, need), need));
    }

    private SourceState budgetShort(Cycle cycle, int need) {
        return new SourceState.RateLimited(reads.budget().until(cycle.at(), need),
                                           cycle.at());
    }

    private Promise<SourceState> settled(Cycle cycle, Result<Ran> ran) {
        return ran.fold(cause -> unfinished(cycle, cause),
                        done -> Promise.success(paced(cycle, done.state(), done.need())));
    }

    /// FER: a cycle past its deadline degrades forward to `GITHUB-DOWN(cycle timed out)`, shown as any failed cycle,
    /// and its late steps are stopped ([Cycle#live]); any other failure (the store's) fails the cycle as itself.
    private Promise<SourceState> unfinished(Cycle cycle, Cause cause) {
        return switch (cause) {
            case CoreError.Timeout _ -> Promise.success(timedOut(cycle));
            default -> cause.promise();
        };
    }

    private SourceState timedOut(Cycle cycle) {
        cycle.expired().set(true);

        return paced(cycle,
                     new SourceState.GithubDown(TIMED_OUT, cycle.at()),
                     0);
    }

    private SourceState paced(Cycle cycle, SourceState state, int need) {
        pace.set(Pace.pace(state,
                           cycle.at(),
                           reads.budget().period(need, PERIOD)));

        return state;
    }

    private Promise<SourceState> recorded(Cycle cycle, SourceState state) {
        var continued = last.get().map(previous -> SourceState.continued(previous, state)).or(state);

        last.set(some(continued));

        return store.sources()
                    .record(Source.GITHUB, continued)
                    .map(_ -> continued);
    }

    private Promise<Unit> observed(Cycle cycle) {
        watch.begin();

        return cycle.live(() -> watch.sampled(reads,
                                              cycle.at()))
                    .flatMap(_ -> cycle.live(() -> pulls(cycle)))
                    .flatMap(_ -> cycle.live(this::openPrs))
                    .flatMap(open -> perOpen(cycle, open))
                    .flatMap(_ -> cycle.live(() -> baseReads(cycle)))
                    .flatMap(_ -> cycle.live(() -> watch.read(reads,
                                                              repo,
                                                              bases,
                                                              cycle.at())))
                    .flatMap(_ -> cycle.live(() -> feedReads(cycle)));
    }

    /// The change feed's reads: every repository, `[github] repo` first, through one path ([GitHubFeed#repoFeeds]), then
    /// the re-read. The feed never reads the poller's own check reads, so the first repository is judged exactly as a
    /// `[[repos]]` one (lead ruling: the control repository proves pragmatica's lane only if the lane is the same).
    private Promise<Unit> feedReads(Cycle cycle) {
        return feed.map(reading -> feedReads(cycle, reading))
                   .or(unitPromise());
    }

    private Promise<Unit> feedReads(Cycle cycle, GitHubFeed reading) {
        return reading.repoFeeds(cycle)
                      .flatMap(_ -> cycle.live(() -> reading.rereads(cycle)));
    }

    /// The PRs skipped and not decoded since, kept across cycles; after a start, reloaded once from the stored github
    /// state, whose `Incomplete` reason lists them.
    private Promise<Map<PrNumber, String>> skippedSet() {
        return skippedPrs.get()
                         .map(Promise::success)
                         .or(this::reloaded);
    }

    private Promise<Map<PrNumber, String>> reloaded() {
        return store.sources()
                    .all()
                    .map(GitHubPoller::storedSkipped)
                    .map(this::kept);
    }

    private Map<PrNumber, String> kept(Map<PrNumber, String> skipped) {
        skippedPrs.set(some(skipped));

        return skipped;
    }

    static Map<PrNumber, String> storedSkipped(List<SourceStatus> all) {
        return Option.from(all.stream().filter(status -> status.source()
                                                               .equals(Source.GITHUB)).findFirst())
                     .map(status -> skippedIn(status.state()))
                     .or(Map.of());
    }

    /// The set an `Incomplete` github state lists; every other state lists none.
    static Map<PrNumber, String> skippedIn(SourceState state) {
        return switch (state) {
            case SourceState.Incomplete(var reason, var _) -> sorted(SKIPPED.matcher(reason).results().flatMap(GitHubPoller::skippedEntry));
            default -> Map.of();
        };
    }

    private static Stream<Map.Entry<PrNumber, String>> skippedEntry(MatchResult found) {
        return Number.parseLong(found.group(1))
                     .flatMap(PrNumber::prNumber)
                     .option()
                     .stream()
                     .map(number -> Map.entry(number,
                                              found.group(2)));
    }

    /// A PR leaves the skipped set only when a read decodes it; each PR skipped now joins it with its latest reason.
    static Map<PrNumber, String> skippedAfter(Map<PrNumber, String> before,
                                              List<PullRequest> decoded,
                                              List<PullsPage.Skipped> found) {
        var read = decoded.stream().map(PullRequest::number).collect(Collectors.toSet());

        return sorted(Stream.concat(before.keySet()
                                          .stream()
                                          .filter(Predicate.not(read::contains))
                                          .map(number -> Map.entry(number,
                                                                   before.get(number))),
                                    found.stream().map(skipped -> Map.entry(skipped.number(), skipped.reason()))));
    }

    private static Map<PrNumber, String> sorted(Stream<Map.Entry<PrNumber, String>> entries) {
        return entries.collect(Collectors.toMap(Map.Entry::getKey,
                                                Map.Entry::getValue,
                                                (_, latest) -> latest,
                                                () -> new TreeMap<>(BY_NUMBER)));
    }

    private Promise<List<PullRequest>> openPrs() {
        return store.prs()
                    .all()
                    .map(GitHubPoller::openOnly);
    }

    private static List<PullRequest> openOnly(List<PullRequest> all) {
        return all.stream()
                  .filter(pr -> pr.state() == PrState.OPEN)
                  .toList();
    }

    // pulls (spec §4.1)
    private Promise<Unit> pulls(Cycle cycle) {
        return store.prs()
                    .backfillDone()
                    .flatMap(done -> pullsAfter(cycle, done));
    }

    private Promise<Unit> pullsAfter(Cycle cycle, Option<Instant> backfillDone) {
        return backfillDone.isPresent()
               ? incremental(cycle)
               : backfill(cycle);
    }

    private Promise<Unit> backfill(Cycle cycle) {
        return cycle.attempt(reads.pages(path(PULLS),
                                         this::page,
                                         _ -> true))
                    .flatMap(read -> whenRead(read,
                                              pages -> stored(cycle, pages, true)));
    }

    private Promise<Unit> incremental(Cycle cycle) {
        return store.prs()
                    .highWater()
                    .flatMap(mark -> incrementalFrom(cycle, mark))
                    .flatMap(read -> whenRead(read,
                                              pages -> stored(cycle, pages, false)));
    }

    private Promise<Option<List<PullsPage.Page>>> incrementalFrom(Cycle cycle, Option<Instant> mark) {
        return cycle.attempt(reads.pages(path(PULLS), this::page, page -> reachesBack(page, mark)));
    }

    private Result<PullsPage.Page> page(String body) {
        return PullsPage.decode(body, repo);
    }

    /// The next page may still hold a PR updated at or after the mark when this page's oldest one is. No mark yet:
    /// every page.
    static boolean reachesBack(PullsPage.Page page, Option<Instant> mark) {
        return mark.map(at -> reaches(page, at))
                   .or(true);
    }

    private static boolean reaches(PullsPage.Page page, Instant mark) {
        return oldest(page).filter(oldest -> !oldest.isBefore(mark))
                     .isPresent();
    }

    private static Option<Instant> oldest(PullsPage.Page page) {
        return Option.from(updatedAts(page).min(Instant::compareTo));
    }

    /// A skipped PR's `updated_at` counts too: a page of skipped PRs still pages on and still advances the mark.
    private static Stream<Instant> updatedAts(PullsPage.Page page) {
        return Stream.concat(page.pulls().stream().map(PullRequest::updatedAt),
                             page.skipped().stream().flatMap(skipped -> skipped.updatedAt()
                                                                               .stream()));
    }

    /// The pages are written only when every one was read and decoded, so a broken page keeps the old rows and the
    /// mark, and no PR between the mark and the broken page is passed over.
    private Promise<Unit> stored(Cycle cycle, List<PullsPage.Page> pages, boolean backfill) {
        var pulls = pages.stream().flatMap(page -> page.pulls()
                                                       .stream()).toList();

        skippedPrs.set(some(skippedAfter(skippedPrs.get().or(Map.of()),
                                         pulls,
                                         pages.stream().flatMap(page -> page.skipped()
                                                                            .stream()).toList())));

        return mergedBy(cycle, pulls).flatMap(mergers -> store.prs()
                                                              .upsert(pulls,
                                                                      mergers,
                                                                      cycle.at()))
                       .flatMap(_ -> advanced(pages))
                       .flatMap(_ -> finished(cycle, backfill));
    }

    /// The pulls list carries no `merged_by`, so each PR stored open and listed merged has its
    /// `pulls/{n}` read before the upsert; its `merged_by.login` goes into the `PR_STATE` row. FER: a failed read is noted
    /// as every read is, and degrades forward to no `mergedBy` key; the merge itself is still recorded.
    private Promise<Map<PrNumber, String>> mergedBy(Cycle cycle, List<PullRequest> pulls) {
        return openPrs().map(open -> justMerged(open, pulls))
                      .flatMap(merged -> mergers(cycle, merged));
    }

    /// The PRs the table holds open that the pages list merged, each once.
    static List<PrNumber> justMerged(List<PullRequest> open, List<PullRequest> listed) {
        var stored = open.stream().map(PullRequest::number).collect(Collectors.toSet());

        return listed.stream()
                     .filter(pr -> pr.state() == PrState.MERGED && stored.contains(pr.number()))
                     .map(PullRequest::number)
                     .distinct()
                     .toList();
    }

    private Promise<Map<PrNumber, String>> mergers(Cycle cycle, List<PrNumber> merged) {
        var found = new ConcurrentHashMap<PrNumber, String>();

        return sequentially(cycle, merged, number -> merger(cycle, number, found)).map(_ -> Map.copyOf(found));
    }

    private Promise<Unit> merger(Cycle cycle, PrNumber number, Map<PrNumber, String> found) {
        return cycle.attempt(reads.parsed(path("pulls/" + number.value()),
                                          PullRead::mergedBy))
                    .map(read -> read.flatMap(login -> login)
                                     .onPresent(login -> found.put(number, login)))
                    .mapToUnit();
    }

    private Promise<Unit> advanced(List<PullsPage.Page> pages) {
        return Option.from(pages.stream().flatMap(GitHubPoller::updatedAts).max(Instant::compareTo))
                     .map(mark -> store.prs()
                                       .highWater(mark))
                     .or(unitPromise());
    }

    private Promise<Unit> finished(Cycle cycle, boolean backfill) {
        return backfill
               ? store.prs()
                      .backfillDone(cycle.at())
               : unitPromise();
    }

    // per open PR (spec §4.1, §4.3, §4.4)
    private Promise<Unit> perOpen(Cycle cycle, List<PullRequest> open) {
        return sequentially(cycle,
                            open,
                            pr -> detail(cycle, pr)).map(_ -> forgotten(open))
                           .flatMap(_ -> requiredLists(cycle, open))
                           .flatMap(lists -> sequentially(cycle,
                                                          open,
                                                          pr -> cells(cycle, pr, lists)));
    }

    private Promise<Unit> detail(Cycle cycle, PullRequest pr) {
        return committedAt(cycle,
                           pr.head()).flatMap(date -> cycle.attempt(mergeability(pr.number(),
                                                                                 date)))
                          .flatMap(read -> whenRead(read,
                                                    detail -> store.prs()
                                                                   .openDetail(pr.number(),
                                                                               detail,
                                                                               cycle.at())));
    }

    private Promise<OpenDetail> mergeability(PrNumber number, Option<Instant> committedAt) {
        return reads.parsed(path("pulls/" + number.value()), body -> OpenDetail.decode(body, committedAt));
    }

    /// Once per head SHA: a known date is not asked again (an unauthenticated 304 still costs 1 of 60).
    private Promise<Option<Instant>> committedAt(Cycle cycle, Sha head) {
        return option(committed.get(head)).map(known -> Promise.success(some(known)))
                     .or(() -> fetchedDate(cycle, head));
    }

    private Promise<Option<Instant>> fetchedDate(Cycle cycle, Sha head) {
        return cycle.attempt(reads.parsed(path("commits/" + head.value()),
                                          OpenDetail::committedAt))
                    .map(date -> remembered(head, date));
    }

    private Option<Instant> remembered(Sha head, Option<Instant> date) {
        return date.onPresent(known -> committed.put(head, known));
    }

    /// Keeps the dates of the open heads only.
    private Unit forgotten(List<PullRequest> open) {
        committed.keySet().retainAll(open.stream().map(PullRequest::head).toList());

        return unit();
    }

    private Promise<Map<BranchName, RequiredChecks>> requiredLists(Cycle cycle, List<PullRequest> open) {
        var lists = new ConcurrentHashMap<BranchName, RequiredChecks>();

        return sequentially(cycle, basesToRead(open), base -> required(cycle, base, lists)).map(_ -> Map.copyOf(lists));
    }

    /// The configured bases, then each other base an open PR targets (stacked), each once.
    private List<BranchName> basesToRead(List<PullRequest> open) {
        return Stream.concat(bases.stream(),
                             open.stream().map(PullRequest::base))
                     .distinct()
                     .toList();
    }

    private Promise<Unit> required(Cycle cycle, BranchName base, Map<BranchName, RequiredChecks> lists) {
        return reads.parsed(path("branches/" + segment(base)),
                            BranchRead::decode)
                    .fold(branch -> withRulesets(cycle,
                                                 base,
                                                 cycle.noted(branch),
                                                 lists));
    }

    private Promise<Unit> withRulesets(Cycle cycle,
                                       BranchName base,
                                       Result<BranchRead> branch,
                                       Map<BranchName, RequiredChecks> lists) {
        return reads.parsed(path("rules/branches/" + segment(base)),
                            body -> watch.seen(base,
                                               bases.contains(base),
                                               body,
                                               Rulesets.decode(body)))
                    .fold(rules -> resolved(cycle,
                                            base,
                                            RequiredChecks.resolve(branch,
                                                                   cycle.noted(rules),
                                                                   bases.contains(base)),
                                            lists));
    }

    private Promise<Unit> resolved(Cycle cycle,
                                   BranchName base,
                                   RequiredChecks list,
                                   Map<BranchName, RequiredChecks> lists) {
        lists.put(base, list);

        return store.checks()
                    .required(base,
                              list,
                              cycle.at());
    }

    /// Condition: only a [RequiredChecks.Required] list has cells, so only then are the head's checks read. Any other
    /// list writes no cells, which also clears an older head's.
    private Promise<Unit> cells(Cycle cycle, PullRequest pr, Map<BranchName, RequiredChecks> lists) {
        return switch (option(lists.get(pr.base())).or(new RequiredChecks.Unknown("not read"))) {
            case RequiredChecks.Required list -> checkReads(cycle, pr, list);
            default -> cellsWritten(cycle, pr, List.of());
        };
    }

    private Promise<Unit> checkReads(Cycle cycle, PullRequest pr, RequiredChecks.Required list) {
        return checkRuns(pr.head()).fold(runs -> withStatuses(cycle, pr, list, cycle.noted(runs)));
    }

    /// A failed read reaches [CheckCells#cells] as a failure, so those cells read `UNKNOWN` at this head, never the old
    /// head's values.
    private Promise<Unit> withStatuses(Cycle cycle,
                                       PullRequest pr,
                                       RequiredChecks.Required list,
                                       Result<List<CheckRun>> runs) {
        return statuses(pr.head()).fold(statuses -> cellsWritten(cycle,
                                                                 pr,
                                                                 CheckCells.cells(list, runs, cycle.noted(statuses))));
    }

    private Promise<Unit> cellsWritten(Cycle cycle, PullRequest pr, List<Cell> cells) {
        return store.checks()
                    .cells(pr.number(),
                           pr.head(),
                           cells,
                           cycle.at())
                    .mapToUnit();
    }

    /// Every page, joined in read order: [CheckCells] keeps the last copy of an id listed twice.
    private Promise<List<CheckRun>> checkRuns(Sha head) {
        return reads.pages(path("commits/" + head.value() + "/check-runs?filter=all&per_page=100"),
                           CheckRun::decode,
                           _ -> true)
                    .map(GitHubPoller::joined);
    }

    private Promise<List<CommitStatus>> statuses(Sha head) {
        return reads.pages(path("commits/" + head.value() + "/statuses?per_page=100"),
                           CommitStatus::decode,
                           _ -> true)
                    .map(GitHubPoller::joined);
    }

    static <T> List<T> joined(List<List<T>> pages) {
        return pages.stream()
                    .flatMap(List::stream)
                    .toList();
    }

    // base reads (spec §4.2)
    private Promise<Unit> baseReads(Cycle cycle) {
        return sequentially(cycle,
                            bases,
                            base -> runs(cycle,
                                         base.value(),
                                         "actions/runs?branch=" + segment(base) + "&per_page=20")).flatMap(_ -> runs(cycle,
                                                                                                                     SCHEDULE,
                                                                                                                     "actions/runs?event=schedule&per_page=20"))
                           .flatMap(_ -> issues(cycle));
    }

    private Promise<Unit> runs(Cycle cycle, String scope, String query) {
        return cycle.attempt(reads.parsed(path(query),
                                          WorkflowRun::decode))
                    .flatMap(read -> whenRead(read,
                                              found -> store.bases()
                                                            .runs(scope,
                                                                  baseRuns(found,
                                                                           scope,
                                                                           cycle.at()))));
    }

    private static List<BaseView.Run> baseRuns(List<WorkflowRun> found, String scope, Instant at) {
        return found.stream()
                    .map(run -> new BaseView.Run(run.id(),
                                                 scope,
                                                 run.name(),
                                                 run.status(),
                                                 run.conclusion(),
                                                 run.headSha(),
                                                 run.htmlUrl(),
                                                 run.createdAt(),
                                                 at))
                    .toList();
    }

    private Promise<Unit> issues(Cycle cycle) {
        return cycle.attempt(reads.parsed(path("issues?state=open&creator=app/github-actions"),
                                          BotIssue::decode))
                    .flatMap(read -> whenRead(read,
                                              found -> store.bases()
                                                            .issues(botIssues(found,
                                                                              cycle.at()))));
    }

    private static List<BaseView.Issue> botIssues(List<BotIssue> found, Instant at) {
        return found.stream()
                    .map(issue -> new BaseView.Issue(issue.number(),
                                                     issue.title(),
                                                     issue.htmlUrl(),
                                                     issue.createdAt(),
                                                     at))
                    .toList();
    }

    // helpers
    private String path(String relative) {
        return "/repos/" + repo + "/" + relative;
    }

    /// A branch name as one path segment: `/` and every other reserved character percent-encoded.
    static String segment(BranchName branch) {
        return URLEncoder.encode(branch.value(),
                                 StandardCharsets.UTF_8)
                         .replace("+", "%20");
    }

    /// A read that failed was noted; nothing is written for it.
    static <T> Promise<Unit> whenRead(Option<T> read, Fn1<Promise<Unit>, T> write) {
        return read.map(write)
                   .or(unitPromise());
    }

    /// Iteration: one item after another, so the budget gate sees each request in turn; none after the deadline.
    static <T> Promise<Unit> sequentially(Cycle cycle, List<T> items, Fn1<Promise<Unit>, T> step) {
        return items.stream()
                    .reduce(unitPromise(),
                            (chain, item) -> chain.flatMap(_ -> cycle.live(() -> step.apply(item))),
                            (first, second) -> second);
    }

    /// GitHub reported its rate limit spent: kiln's own refusal (the reserve, or a secondary limit), or a 403 or 429
    /// while nothing remains or a secondary limit holds.
    private static boolean rateLimited(Cause problem, RateBudget budget, Instant at) {
        return switch (problem) {
            case GitHubReadError.BudgetSpent _ -> true;
            case GitHubError.HttpStatus http -> (http.status() == 403 || http.status() == 429) && (budget.exhausted() || budget.blocked(at));
            default -> false;
        };
    }

    /// The bare status when GitHub answered one (`GITHUB-DOWN(502)`), else the failure's message on one line.
    private static String describe(Cause cause) {
        return switch (cause) {
            case GitHubError.HttpStatus http -> Integer.toString(http.status());
            default -> BLANKS.matcher(cause.message()).replaceAll(" ");
        };
    }
}
