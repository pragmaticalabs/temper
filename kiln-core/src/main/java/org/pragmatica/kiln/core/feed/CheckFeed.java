package org.pragmatica.kiln.core.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.CheckCells;
import org.pragmatica.kiln.core.github.CheckRun;
import org.pragmatica.kiln.core.github.CommitStatus;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// `CHECK_CONCLUDED` and `CHECKS_SETTLED` at a PR head (design §2.5, review H2, NOTE 8).
///
/// **Observations.** Per required check, the runs named as its context (from its app, when one is pinned) are taken per
/// check suite, the run of each suite with the greatest check-run id standing for it (lead ruling 2026-11-04: a
/// queued rerun has no `started_at` yet but the highest id), and an unpinned check also has
/// the latest commit status of its context. A suite's workflow run (from the head's `actions/runs?head_sha=`) gives the
/// run id, `event` and `run_attempt`. Each suite is kept apart, so a push run never stands in for the PR run of the same
/// name (H2).
///
/// **Concluded.** The subject is the check's context (what a waiter matches, design §2.6: `check-concluded control`);
/// the payload's `check` is its label, with the app when one is pinned. An observation with a terminal conclusion is reported once per fingerprint (conclusion, attempt, check
/// id): a first terminal conclusion, a changed one, and a rerun's are each one `CHECK_CONCLUDED`; a still-pending
/// observation reports nothing and is not remembered, so its later conclusion is new.
///
/// **Settled** (NOTE 8) judges, per required check, only the **qualifying** observations (§4.2 G4: a commit status, or
/// a run whose workflow run's event is `pull_request` and whose `pull_requests[]` lists this PR; a fork PR's run lists
/// none, so it never qualifies, R2-b), and of those, per distinct workflow (its `workflow_id`, else its name), only the
/// latest run by `run_number`, latest attempt within it (R2-3): a cancelled or failed older run of a workflow is
/// superseded by its newer run, while a failing run of another workflow with a colliding job name still counts. Then:
/// - a required check whose chosen observation is still pending blocks settling (lead ruling: never `missing`);
/// - `concluded`: every required check has a chosen observation, all terminal; `green` when every one is `success`,
///   else `red`;
/// - `window`: some required check has no qualifying observation at all, every workflow run at the head has completed
///   and [#SETTLE] has passed since the last completion (since kiln first saw the head, when it has no run): `red`
///   when a chosen observation is not `success` (lead ruling: red wins over missing), else `missing`. A path-filtered
///   workflow that never runs and a run not yet queued look the same until then; that is why the window exists.
///
/// A settled outcome is reported when it differs from the one last reported for this head.
public sealed interface CheckFeed {
    String CHECK_RUN = "check-run";
    String STATUS = "status";
    String PULL_REQUEST = "pull_request";
    /// How long after the last completion a head with a missing check is called settled (design §2.5).
    Duration SETTLE = Duration.ofMinutes(10);
    String GREEN = "green";
    String RED = "red";
    String MISSING = "missing";
    String CONCLUDED = "concluded";
    String WINDOW = "window";
    /// Joins a check's key and its suite in an observation key (the check key itself may hold U+001F).
    String SEPARATOR = "\u001e";

    /// One required check as one suite (or its commit status) shows it at the head: `conclusion` none while pending.
    record Observation(RequiredCheck check,
                       String source,
                       String suite,
                       Option<String> conclusion,
                       Option<String> description,
                       Option<Long> checkId,
                       Option<Long> suiteId,
                       Option<WorkflowRun> run,
                       boolean qualifying) {
        public String key() {
            return check.key() + SEPARATOR + suite;
        }

        public boolean terminal() {
            return conclusion.isPresent();
        }

        /// The workflow this observation stands for (R2-3): its run's `workflow_id`, else the run's name; a status, and
        /// a check run with no workflow run, each stand for their own.
        public String workflow() {
            return run.map(CheckFeed::workflowOf)
                      .or(suite);
        }

        public boolean success() {
            return conclusion.filter(Cell.SUCCESS::equals)
                             .isPresent();
        }

        public Option<Long> attempt() {
            return run.map(WorkflowRun::attempt);
        }

        /// What a repeat must differ in to be reported again.
        public String fingerprint() {
            return conclusion.or("") + "|" + attempt().or(0L) + "|" + checkId.or(0L);
        }
    }

    /// The outcome of a settled head, the rule that settled it, and the checks missing or not green.
    record Settled(String outcome, String rule, List<String> missing, List<String> red) {
        public Settled {
            missing = List.copyOf(missing);
            red = List.copyOf(red);
        }
    }

    /// Every observation of `list`'s checks at PR `pr`'s head: runs and statuses each kept once by id (pagination may
    /// list one twice: the last copy is kept), runs per suite, each marked qualifying or not for `pr` (G4).
    static List<Observation> observed(RequiredChecks.Required list,
                                      PrNumber pr,
                                      List<CheckRun> runs,
                                      List<CommitStatus> statuses,
                                      List<WorkflowRun> headRuns) {
        var suites = bySuite(headRuns);
        var uniqueRuns = unique(runs, CheckRun::id);
        var uniqueStatuses = unique(statuses, CommitStatus::id);

        return list.checks()
                   .stream()
                   .flatMap(check -> Stream.concat(runObservations(check, pr, uniqueRuns, suites),
                                                   statusObservation(check, uniqueStatuses).stream()))
                   .toList();
    }

    private static Map<Long, WorkflowRun> bySuite(List<WorkflowRun> headRuns) {
        return headRuns.stream()
                       .flatMap(CheckFeed::suiteEntry)
                       .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, CheckFeed::laterAttempt));
    }

    private static Stream<Map.Entry<Long, WorkflowRun>> suiteEntry(WorkflowRun run) {
        return run.suiteId()
                  .map(suite -> Map.entry(suite, run))
                  .stream();
    }

    private static WorkflowRun laterAttempt(WorkflowRun first, WorkflowRun second) {
        return second.attempt() >= first.attempt()
               ? second
               : first;
    }

    private static <T> List<T> unique(List<T> items, Function<T, Long> id) {
        return List.copyOf(items.stream()
                                .collect(Collectors.toMap(id,
                                                          Function.identity(),
                                                          (_, last) -> last,
                                                          LinkedHashMap::new))
                                .values());
    }

    private static Stream<Observation> runObservations(RequiredCheck check,
                                                       PrNumber pr,
                                                       List<CheckRun> runs,
                                                       Map<Long, WorkflowRun> suites) {
        return latestPerSuite(check, runs).stream()
                             .map(run -> runObservation(check, pr, run, suites));
    }

    /// The run of `check` with the greatest check-run id in each suite, suites in key order.
    private static Collection<CheckRun> latestPerSuite(RequiredCheck check, List<CheckRun> runs) {
        return runs.stream()
                   .filter(run -> named(run, check))
                   .collect(Collectors.toMap(CheckFeed::suiteOf,
                                             Function.identity(),
                                             BinaryOperator.maxBy(Comparator.comparingLong(CheckRun::id)),
                                             TreeMap::new))
                   .values();
    }

    private static boolean named(CheckRun run, RequiredCheck check) {
        return run.name()
                  .equals(check.context().value()) && fromRequiredApp(run, check);
    }

    private static boolean fromRequiredApp(CheckRun run, RequiredCheck check) {
        return check.appId()
                    .isEmpty() || run.app()
                                     .equals(check.appId());
    }

    private static String suiteOf(CheckRun run) {
        return run.suiteId()
                  .map(suite -> "suite:" + suite)
                  .or(() -> "run:" + run.id());
    }

    private static Observation runObservation(RequiredCheck check,
                                              PrNumber pr,
                                              CheckRun run,
                                              Map<Long, WorkflowRun> suites) {
        var workflowRun = run.suiteId().flatMap(suite -> option(suites.get(suite)));

        return new Observation(check,
                               CHECK_RUN,
                               suiteOf(run),
                               runConclusion(run),
                               none(),
                               some(run.id()),
                               run.suiteId(),
                               workflowRun,
                               workflowRun.filter(found -> forPr(found, pr)).isPresent());
    }

    /// G4: a `pull_request` run that lists `pr` in its `pull_requests[]`.
    private static boolean forPr(WorkflowRun run, PrNumber pr) {
        return run.event()
                  .filter(PULL_REQUEST::equals)
                  .isPresent() && run.lists(pr.value());
    }

    private static String workflowOf(WorkflowRun run) {
        return run.workflowId()
                  .map(id -> "workflow:" + id)
                  .or(() -> "name:" + run.name());
    }

    private static Option<String> runConclusion(CheckRun run) {
        return CheckCells.COMPLETED.equals(run.status())
               ? some(run.conclusion().or(CheckCells.COMPLETED))
               : none();
    }

    /// Statuses answer only a check with no app pinned, as in [CheckCells].
    private static Option<Observation> statusObservation(RequiredCheck check, List<CommitStatus> statuses) {
        return check.appId()
                    .isPresent()
               ? none()
               : Option.from(statuses.stream()
                                     .filter(status -> status.context()
                                                             .equals(check.context().value()))
                                     .max(CheckCells.LATEST_STATUS)).map(status -> statusObservation(check, status));
    }

    private static Observation statusObservation(RequiredCheck check, CommitStatus status) {
        return new Observation(check,
                               STATUS,
                               STATUS,
                               statusConclusion(status),
                               status.description(),
                               some(status.id()),
                               none(),
                               none(),
                               true);
    }

    /// A pending status is not terminal; a `success` whose description says skipped is `success*`, never success.
    private static Option<String> statusConclusion(CommitStatus status) {
        return CheckCells.WAITING.contains(status.state())
               ? none()
               : some(skippedOr(status));
    }

    private static String skippedOr(CommitStatus status) {
        return status.description()
                     .filter(description -> Cell.SUCCESS.equals(status.state()) && CheckCells.SKIP.matcher(description).find())
                     .map(_ -> CheckCells.SKIPPED_SUCCESS)
                     .or(status.state());
    }

    /// The fingerprints to remember: the terminal observations only. Keys are unique by construction (one per check and
    /// suite), so no merge is needed.
    static Map<String, String> fingerprints(List<Observation> observed) {
        return observed.stream()
                       .filter(Observation::terminal)
                       .collect(Collectors.toMap(Observation::key, Observation::fingerprint));
    }

    /// One `CHECK_CONCLUDED` per terminal observation whose fingerprint is not the stored one. `version` is the
    /// required-list version the check was judged against (NOTE 9).
    static List<FeedChange> concluded(PrNumber pr,
                                      Sha head,
                                      Map<String, String> stored,
                                      List<Observation> observed,
                                      long version,
                                      Instant at) {
        return observed.stream()
                       .filter(Observation::terminal)
                       .filter(observation -> !observation.fingerprint()
                                                          .equals(stored.get(observation.key())))
                       .map(observation -> concludedChange(pr,
                                                           head,
                                                           option(stored.get(observation.key())),
                                                           observation,
                                                           version,
                                                           at))
                       .toList();
    }

    private static FeedChange concludedChange(PrNumber pr,
                                              Sha head,
                                              Option<String> stored,
                                              Observation observation,
                                              long version,
                                              Instant at) {
        var conclusion = observation.conclusion().or("");

        return new FeedChange(FeedKind.CHECK_CONCLUDED,
                              some(pr),
                              observation.check().context().value(),
                              stored.map(CheckFeed::conclusionOf).or(FeedChange.NONE),
                              conclusion,
                              at,
                              FeedChange.payload("check",
                                                 observation.check().label(),
                                                 "source",
                                                 observation.source(),
                                                 "conclusion",
                                                 conclusion,
                                                 "description",
                                                 observation.description(),
                                                 "required",
                                                 true,
                                                 "listVersion",
                                                 version,
                                                 "head",
                                                 head.value(),
                                                 "runId",
                                                 observation.run().map(WorkflowRun::id),
                                                 "suiteId",
                                                 observation.suiteId(),
                                                 "event",
                                                 observation.run().flatMap(WorkflowRun::event),
                                                 "attempt",
                                                 observation.attempt(),
                                                 "workflow",
                                                 observation.run().map(WorkflowRun::name)));
    }

    private static String conclusionOf(String fingerprint) {
        return fingerprint.substring(0, fingerprint.indexOf('|'));
    }

    /// The §2.5 rule, at `at`, for a head kiln first saw at `firstSeen`.
    static Option<Settled> settled(RequiredChecks.Required list,
                                   List<Observation> observed,
                                   List<WorkflowRun> headRuns,
                                   Instant firstSeen,
                                   Instant at) {
        var chosen = chosen(observed);
        var checks = list.checks();
        var missing = labels(checks.stream().filter(check -> of(check, chosen).findAny()
                                                               .isEmpty()));
        var red = labels(checks.stream().filter(check -> of(check, chosen).anyMatch(CheckFeed::failed)));
        var pending = checks.stream()
                            .anyMatch(check -> of(check, chosen).anyMatch(observation -> !observation.terminal()));

        return pending
               ? none()
               : judged(missing, red, headRuns, firstSeen, at);
    }

    private static Option<Settled> judged(List<String> missing,
                                          List<String> red,
                                          List<WorkflowRun> headRuns,
                                          Instant firstSeen,
                                          Instant at) {
        return missing.isEmpty()
               ? some(new Settled(outcome(red, GREEN), CONCLUDED, missing, red))
               : windowed(missing, red, headRuns, firstSeen, at);
    }

    private static Option<Settled> windowed(List<String> missing,
                                            List<String> red,
                                            List<WorkflowRun> headRuns,
                                            Instant firstSeen,
                                            Instant at) {
        return windowClosed(headRuns, firstSeen, at)
               ? some(new Settled(outcome(red, MISSING), WINDOW, missing, red))
               : none();
    }

    /// `red` when a check is not green, else `otherwise`.
    private static String outcome(List<String> red, String otherwise) {
        return red.isEmpty()
               ? otherwise
               : RED;
    }

    /// Every run at the head completed, and [#SETTLE] passed since the last completion (a run's `updated_at`, else its
    /// `created_at`), or since `firstSeen` when the head has no run.
    static boolean windowClosed(List<WorkflowRun> headRuns, Instant firstSeen, Instant at) {
        return headRuns.stream()
                       .allMatch(WorkflowRun::completed) && !at.isBefore(lastCompletion(headRuns, firstSeen).plus(SETTLE));
    }

    private static Instant lastCompletion(List<WorkflowRun> headRuns, Instant firstSeen) {
        return Option.from(headRuns.stream()
                                   .map(run -> run.updatedAt()
                                                  .or(run.createdAt()))
                                   .max(Comparator.naturalOrder())).or(firstSeen);
    }

    /// R2-3: of the qualifying observations, per required check and distinct workflow, the latest by run number, then
    /// attempt.
    static List<Observation> chosen(List<Observation> observed) {
        return List.copyOf(observed.stream()
                                   .filter(Observation::qualifying)
                                   .collect(Collectors.toMap(observation -> observation.check()
                                                                                       .key() + SEPARATOR + observation.workflow(),
                                                             Function.identity(),
                                                             BinaryOperator.maxBy(LATEST),
                                                             TreeMap::new))
                                   .values());
    }

    /// The order of [#chosen]: run number (the run id when GitHub sent none), then attempt. Two chosen candidates never
    /// tie: one workflow's run number and attempt name one run, so one suite, whose check runs are already one.
    Comparator<Observation> LATEST = Comparator.comparing(CheckFeed::runNumberOf).thenComparing(observation -> observation.attempt()
                                                                                                                          .or(0L));

    private static long runNumberOf(Observation observation) {
        return observation.run()
                          .map(run -> run.runNumber()
                                         .or(run.id()))
                          .or(0L);
    }

    private static Stream<Observation> of(RequiredCheck check, List<Observation> chosen) {
        return chosen.stream()
                     .filter(observation -> observation.check()
                                                       .equals(check));
    }

    private static boolean failed(Observation observation) {
        return observation.terminal() && !observation.success();
    }

    private static List<String> labels(Stream<RequiredCheck> checks) {
        return checks.map(RequiredCheck::label)
                     .toList();
    }

    /// One `CHECKS_SETTLED` when `now` is settled with an outcome other than the one last reported for this head.
    static Option<FeedChange> settledChange(PrNumber pr,
                                            Sha head,
                                            Option<String> reported,
                                            Option<Settled> now,
                                            Instant at) {
        return now.filter(settled -> !reported.equals(some(settled.outcome())))
                  .map(settled -> new FeedChange(FeedKind.CHECKS_SETTLED,
                                                 some(pr),
                                                 head.value(),
                                                 reported.or(FeedChange.NONE),
                                                 settled.outcome(),
                                                 at,
                                                 FeedChange.payload("outcome",
                                                                    settled.outcome(),
                                                                    "rule",
                                                                    settled.rule(),
                                                                    "head",
                                                                    head.value(),
                                                                    "missing",
                                                                    settled.missing(),
                                                                    "red",
                                                                    settled.red(),
                                                                    "settleWindowS",
                                                                    SETTLE.toSeconds())));
    }

    record unused() implements CheckFeed {}
}
