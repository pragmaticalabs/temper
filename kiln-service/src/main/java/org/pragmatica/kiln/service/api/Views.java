package org.pragmatica.kiln.service.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.conflict.Prediction;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.github.PullsPage;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.conflict.ConflictBoard;
import org.pragmatica.kiln.service.github.GitHubBudget;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.PassView;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.WorkBoard;
import org.pragmatica.kiln.service.store.WorkCard;
import org.pragmatica.kiln.service.store.WorkEvent;
import org.pragmatica.kiln.service.web.Periods;
import org.pragmatica.kiln.service.web.RateBudget;
import org.pragmatica.kiln.service.web.Sources;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// The JSON of the API (spec §7, kiln-f1-design §4): plain records with nullable fields, so the shape is the record's own
/// and no mapper setting decides it. Every fact carries its `observedAt` (D-4). A value kiln does not know is `null`,
/// never 0 or `false` (§3.4: `behindBy` null is `UNKNOWN`), and every answer carries the state of each source it rests
/// on, so a missing or failed source shows in the API as it does on the pages (WP9 open item 12).
public sealed interface Views {
    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    static Result<String> json(Object value) {
        return MAPPER.writeAsString(value);
    }

    /// One source: `state` is `NONE` when nothing was ever recorded; `stale` past 3 × its period, or never read.
    record SourceJson(String source, String state, String detail, String at, String lastSuccess, boolean stale) {}

    record PrsJson(List<SourceJson> sources, List<PrJson> prs) {}

    record PrJson(long number,
                  String title,
                  String state,
                  boolean draft,
                  String base,
                  String headRef,
                  String headRepo,
                  String head,
                  String updatedAt,
                  List<String> labels,
                  List<Long> closes,
                  List<Long> partOf,
                  String observedAt,
                  DetailJson detail,
                  BehindJson behind,
                  RequiredJson required,
                  List<CellJson> cells,
                  GateJson gate,
                  List<LinkedJson> checkouts) {}

    record DetailJson(Boolean mergeable, String mergeableState, String headCommittedAt, String observedAt) {}

    /// `null` values are `UNKNOWN` (the head is not in the mirror), never 0 (K-8).
    record BehindJson(Integer behindBy, Boolean behindTreeEmpty, String observedAt) {}

    /// `kind`: `REQUIRED`, `NONE_REQUIRED` or `UNKNOWN`.
    record RequiredJson(String kind,
                        String enforcement,
                        String why,
                        String status,
                        Boolean stacked,
                        List<CheckJson> checks,
                        String observedAt) {}

    record CheckJson(String context, Long appId) {}

    /// `success` is `value == "success"`, the only success (spec §4.4).
    record CellJson(String check, String value, boolean success, int foreign, List<String> shown, String observedAt) {}

    /// `kind`: `ANSWERED` or `SCHEMA_BROKEN` (`field` names what was missing).
    record GateJson(String kind, String sha, String answer, Long job, String judge, String field, String observedAt) {}

    record LinkedJson(String host, String path, String status, String observedAt) {}

    record CheckoutsJson(List<SourceJson> sources,
                         List<CountsJson> counts,
                         int page,
                         int pages,
                         List<CheckoutJson> rows) {}

    /// A host's counts; `counts` is `null` for a host with no row observed (never all zeros).
    record CountsJson(String host,
                      Map<String, Integer> counts,
                      Long knownSizeKb,
                      int measuredRows,
                      String oldestFactsAt) {}

    record CheckoutJson(String host,
                        String path,
                        String status,
                        String activityAt,
                        String listedAt,
                        String factsPass,
                        String factsAt,
                        String head,
                        String branch,
                        Integer modified,
                        Integer untracked,
                        String op,
                        Integer stash,
                        Integer salvageRefs,
                        List<Long> processes,
                        String preservation,
                        List<String> reasons,
                        List<Long> linkedPrs,
                        String linkRule,
                        Boolean atBase,
                        String derivedAt,
                        Long sizeKb,
                        String sizeAt) {}

    record SourcesJson(List<SourceJson> sources, RateJson rate, List<SkippedJson> skipped, List<PassJson> passes) {}

    record GithubBudgetJson(List<SampleJson> samples, String mode, DriftJson drift) {}

    record SampleJson(String identity,
                      int limit,
                      int remaining,
                      int used,
                      String reset,
                      String observedAt,
                      String source) {}

    /// `fresh` is false while the latest ruleset read was incomplete: `failure` then names why and `failedCycles` counts the
    /// consecutive cycles, while `observedAt` stays the last good read.
    record DriftJson(String rulesetHash,
                     List<Long> rulesetIds,
                     List<String> teamMembers,
                     String observedAt,
                     boolean fresh,
                     String failure,
                     int failedCycles) {}

    record RateJson(int remaining, String reset, String observedAt, int kilnSpent, int hourlyCap, int reserve) {}

    record SkippedJson(long number, String reason, String observedAt) {}

    record PassJson(String host,
                    String kind,
                    String outcome,
                    int rows,
                    Integer expected,
                    String startedAt,
                    String finishedAt,
                    Integer progressK,
                    Integer progressN) {}

    record SummaryJson(List<String> lines, String asOf) {}

    /// A source as the change feed reports it: its last success as stored (`last_success_at`, kept
    /// while the source fails, never the time of the read) and its period. No state word: a reader judges staleness by
    /// age alone.
    record SourceAgeJson(String lastOkAt, long periodS) {}

    /// One `change` row: `pr` `null` for a change about no PR, `payload` the kind's extra facts (`null` for the WP1 kinds).
    record FeedChangeJson(long id,
                          String kind,
                          String repo,
                          Long pr,
                          String subject,
                          String before,
                          String after,
                          String observedAt,
                          String recordedAt,
                          Object payload) {}

    record ChangesJson(String epoch,
                       long floor,
                       long head,
                       String asOf,
                       Map<String, SourceAgeJson> sources,
                       List<FeedChangeJson> changes) {}

    record EpochJson(String epoch) {}

    /// `head` is the new epoch's: the caller restarts from 0.
    record ResetJson(EpochJson reset, long head, String asOf, Map<String, SourceAgeJson> sources) {}

    /// The ids above `from` and below `to` were pruned.
    record RangeJson(long from, long to) {}

    record GapJson(RangeJson gap,
                   String epoch,
                   long floor,
                   long head,
                   String asOf,
                   Map<String, SourceAgeJson> sources) {}

    /// The feed's source for kiln's restore-detection file (lead ruling R2-F3): its last write, never a state.
    static final String HIGH_WATER = "kiln:highwater";

    /// Every `source_state` row by its key, and [#HIGH_WATER], in key order. The key is the table's primary key, so no
    /// two rows share it, and no `source_state` key has the `kiln:` prefix.
    static Map<String, SourceAgeJson> ages(List<SourceStatus> statuses,
                                           Periods periods,
                                           Option<Instant> highWaterOkAt) {
        var ages = new TreeMap<>(statuses.stream()
                                         .collect(Collectors.toMap(status -> status.source()
                                                                                   .key(),
                                                                   status -> age(status, periods))));

        ages.put(HIGH_WATER, new SourceAgeJson(text(highWaterOkAt), ChangeFeed.HIGH_WATER_PERIOD_S));

        return ages;
    }

    private static SourceAgeJson age(SourceStatus status, Periods periods) {
        return new SourceAgeJson(text(status.lastSuccess()),
                                 periods.of(status.source()).toSeconds());
    }

    /// One of the three shapes of the change feed reply; a stored payload that is not JSON fails the answer (jsonb makes it unreachable).
    static Result<Object> feed(FeedReply reply, Map<String, SourceAgeJson> sources, Instant asOf) {
        return switch (reply) {
            case FeedReply.Listed listed -> listed(listed.feed(), sources, asOf);
            case FeedReply.Reset reset -> Result.success(new ResetJson(new EpochJson(epoch(reset.feed())),
                                                                       reset.feed().head(),
                                                                       asOf.toString(),
                                                                       sources));
            case FeedReply.Gap gap -> Result.success(new GapJson(new RangeJson(gap.from(),
                                                                               gap.feed().floor()),
                                                                 epoch(gap.feed()),
                                                                 gap.feed().floor(),
                                                                 gap.feed().head(),
                                                                 asOf.toString(),
                                                                 sources));
        };
    }

    private static Result<Object> listed(ChangeFeed feed, Map<String, SourceAgeJson> sources, Instant asOf) {
        return Result.allOf(feed.changes().stream().map(Views::feedChange).toList()).map(changes -> new ChangesJson(epoch(feed),
                                                                                                                    feed.floor(),
                                                                                                                    feed.head(),
                                                                                                                    asOf.toString(),
                                                                                                                    sources,
                                                                                                                    changes));
    }

    private static String epoch(ChangeFeed feed) {
        return feed.epoch()
                   .value()
                   .toString();
    }

    private static Result<FeedChangeJson> feedChange(ChangeFeed.Row row) {
        return payload(row.payload()).map(payload -> new FeedChangeJson(row.id(),
                                                                        row.kind(),
                                                                        row.repo(),
                                                                        row.pr().map(PrNumber::value).or((Long) null),
                                                                        row.subject(),
                                                                        row.before(),
                                                                        row.after(),
                                                                        row.observedAt().toString(),
                                                                        row.recordedAt().toString(),
                                                                        payload.or((Object) null)));
    }

    private static Result<Option<Object>> payload(Option<String> text) {
        return text.map(json -> MAPPER.readString(json, Object.class).map(Option::some))
                   .or(Result.success(Option.none()));
    }

    // ---- mapping -----------------------------------------------------------------------------------------------------
    static List<SourceJson> sources(List<Source> expected, Sources sources, Instant now) {
        return expected.stream()
                       .distinct()
                       .map(source -> source(source, sources, now))
                       .toList();
    }

    private static SourceJson source(Source source, Sources sources, Instant now) {
        var stale = sources.stale(source, now);
        var last = text(sources.lastSuccess(source));

        return sources.of(source)
                      .map(status -> stated(source,
                                            status.state(),
                                            last,
                                            stale))
                      .or(new SourceJson(source.key(),
                                         "NONE",
                                         null,
                                         null,
                                         last,
                                         stale));
    }

    private static SourceJson stated(Source source, SourceState state, String last, boolean stale) {
        return switch (state) {
            case SourceState.Ok(var at) -> new SourceJson(source.key(), "OK", null, at.toString(), last, stale);
            case SourceState.Incomplete(var reason, var at) -> new SourceJson(source.key(),
                                                                              "INCOMPLETE",
                                                                              reason,
                                                                              at.toString(),
                                                                              last,
                                                                              stale);
            case SourceState.Unreachable(var reason, var since) -> new SourceJson(source.key(),
                                                                                  "UNREACHABLE",
                                                                                  reason,
                                                                                  since.toString(),
                                                                                  last,
                                                                                  stale);
            case SourceState.RateLimited(var reset, var since) -> new SourceJson(source.key(),
                                                                                 "RATE_LIMITED",
                                                                                 "until " + reset,
                                                                                 since.toString(),
                                                                                 last,
                                                                                 stale);
            case SourceState.GithubDown(var status, var since) -> new SourceJson(source.key(),
                                                                                 "GITHUB_DOWN",
                                                                                 status,
                                                                                 since.toString(),
                                                                                 last,
                                                                                 stale);
            case SourceState.TemperDown(var reason, var since) -> new SourceJson(source.key(),
                                                                                 "TEMPER_DOWN",
                                                                                 reason,
                                                                                 since.toString(),
                                                                                 last,
                                                                                 stale);
            case SourceState.TemperSchema(var field, var since) -> new SourceJson(source.key(),
                                                                                  "TEMPER_SCHEMA",
                                                                                  field,
                                                                                  since.toString(),
                                                                                  last,
                                                                                  stale);
        };
    }

    static PrJson pr(PrView view) {
        var pr = view.pr().value();

        return new PrJson(pr.number().value(),
                          pr.title(),
                          pr.state().name(),
                          pr.draft(),
                          pr.base().value(),
                          pr.headRef().value(),
                          pr.headRepo().or((String) null),
                          pr.head().value(),
                          pr.updatedAt().toString(),
                          pr.labels(),
                          numbers(pr.closes()),
                          numbers(pr.partOf()),
                          view.pr().observedAt().toString(),
                          view.detail().map(Views::detail).or((DetailJson) null),
                          view.behind().map(Views::behind).or((BehindJson) null),
                          view.required().map(Views::required).or((RequiredJson) null),
                          view.cells().stream().map(Views::cell).toList(),
                          view.gate().map(Views::gate).or((GateJson) null),
                          view.checkouts()
                              .stream()
                              .map(linked -> new LinkedJson(linked.value().host().value(),
                                                            linked.value().path().value(),
                                                            linked.value().status().name(),
                                                            linked.observedAt().toString()))
                              .toList());
    }

    private static List<Long> numbers(List<PrNumber> numbers) {
        return numbers.stream()
                      .map(PrNumber::value)
                      .toList();
    }

    private static DetailJson detail(Observed<OpenDetail> observed) {
        var detail = observed.value();

        return new DetailJson(detail.mergeable().or((Boolean) null),
                              detail.mergeableState().or((String) null),
                              text(detail.headCommittedAt()),
                              observed.observedAt().toString());
    }

    private static BehindJson behind(Observed<BehindFacts> observed) {
        return new BehindJson(observed.value().behindBy().or((Integer) null),
                              observed.value().behindTreeEmpty().or((Boolean) null),
                              observed.observedAt().toString());
    }

    private static RequiredJson required(Observed<RequiredChecks> observed) {
        var at = observed.observedAt().toString();

        return switch (observed.value()) {
            case RequiredChecks.Required(var checks, var enforcement) -> new RequiredJson("REQUIRED",
                                                                                          enforcement,
                                                                                          null,
                                                                                          null,
                                                                                          null,
                                                                                          checks.stream().map(Views::check).toList(),
                                                                                          at);
            case RequiredChecks.NoneRequired(var why, var stacked) -> new RequiredJson("NONE_REQUIRED",
                                                                                       null,
                                                                                       why,
                                                                                       null,
                                                                                       stacked,
                                                                                       List.of(),
                                                                                       at);
            case RequiredChecks.Unknown(var status) -> new RequiredJson("UNKNOWN",
                                                                        null,
                                                                        null,
                                                                        status,
                                                                        null,
                                                                        List.of(),
                                                                        at);
        };
    }

    private static CheckJson check(RequiredCheck check) {
        return new CheckJson(check.context().value(),
                             check.appId().map(app -> app.value()).or((Long) null));
    }

    private static CellJson cell(Observed<Cell> observed) {
        var cell = observed.value();

        return new CellJson(cell.check().label(),
                            cell.value(),
                            cell.isSuccess(),
                            cell.foreign(),
                            cell.shown(),
                            observed.observedAt().toString());
    }

    private static GateJson gate(Observed<GateRead> observed) {
        var at = observed.observedAt().toString();

        return switch (observed.value()) {
            case GateRead.Answered(var sha, var answer, var job, var judge) -> new GateJson("ANSWERED",
                                                                                            sha,
                                                                                            answer,
                                                                                            job.or((Long) null),
                                                                                            judge.or((String) null),
                                                                                            null,
                                                                                            at);
            case GateRead.SchemaBroken(var field) -> new GateJson("SCHEMA_BROKEN", null, null, null, null, field, at);
        };
    }

    static CountsJson counts(HostCounts counts) {
        var byStatus = new TreeMap<String, Integer>();

        counts.counts().forEach((status, count) -> byStatus.put(status.name(), count));

        return new CountsJson(counts.host().value(),
                              byStatus,
                              counts.measuredRows() > 0
                              ? counts.knownSizeKb()
                              : null,
                              counts.measuredRows(),
                              text(counts.oldestFactsAt()));
    }

    static CheckoutJson checkout(CheckoutView view) {
        var row = view.row();
        var facts = row.facts();
        var derived = view.derived();

        return new CheckoutJson(row.host().value(),
                                row.path().value(),
                                view.status().name(),
                                row.activityAt().toString(),
                                row.listedAt().toString(),
                                row.factsPass().map(Enum::name).or((String) null),
                                text(row.factsAt()),
                                facts.flatMap(CheckoutFacts::head).map(Sha::value).or((String) null),
                                facts.flatMap(CheckoutFacts::branch).map(BranchName::value).or((String) null),
                                facts.map(CheckoutFacts::modified).or((Integer) null),
                                facts.map(CheckoutFacts::untracked).or((Integer) null),
                                facts.flatMap(CheckoutFacts::op).or((String) null),
                                facts.map(CheckoutFacts::stash).or((Integer) null),
                                facts.flatMap(CheckoutFacts::salvageRefs).or((Integer) null),
                                facts.map(CheckoutFacts::processes).or(List.of()),
                                derived.map(Views::preservation).or((String) null),
                                derived.map(Views::reasons).or(List.of()),
                                derived.map(found -> numbers(found.derived().link().prs())).or(List.of()),
                                derived.map(found -> found.derived()
                                                          .link()
                                                          .rule()
                                                          .name()).or((String) null),
                                derived.flatMap(found -> found.derived()
                                                              .atBase()).or((Boolean) null),
                                derived.map(found -> found.derivedAt()
                                                          .toString()).or((String) null),
                                row.sizeKb().or((Long) null),
                                text(row.sizeAt()));
    }

    private static String preservation(DerivedRow derived) {
        return switch (derived.derived()
                              .preservation()) {
            case Preservation.Preserved _ -> "PRESERVED";
            case Preservation.HostBareOnly _ -> "HOST_BARE_ONLY";
            case Preservation.Unpreserved _ -> "UNPRESERVED";
        };
    }

    private static List<String> reasons(DerivedRow derived) {
        return derived.derived()
                      .preservation() instanceof Preservation.Unpreserved(var reasons)
               ? reasons
               : List.of();
    }

    static GithubBudgetJson githubBudget(GitHubBudget budget) {
        var drift = budget.drift();

        return new GithubBudgetJson(budget.sample().map(Views::sample).stream().toList(),
                                    budget.mode().name(),
                                    new DriftJson(drift.rulesetHash().or((String) null),
                                                  drift.rulesetIds(),
                                                  drift.teamMembers().or((List<String>) null),
                                                  drift.observedAt().map(Instant::toString).or((String) null),
                                                  drift.fresh(),
                                                  drift.failure().or((String) null),
                                                  drift.failedCycles()));
    }

    private static SampleJson sample(GitHubBudget.Sample sample) {
        return new SampleJson(sample.identity(),
                              sample.limit(),
                              sample.remaining(),
                              sample.used(),
                              sample.reset().toString(),
                              sample.observedAt().toString(),
                              sample.source());
    }

    static Option<RateJson> rate(Option<Observed<RateBudget>> budget) {
        return budget.map(observed -> new RateJson(observed.value().remaining(),
                                                   observed.value().reset().toString(),
                                                   observed.observedAt().toString(),
                                                   observed.value().kilnSpent(),
                                                   observed.value().hourlyCap(),
                                                   observed.value().reserve()));
    }

    static SkippedJson skipped(Observed<PullsPage.Skipped> skipped) {
        return new SkippedJson(skipped.value().number().value(),
                               skipped.value().reason(),
                               skipped.observedAt().toString());
    }

    static PassJson pass(PassView pass) {
        return new PassJson(pass.host().value(),
                            pass.kind().name(),
                            pass.outcome().name(),
                            pass.rows(),
                            pass.expected().or((Integer) null),
                            pass.startedAt().toString(),
                            text(pass.finishedAt()),
                            pass.progressK().or((Integer) null),
                            pass.progressN().or((Integer) null));
    }

    private static String text(Option<Instant> at) {
        return at.map(Instant::toString)
                 .or((String) null);
    }

    /// `GET /api/v1/work`: every item's current state, merged ones included however old, and the ledger's freshness
    /// (`lastEventAt`, null while the ledger is empty).
    record WorkJson(String lastEventAt, List<WorkCardJson> items) {}

    record WorkCardJson(String id,
                        String item,
                        String title,
                        String submittedAt,
                        String phase,
                        int round,
                        String coder,
                        String verifier,
                        String note,
                        String at,
                        String phaseSince) {}

    /// `POST /api/v1/ingest/work`'s answer: the appended event's id and what it recorded.
    record WorkRecordedJson(long event, String item, String phase, int round, String at) {}

    static WorkJson work(WorkBoard board) {
        return new WorkJson(text(board.lastEventAt()),
                            board.cards().stream().map(Views::workCard).toList());
    }

    private static WorkCardJson workCard(WorkCard card) {
        return new WorkCardJson(card.id(),
                                card.item(),
                                card.title().or((String) null),
                                card.submittedAt().toString(),
                                card.phase().label(),
                                card.round(),
                                card.coder().or((String) null),
                                card.verifier().or((String) null),
                                card.note().or((String) null),
                                card.at().toString(),
                                card.phaseSince().toString());
    }

    static WorkRecordedJson recorded(WorkEvent event, long id) {
        return new WorkRecordedJson(id,
                                    event.id(),
                                    event.phase().label(),
                                    event.round(),
                                    event.at().toString());
    }

    /// `GET /api/v1/conflicts` (temper #100): `enabled` false while the watch is off (every other field then null or
    /// empty); `limit` states that merge-tree finds textual conflicts only; `pairsSkipped` the pair checks the last step
    /// left to the next ones, over its cap. `failure` is the last step's, null when it
    /// succeeded. Each prediction names its kind (`tip`|`pair`), PRs, paths and the hot ones among them, and for a pair
    /// `firsts`: the PRs whose landing first makes the other conflict (one or both; PR #108 review F4).
    record ConflictsJson(boolean enabled,
                         String branch,
                         String tip,
                         String checkedAt,
                         int open,
                         int unknown,
                         int pairsSkipped,
                         List<PredictionJson> predictions,
                         List<PredictionJson> carried,
                         List<CostJson> costs,
                         String failure,
                         String limit) {}

    record PredictionJson(String kind,
                          List<Long> prs,
                          List<String> paths,
                          List<String> hot,
                          String tip,
                          List<Long> firsts) {}

    record CostJson(String at, Long fetchMillis, Long fetchedBytes, long checkMillis, int tipChecks, int pairChecks) {}

    static ConflictsJson conflicts(Option<ConflictBoard> board) {
        return board.map(Views::conflictsOf)
                    .or(new ConflictsJson(false,
                                          null,
                                          null,
                                          null,
                                          0,
                                          0,
                                          0,
                                          List.of(),
                                          List.of(),
                                          List.of(),
                                          null,
                                          ConflictBoard.LIMIT));
    }

    private static ConflictsJson conflictsOf(ConflictBoard board) {
        return new ConflictsJson(true,
                                 board.branch().value(),
                                 board.tip().map(Sha::value).or((String) null),
                                 text(board.checkedAt()),
                                 board.open(),
                                 board.unknown(),
                                 board.skipped(),
                                 board.predictions().stream().map(Views::prediction).toList(),
                                 board.carried().stream().map(Views::prediction).toList(),
                                 board.costs().stream().map(Views::cost).toList(),
                                 board.failure().or((String) null),
                                 ConflictBoard.LIMIT);
    }

    private static PredictionJson prediction(Prediction prediction) {
        return new PredictionJson(prediction.kind().word(),
                                  prediction.prs().stream().map(PrNumber::value).toList(),
                                  prediction.paths(),
                                  prediction.hotPaths(),
                                  prediction.tip().value(),
                                  prediction.firsts().stream().map(PrNumber::value).toList());
    }

    private static CostJson cost(ConflictBoard.Cost cost) {
        return new CostJson(cost.at().toString(),
                            cost.fetchMillis().or((Long) null),
                            cost.fetchedBytes().or((Long) null),
                            cost.checkMillis(),
                            cost.tipChecks(),
                            cost.pairChecks());
    }

    record unused() implements Views {}
}
