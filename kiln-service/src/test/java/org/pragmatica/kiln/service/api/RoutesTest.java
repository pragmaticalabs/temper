package org.pragmatica.kiln.service.api;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.Derived;
import org.pragmatica.kiln.core.checkout.LinkRule;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.service.github.Credential;
import org.pragmatica.kiln.service.github.GitHubBudget;
import org.pragmatica.kiln.service.github.GitHubWatch;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.github.PullsPage;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.http.ApiRequest;
import org.pragmatica.kiln.service.http.ApiResponse;
import org.pragmatica.kiln.service.http.Router;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.scan.MacIngest;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.ChangeStore;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.CheckoutStore;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.NonGitView;
import org.pragmatica.kiln.service.store.Page;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassOutcome;
import org.pragmatica.kiln.service.store.PassRecord;
import org.pragmatica.kiln.service.store.PassView;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.kiln.service.store.ViewStore;
import org.pragmatica.kiln.service.store.WorkBoard;
import org.pragmatica.kiln.service.store.WorkCard;
import org.pragmatica.kiln.service.store.WorkEvent;
import org.pragmatica.kiln.service.store.WorkPhase;
import org.pragmatica.kiln.service.store.WorkStore;
import org.pragmatica.kiln.service.web.Periods;
import org.pragmatica.kiln.service.web.RateBudget;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// Every route of [ApiRoutes] over in-memory store parts (WP9): what each answers, the [Views] JSON of every fact with
/// its `observedAt` (a fact kiln does not know is `null`, never 0), the summary's `asOf` taken from the store's
/// watermark and never the clock (open item 10), a source never recorded listed as `NONE` (open item 12), the dashboard's
/// inputs (open items 1, 2, 4), and a failing store answered 500, never an empty page.
class RoutesTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Instant SEEN = NOW.minusSeconds(30);

    private static final GitHubBudget BUDGET = new GitHubBudget(some(new GitHubBudget.Sample("kiln-app",
                                                                                             5000,
                                                                                             4321,
                                                                                             679,
                                                                                             NOW.plusSeconds(600),
                                                                                             SEEN,
                                                                                             "header")),
                                                                Credential.Mode.TOKEN,
                                                                new GitHubWatch.Drift(some("abc123"),
                                                                                      List.of(7L, 9L),
                                                                                      some(List.of("reviewer-pragmatica")),
                                                                                      some(SEEN),
                                                                                      none(),
                                                                                      0));

    private static final Instant WATERMARK = NOW.minusSeconds(3);
    private static final TimeSpan WAIT = TimeSpan.timeSpan(10).seconds();
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final HostName BIGBOY = HostName.hostName("bigboy").unwrap();
    private static final BranchName MAIN = BranchName.branchName("main").unwrap();
    private static final Sha HEAD = Sha.sha("a".repeat(40)).unwrap();
    private static final CheckoutPath PATH = CheckoutPath.checkoutPath("/home/builder/work/co/a").unwrap();

    private static final RequiredCheck CI = new RequiredCheck(CheckContext.checkContext("ci").unwrap(),
                                                              some(AppId.appId(15368).unwrap()));

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    // ---- fixtures ----------------------------------------------------------------------------------------------------
    private static PullRequest pullRequest() {
        return new PullRequest(PrNumber.prNumber(7).unwrap(),
                               "pr <7>",
                               PrState.OPEN,
                               true,
                               MAIN,
                               BranchName.branchName("feature").unwrap(),
                               some("o/r"),
                               HEAD,
                               none(),
                               none(),
                               SEEN,
                               List.of("kiln"),
                               List.of(PrNumber.prNumber(3).unwrap()),
                               List.of(PrNumber.prNumber(2).unwrap()));
    }

    private static PrView prView() {
        return new PrView(Observed.observed(pullRequest(), SEEN),
                          some(Observed.observed(new OpenDetail(some(true), some("clean"), some(SEEN)), SEEN)),
                          some(Observed.observed(new BehindFacts(none(), none()), SEEN)),
                          some(Observed.observed(new RequiredChecks.Required(List.of(CI), "non_admins"),
                                                 SEEN)),
                          List.of(Observed.observed(new Cell(CI, "success", 1, List.of("ci")), SEEN)),
                          some(Observed.observed(new GateRead.Answered("aaaaaaa", "PASS", some(12L), some("j")), SEEN)),
                          List.of(Observed.observed(new PrView.LinkedCheckout(BIGBOY, PATH, CheckoutStatus.ACTIVE), SEEN)));
    }

    /// An open PR with only a required list and a gate: no detail, no behind facts, no cells, no checkout.
    private static PrView sparse(RequiredChecks required, GateRead gate) {
        return new PrView(Observed.observed(pullRequest(), SEEN),
                          none(),
                          none(),
                          some(Observed.observed(required, SEEN)),
                          List.of(),
                          some(Observed.observed(gate, SEEN)),
                          List.of());
    }

    private static CheckoutFacts facts() {
        return new CheckoutFacts(PATH,
                                 some(HEAD),
                                 some(MAIN),
                                 some("git@x:o/r.git"),
                                 List.of(),
                                 2,
                                 1,
                                 some("rebase"),
                                 SEEN,
                                 List.of(42L),
                                 false,
                                 PATH.value() + "/.git",
                                 1,
                                 some(3));
    }

    private static CheckoutView checkout() {
        var derived = new DerivedRow(PATH,
                                     new Derived(CheckoutStatus.NEEDS_SALVAGE,
                                                 new Preservation.Unpreserved(List.of("dirty:2")),
                                                 new PrLink(List.of(PrNumber.prNumber(7).unwrap()),
                                                            LinkRule.BRANCH),
                                                 some(false)),
                                     some(SEEN),
                                     none(),
                                     some(SEEN),
                                     SEEN);

        return new CheckoutView(new CheckoutRow(BIGBOY,
                                                PATH,
                                                PATH.value() + "/.git",
                                                SEEN,
                                                some(facts()),
                                                some(PassKind.HOT),
                                                some(SEEN),
                                                SEEN,
                                                none(),
                                                none()),
                                some(derived));
    }

    private static CheckoutView unscanned() {
        return new CheckoutView(new CheckoutRow(HostName.MAC,
                                                CheckoutPath.checkoutPath("/Users/s/x").unwrap(),
                                                "/Users/s/x/.git",
                                                SEEN,
                                                none(),
                                                none(),
                                                none(),
                                                SEEN,
                                                some(5L),
                                                some(SEEN)),
                                none());
    }

    private Object answer(String name, Object value) {
        calls.add(name);

        return failing
               ? Causes.cause("store down").promise()
               : Promise.success(value);
    }

    private final ViewStore views = (ViewStore) Proxy.newProxyInstance(ViewStore.class.getClassLoader(),
                                                                       new Class<?>[]{ViewStore.class},
                                                                       (_, method, args) -> switch (method.getName()) {
        case "openPrs" -> answer("openPrs",
                                 List.of(prView(),
                                         sparse(new RequiredChecks.NoneRequired("off", true),
                                                new GateRead.SchemaBroken("answer")),
                                         sparse(new RequiredChecks.Unknown("403"), new GateRead.SchemaBroken("sha"))));
        case "counts" -> answer("counts",
                                List.of(new HostCounts(BIGBOY,
                                                       Map.of(CheckoutStatus.NEEDS_SALVAGE,
                                                              4,
                                                              CheckoutStatus.STALE_UNOWNED,
                                                              1),
                                                       900,
                                                       2,
                                                       some(SEEN)),
                                        new HostCounts(HostName.MAC, Map.of(CheckoutStatus.UNSCANNED, 1), 0, 0, none())));
        case "checkouts" -> answer("checkouts " + args[0] + " " + args[1] + " " + args[2],
                                   new Page<>(List.of(checkout()), (int) args[2], 3));
        case "bases" -> answer("bases",
                               new BaseView(List.of(), List.of()));
        case "nonGit" -> answer("nonGit " + args[0],
                                List.of(new NonGitView("~/work/*",
                                                       CheckoutPath.checkoutPath("/home/builder/work/junk").unwrap(),
                                                       SEEN)));
        case "digest" -> answer("digest", some(Observed.observed("digest text", SEEN)));
        case "passes" -> answer("passes",
                                List.of(new PassView(BIGBOY,
                                                     PassKind.HOT,
                                                     PassOutcome.COMPLETE,
                                                     3,
                                                     some(3),
                                                     SEEN,
                                                     some(SEEN),
                                                     none(),
                                                     none())));
        case "listed" -> answer("listed " + args[0] + " " + args[1],
                                ((Option<?>) args[1]).isPresent()
                                ? List.of(unscanned())
                                : List.of(checkout()));
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final SourceStore sources = (SourceStore) Proxy.newProxyInstance(SourceStore.class.getClassLoader(),
                                                                             new Class<?>[]{SourceStore.class},
                                                                             (_, method, args) -> switch (method.getName()) {
        case "all" -> answer("sources",
                             List.of(new SourceStatus(Source.GITHUB, new SourceState.Ok(SEEN), some(SEEN)),
                                     new SourceStatus(Source.host(BIGBOY),
                                                      new SourceState.Unreachable("ssh exit 255", SEEN),
                                                      some(SEEN))));
        case "record" -> answer("record " + args[0] + " " + args[1], org.pragmatica.lang.Unit.unit());
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final ChangeStore changes = (ChangeStore) Proxy.newProxyInstance(ChangeStore.class.getClassLoader(),
                                                                             new Class<?>[]{ChangeStore.class},
                                                                             (_, method, args) -> switch (method.getName()) {
        case "watermark" -> WATERMARK;
        case "since" -> answer("since " + args[0] + " " + args[1],
                               List.of(new Change(SEEN,
                                                  PrNumber.prNumber(7).unwrap(),
                                                  ChangeKind.PR_STATE,
                                                  "#7",
                                                  "open",
                                                  "merged",
                                                  WATERMARK.minusSeconds(1)),
                                       new Change(SEEN,
                                                  PrNumber.prNumber(8).unwrap(),
                                                  ChangeKind.PR_STATE,
                                                  "#8",
                                                  "open",
                                                  "closed",
                                                  WATERMARK.plusSeconds(1))));
        case "oldest" -> answer("oldest", some(NOW.minusSeconds(86_400)));
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final CheckoutStore checkouts = (CheckoutStore) Proxy.newProxyInstance(CheckoutStore.class.getClassLoader(),
                                                                                   new Class<?>[]{CheckoutStore.class},
                                                                                   (_, method, args) -> switch (method.getName()) {
        case "applyPass" -> answer("applyPass " + args[0] + " " + args[1] + " " + args[2],
                                   new PassRecord(PassOutcome.COMPLETE, 1));
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final List<WorkEvent> recorded = new CopyOnWriteArrayList<>();

    private static WorkCard card(String id, WorkPhase phase, Instant since) {
        return new WorkCard(id,
                            "T1",
                            some("title of " + id),
                            NOW.minusSeconds(11_520),
                            phase,
                            2,
                            some("t1wp5"),
                            some("t1wp5v"),
                            some("note <b>"),
                            since,
                            since);
    }

    private final WorkStore work = (WorkStore) Proxy.newProxyInstance(WorkStore.class.getClassLoader(),
                                                                      new Class<?>[]{WorkStore.class},
                                                                      (_, method, args) -> switch (method.getName()) {
        case "record" -> recorded.add((WorkEvent) args[0])
                         ? answer("record work", 41L)
                         : null;
        case "board" -> answer("board",
                               new WorkBoard(List.of(card("T1-WP5", WorkPhase.VERIFY, SEEN),
                                                     card("T1-WP1",
                                                          WorkPhase.MERGED,
                                                          NOW.minus(Duration.ofDays(8))))));
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final KilnStore store = (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(),
                                                                       new Class<?>[]{KilnStore.class},
                                                                       (_, method, _) -> switch (method.getName()) {
        case "work" -> work;
        case "views" -> views;
        case "sources" -> sources;
        case "changes" -> changes;
        case "checkouts" -> checkouts;
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final ApiRoutes routes = ApiRoutes.apiRoutes(store,
                                                         MacIngest.macIngest(checkouts,
                                                                             sources,
                                                                             List.of("/m/*"),
                                                                             () -> NOW,
                                                                             () -> {}),
                                                         List.of(MAIN),
                                                         List.of(BIGBOY),
                                                         () -> some(Observed.observed(new RateBudget(4321,
                                                                                                     NOW.plusSeconds(600),
                                                                                                     123,
                                                                                                     1500,
                                                                                                     1400),
                                                                                      SEEN)),
                                                         () -> List.of(Observed.observed(PullsPage.Skipped.skipped(PrNumber.prNumber(9).unwrap(),
                                                                                                                   "base ref -x",
                                                                                                                   none()),
                                                                                         SEEN)),
                                                         () -> Periods.periods(Duration.ofSeconds(60),
                                                                               Duration.ofSeconds(60),
                                                                               Duration.ofSeconds(60),
                                                                               Duration.ofSeconds(120)),
                                                         Periods.periods(Duration.ofSeconds(60),
                                                                         Duration.ofSeconds(60),
                                                                         Duration.ofSeconds(60),
                                                                         Duration.ofSeconds(120)),
                                                         () -> NOW,
                                                         () -> BUDGET);

    private final Router router = Router.router(routes.routes());

    private ApiResponse get(String path, Map<String, String> query) {
        return router.handle(ApiRequest.apiRequest("GET", path, query, ""))
                     .await(WAIT)
                     .unwrap();
    }

    private static Map<String, Object> json(ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.contentType()).isEqualTo(ApiResponse.JSON);

        return MAPPER.readString(response.body(),
                                 new TypeToken<Map<String, Object>>() {})
                     .unwrap();
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(Object json, Object... path) {
        var current = json;

        for (var step : path) {
            current = step instanceof Integer index
                      ? ((List<Object>) current).get(index)
                      : ((Map<String, Object>) current).get(step);
        }

        return (T) current;
    }

    // ---- JSON --------------------------------------------------------------------------------------------------------
    @Test
    void prs_carryEveryFact_withItsObservation_andNullForUnknown() {
        var answer = json(get("/api/v1/prs", Map.of()));
        Map<String, Object> pr = at(answer, "prs", 0);

        assertThat(pr).containsEntry("number", 7)
                  .containsEntry("title", "pr <7>")
                  .containsEntry("state", "OPEN")
                  .containsEntry("draft", true)
                  .containsEntry("base", "main")
                  .containsEntry("headRef", "feature")
                  .containsEntry("headRepo", "o/r")
                  .containsEntry("head",
                                 HEAD.value())
                  .containsEntry("updatedAt",
                                 SEEN.toString())
                  .containsEntry("labels",
                                 List.of("kiln"))
                  .containsEntry("closes",
                                 List.of(3))
                  .containsEntry("partOf",
                                 List.of(2))
                  .containsEntry("observedAt",
                                 SEEN.toString());
        assertThat((Object) at(pr, "detail")).isEqualTo(Map.of("mergeable",
                                                               true,
                                                               "mergeableState",
                                                               "clean",
                                                               "headCommittedAt",
                                                               SEEN.toString(),
                                                               "observedAt",
                                                               SEEN.toString()));
        assertThat((Object) at(pr, "behind", "behindBy")).isNull();
        assertThat((Object) at(pr, "behind", "behindTreeEmpty")).isNull();
        assertThat((Object) at(pr, "behind", "observedAt")).isEqualTo(SEEN.toString());
        assertThat((Object) at(pr, "required", "kind")).isEqualTo("REQUIRED");
        assertThat((Object) at(pr, "required", "enforcement")).isEqualTo("non_admins");
        assertThat((Object) at(pr, "required", "checks")).isEqualTo(List.of(Map.of("context", "ci", "appId", 15368)));
        assertThat((Object) at(pr, "cells", 0)).isEqualTo(Map.of("check",
                                                                 "ci@15368",
                                                                 "value",
                                                                 "success",
                                                                 "success",
                                                                 true,
                                                                 "foreign",
                                                                 1,
                                                                 "shown",
                                                                 List.of("ci"),
                                                                 "observedAt",
                                                                 SEEN.toString()));
        assertThat((Object) at(pr, "gate", "kind")).isEqualTo("ANSWERED");
        assertThat((Object) at(pr, "gate", "answer")).isEqualTo("PASS");
        assertThat((Object) at(pr, "gate", "job")).isEqualTo(12);
        assertThat((Object) at(pr, "gate", "judge")).isEqualTo("j");
        assertThat((Object) at(pr, "gate", "sha")).isEqualTo("aaaaaaa");
        assertThat((Object) at(pr, "checkouts", 0)).isEqualTo(Map.of("host",
                                                                     "bigboy",
                                                                     "path",
                                                                     PATH.value(),
                                                                     "status",
                                                                     "ACTIVE",
                                                                     "observedAt",
                                                                     SEEN.toString()));
        Map<String, Object> none = at(answer, "prs", 1);
        Map<String, Object> unknown = at(answer, "prs", 2);

        assertThat(none).containsEntry("detail", null)
                  .containsEntry("behind", null)
                  .containsEntry("cells",
                                 List.of())
                  .containsEntry("checkouts",
                                 List.of());
        assertThat((Map<String, Object>) at(none, "required")).containsEntry("kind", "NONE_REQUIRED")
                  .containsEntry("why", "off")
                  .containsEntry("stacked", true)
                  .containsEntry("enforcement", null)
                  .containsEntry("status", null);
        assertThat((Map<String, Object>) at(none, "gate")).containsEntry("kind", "SCHEMA_BROKEN")
                  .containsEntry("field", "answer")
                  .containsEntry("answer", null);
        assertThat((Map<String, Object>) at(unknown, "required")).containsEntry("kind", "UNKNOWN")
                  .containsEntry("status", "403")
                  .containsEntry("why", null)
                  .containsEntry("stacked", null);
    }

    /// Open item 12: every expected source is listed; one never recorded reads NONE and stale, never absent.
    @Test
    void everyAnswer_listsEverySource_aMissingOneAsNone() {
        List<Map<String, Object>> listed = at(json(get("/api/v1/prs", Map.of())), "sources");

        assertThat(listed).extracting(source -> source.get("source"))
                  .containsExactly("github", "mirror", "temper", "hosts", "host:bigboy", "mac");
        assertThat(listed.get(0)).containsEntry("state", "OK")
                  .containsEntry("at",
                                 SEEN.toString())
                  .containsEntry("lastSuccess",
                                 SEEN.toString())
                  .containsEntry("stale", false);
        assertThat(listed.get(1)).containsEntry("state", "NONE")
                  .containsEntry("lastSuccess", null)
                  .containsEntry("stale", true);
        assertThat(listed.get(3)).containsEntry("state", "NONE")
                  .containsEntry("lastSuccess", null)
                  .containsEntry("stale", true);
        assertThat(listed.get(4)).containsEntry("state", "UNREACHABLE")
                  .containsEntry("detail", "ssh exit 255")
                  .containsEntry("at",
                                 SEEN.toString());
    }

    @Test
    void checkouts_areAPage_withEveryHostsCounts() {
        var answer = json(get("/api/v1/checkouts", Map.of("status", "NEEDS_SALVAGE", "host", "bigboy", "page", "2")));
        Map<String, Object> row = at(answer, "rows", 0);

        assertThat(calls).contains("checkouts Some(NEEDS_SALVAGE) Some(bigboy) 2");
        assertThat(answer).containsEntry("page", 2).containsEntry("pages", 3);
        assertThat((Object) at(answer, "counts", 0)).isEqualTo(Map.of("host",
                                                                      "bigboy",
                                                                      "counts",
                                                                      Map.of("NEEDS_SALVAGE", 4, "STALE_UNOWNED", 1),
                                                                      "knownSizeKb",
                                                                      900,
                                                                      "measuredRows",
                                                                      2,
                                                                      "oldestFactsAt",
                                                                      SEEN.toString()));
        assertThat((Object) at(answer, "counts", 1, "knownSizeKb")).isNull();
        assertThat(row).containsEntry("host", "bigboy")
                  .containsEntry("path",
                                 PATH.value())
                  .containsEntry("status", "NEEDS_SALVAGE")
                  .containsEntry("activityAt",
                                 SEEN.toString())
                  .containsEntry("listedAt",
                                 SEEN.toString())
                  .containsEntry("factsPass", "HOT")
                  .containsEntry("factsAt",
                                 SEEN.toString())
                  .containsEntry("head",
                                 HEAD.value())
                  .containsEntry("branch", "main")
                  .containsEntry("modified", 2)
                  .containsEntry("untracked", 1)
                  .containsEntry("op", "rebase")
                  .containsEntry("stash", 1)
                  .containsEntry("salvageRefs", 3)
                  .containsEntry("processes",
                                 List.of(42))
                  .containsEntry("preservation", "UNPRESERVED")
                  .containsEntry("reasons",
                                 List.of("dirty:2"))
                  .containsEntry("linkedPrs",
                                 List.of(7))
                  .containsEntry("linkRule", "BRANCH")
                  .containsEntry("atBase", false)
                  .containsEntry("derivedAt",
                                 SEEN.toString())
                  .containsEntry("sizeKb", null)
                  .containsEntry("sizeAt", null);
        assertThat(get("/api/v1/checkouts", Map.of()).status()).isEqualTo(200);
        assertThat(calls).contains("checkouts None() None() 1");
        assertThat(get("/api/v1/checkouts", Map.of("status", "IN-USE")).status()).isEqualTo(400);
        assertThat(get("/api/v1/checkouts", Map.of("host", "-x")).status()).isEqualTo(400);
    }

    /// Open items 1 and 2: the sources view carries GitHub's budget and skipped PRs, and every host's passes.
    @Test
    void sources_carryTheBudget_theSkippedPrs_andThePasses() {
        var answer = json(get("/api/v1/sources", Map.of()));

        assertThat((Object) at(answer, "rate")).isEqualTo(Map.of("remaining",
                                                                 4321,
                                                                 "reset",
                                                                 NOW.plusSeconds(600).toString(),
                                                                 "observedAt",
                                                                 SEEN.toString(),
                                                                 "kilnSpent",
                                                                 123,
                                                                 "hourlyCap",
                                                                 1500,
                                                                 "reserve",
                                                                 1400));
        assertThat((Object) at(answer, "skipped")).isEqualTo(List.of(Map.of("number",
                                                                            9,
                                                                            "reason",
                                                                            "base ref -x",
                                                                            "observedAt",
                                                                            SEEN.toString())));
        Map<String, Object> pass = at(answer, "passes", 0);

        assertThat(pass).containsEntry("host", "bigboy")
                  .containsEntry("kind", "HOT")
                  .containsEntry("outcome", "COMPLETE")
                  .containsEntry("rows", 3)
                  .containsEntry("expected", 3)
                  .containsEntry("startedAt",
                                 SEEN.toString())
                  .containsEntry("finishedAt",
                                 SEEN.toString())
                  .containsEntry("progressK", null)
                  .containsEntry("progressN", null);
    }

    /// The route publishes kiln's sample, the credential mode and the drift fields, and nothing that
    /// authenticates (no field but those three, no secret in the body).
    @Test
    void githubBudget_publishesTheSample_theMode_andTheDrift_butNeverATokenField() {
        var response = get("/api/v1/github-budget", Map.of());
        var answer = json(response);

        assertThat(answer.keySet()).containsExactlyInAnyOrder("samples", "mode", "drift");
        assertThat(answer).containsEntry("mode", "TOKEN");
        assertThat((Object) at(answer, "samples")).isEqualTo(List.of(Map.of("identity",
                                                                            "kiln-app",
                                                                            "limit",
                                                                            5000,
                                                                            "remaining",
                                                                            4321,
                                                                            "used",
                                                                            679,
                                                                            "reset",
                                                                            NOW.plusSeconds(600).toString(),
                                                                            "observedAt",
                                                                            SEEN.toString(),
                                                                            "source",
                                                                            "header")));
        assertThat((Map<String, Object>) at(answer, "drift")).containsAllEntriesOf(Map.of("rulesetHash",
                                                                                          "abc123",
                                                                                          "rulesetIds",
                                                                                          List.of(7, 9),
                                                                                          "teamMembers",
                                                                                          List.of("reviewer-pragmatica"),
                                                                                          "observedAt",
                                                                                          SEEN.toString()));
        assertThat((Map<String, Object>) at(answer, "drift")).as("fresh, no failure (a null the answer still names)")
                  .containsEntry("fresh", true)
                  .containsEntry("failedCycles", 0)
                  .containsEntry("failure", null);
        assertThat(response.body()).doesNotContain("t0ken-secret")
                  .doesNotContainIgnoringCase("authorization")
                  .doesNotContainIgnoringCase("bearer");
    }

    // ---- summary -----------------------------------------------------------------------------------------------------
    /// Open item 10: `asOf` is the watermark (a change recorded after it waits for the next call), never the clock; the
    /// changes are read after `since` with the page's cap; without `since`, the hour before the watermark.
    @Test
    void summary_isAsOfTheWatermark_neverTheClock() {
        var text = get("/api/v1/summary",
                       Map.of("format",
                              "text",
                              "since",
                              NOW.minusSeconds(600).toString()));

        assertThat(text.contentType()).isEqualTo(ApiResponse.TEXT);
        assertThat(text.body().lines().toList()).containsExactly("checkouts: NEEDS-SALVAGE 4, STALE-UNOWNED 1",
                                                                 "source host:bigboy: UNREACHABLE(ssh exit 255) since " + SEEN
                                                                + ", last success " + SEEN,
                                                                 "source mirror: no state recorded, last success never",
                                                                 "source temper: no state recorded, last success never",
                                                                 "source hosts: no state recorded, last success never",
                                                                 "source mac: no state recorded, last success never",
                                                                 SEEN + " #7 state: open -> merged",
                                                                 "next: since=" + WATERMARK);
        assertThat(calls).contains("since " + NOW.minusSeconds(600) + " 25");
        var json = json(get("/api/v1/summary", Map.of()));

        assertThat(json).containsEntry("asOf", WATERMARK.toString());
        assertThat(calls).contains("since " + NOW.minus(ApiRoutes.DEFAULT_WINDOW) + " 25");
        // a since at or after asOf lists nothing and still states asOf
        var ahead = get("/api/v1/summary",
                        Map.of("format",
                               "text",
                               "since",
                               WATERMARK.toString())).body()
                       .lines()
                       .toList();

        assertThat(ahead).last().isEqualTo("next: since=" + WATERMARK);
        assertThat(ahead).noneMatch(line -> line.contains("state:"));
        assertThat(get("/api/v1/summary", Map.of("since", "2026-10-28T00:00:00Z", "format", "text")).body()).startsWith("gap: since=2026-10-28T00:00:00Z");
        assertThat(get("/api/v1/summary", Map.of("format", "json")).contentType()).isEqualTo(ApiResponse.JSON);
    }

    // ---- pages -------------------------------------------------------------------------------------------------------
    /// Open items 1, 2, 4: the dashboard over the configured base and host, the budget and skipped PR, the passes, the
    /// listed rows of every host and every Mac row.
    @Test
    void dashboard_gathersEveryInput() {
        var page = get("/", Map.of());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.contentType()).isEqualTo(ApiResponse.HTML);
        assertThat(page.body()).contains("rate budget 4321 left",
                                         "skipped #9: <bdi>base ref -x</bdi>",
                                         "hot pass: COMPLETE, 3 of 3 rows",
                                         "digest text",
                                         PATH.value(),
                                         "/Users/s/x",
                                         "1 non-git");
        assertThat(calls).contains("listed [ACTIVE, IN_USE, NEEDS_SALVAGE, UNSCANNED] None()",
                                   "nonGit bigboy")
                  .anyMatch(call -> call.startsWith("listed [")
                                    && call.endsWith("Some(mac)")
                                    && call.contains("STALE_UNOWNED"));
    }

    @Test
    void drillDown_andNonGit_readTheirParameters() {
        var drill = get("/checkouts", Map.of("status", "IN_USE", "host", "mac"));

        assertThat(drill.status()).isEqualTo(200);
        assertThat(drill.contentType()).isEqualTo(ApiResponse.HTML);
        assertThat(drill.body()).contains("IN-USE checkouts on mac", PATH.value());
        assertThat(calls).contains("checkouts Some(IN_USE) Some(mac) 1");
        assertThat(get("/checkouts", Map.of("status", "IN-USE")).status()).isEqualTo(400);
        assertThat(get("/checkouts", Map.of()).status()).isEqualTo(400);
        assertThat(get("/checkouts", Map.of("status", "ACTIVE", "page", "0")).status()).isEqualTo(400);
        var nonGit = get("/non-git", Map.of("host", "bigboy", "root", "~/work/*"));

        assertThat(nonGit.status()).isEqualTo(200);
        assertThat(nonGit.body()).contains("/home/builder/work/junk");
        assertThat(get("/non-git", Map.of("host", "bigboy")).status()).isEqualTo(400);
        assertThat(get("/non-git", Map.of("root", "~/work/*")).status()).isEqualTo(400);
        assertThat(get("/non-git", Map.of("host", "Bad Host", "root", "x")).status()).isEqualTo(400);
    }

    @Test
    void ingest_answersPerSection4_withTheConfiguredRoots() {
        var push = "{\"end\":true,\"count\":0,\"nonGit\":0,\"nonGitBytes\":null}\n";
        var browser = post(Map.of("X-Kiln-Ingest", "1", "Origin", "https://evil.example"), push);
        var bare = post(Map.of(), push);
        var other = post(Map.of("X-Kiln-Ingest", "yes"), push);
        // WP9 verification (CSRF): only kiln-ingest's own request is applied
        assertThat(List.of(browser.status(), bare.status(), other.status())).containsOnly(403);
        assertThat(browser.body()).doesNotContain("evil");
        assertThat(calls).noneMatch(call -> call.startsWith("applyPass"));
        var answer = post(Map.of("x-kiln-ingest", "1"), push);

        assertThat(answer).isEqualTo(ApiResponse.json("{\"outcome\":\"COMPLETE\",\"rows\":1}"));
        assertThat(calls).anyMatch(call -> call.startsWith("applyPass mac PUSH [/m/*]"));
    }

    private ApiResponse post(Map<String, String> headers, String body) {
        return post("/api/v1/ingest/mac", headers, body);
    }

    private ApiResponse post(String path, Map<String, String> headers, String body) {
        var lower = new java.util.HashMap<String, String>();

        headers.forEach((name, value) -> lower.put(name.toLowerCase(java.util.Locale.ROOT), value));

        return router.handle(ApiRequest.apiRequest("POST",
                                                   path,
                                                   Map.of(),
                                                   lower,
                                                   body))
                     .await(WAIT)
                     .unwrap();
    }

    // ---- the work ledger -----------------------------------------------------------------------------------------------
    private static final String EVENT = "{\"item\":\"T1-WP5\",\"title\":\"Work tab\",\"phase\":\"verify\",\"round\":2,"
                                      + "\"coder\":\"wtab\",\"verifier\":\"t1wp5v\",\"note\":\"r2 <ready>\"}";

    /// The Mac ingest's header rule guards the ledger too: a browser's request, one without the header and one with
    /// another value are 403 and record nothing.
    @Test
    void ingestWork_isRefused403_withoutKilnIngestsHeader_orWithAnOrigin() {
        var browser = post("/api/v1/ingest/work", Map.of("X-Kiln-Ingest", "1", "Origin", "https://evil.example"), EVENT);
        var bare = post("/api/v1/ingest/work", Map.of(), EVENT);
        var other = post("/api/v1/ingest/work", Map.of("X-Kiln-Ingest", "yes"), EVENT);

        assertThat(List.of(browser.status(), bare.status(), other.status())).containsOnly(403);
        assertThat(recorded).isEmpty();
    }

    @Test
    void ingestWork_recordsTheEvent_andAnswersItsId() {
        var answer = post("/api/v1/ingest/work", Map.of("X-Kiln-Ingest", "1"), EVENT);

        assertThat(json(answer)).isEqualTo(Map.of("event",
                                                  41,
                                                  "item",
                                                  "T1-WP5",
                                                  "phase",
                                                  "verify",
                                                  "round",
                                                  2,
                                                  "at",
                                                  NOW.toString()));
        assertThat(recorded).containsExactly(new WorkEvent("T1-WP5",
                                                           "T1",
                                                           some("Work tab"),
                                                           WorkPhase.VERIFY,
                                                           2,
                                                           some("wtab"),
                                                           some("t1wp5v"),
                                                           some("r2 <ready>"),
                                                           NOW));
    }

    /// Every invalid body is a 400 naming the field, and records nothing.
    @Test
    void ingestWork_refusesAnInvalidEvent_with400() {
        for (var body : List.of("{\"item\":\"T1-WP5\",\"phase\":\"reviewing\",\"round\":1}",
                                "{\"item\":\"T1-WP5\",\"phase\":\"verify\",\"round\":-1}",
                                "{\"item\":\"T1 WP5\",\"phase\":\"verify\",\"round\":1}",
                                "{\"item\":\"T1-WP5\",\"phase\":\"verify\",\"round\":1,\"verifer\":\"x\"}",
                                "not json")) {
            var answer = post("/api/v1/ingest/work", Map.of("X-Kiln-Ingest", "1"), body);

            assertThat(answer.status()).as(body).isEqualTo(400);
        }

        assertThat(post("/api/v1/ingest/work",
                        Map.of("X-Kiln-Ingest", "1"),
                        "{\"item\":\"T1-WP5\",\"phase\":\"reviewing\",\"round\":-1}").body()).contains("phase", "round");
        assertThat(recorded).isEmpty();
    }

    /// Without a pipeline read wired, the route answers the baseline JSON with nothing read: never a 404 or an empty 200.
    @Test
    void pipelineBaseline_isTheBaselineJson() {
        var answer = json(get("/api/v1/pipeline/baseline", Map.of()));

        assertThat((Object) at(answer, "state", "kind")).isEqualTo("NEVER");
        assertThat((Object) at(answer, "scope", "base")).isEqualTo("release-1.0.0-rc5");
        assertThat(answer).containsEntry("measured", 0)
                  .containsEntry("total", 0)
                  .containsEntry("weeks",
                                 List.of())
                  .containsEntry("prs",
                                 List.of());
    }

    @Test
    void work_isTheBoard_asJson() {
        var answer = json(get("/api/v1/work", Map.of()));
        Map<String, Object> item = at(answer, "items", 0);

        assertThat(answer).containsEntry("lastEventAt", SEEN.toString());
        assertThat(item).containsEntry("id", "T1-WP5")
                  .containsEntry("item", "T1")
                  .containsEntry("title", "title of T1-WP5")
                  .containsEntry("submittedAt",
                                 NOW.minusSeconds(11_520).toString())
                  .containsEntry("phase", "verify")
                  .containsEntry("round", 2)
                  .containsEntry("coder", "t1wp5")
                  .containsEntry("verifier", "t1wp5v")
                  .containsEntry("note", "note <b>")
                  .containsEntry("at",
                                 SEEN.toString())
                  .containsEntry("phaseSince",
                                 SEEN.toString());
        // the JSON lists every item, an old merged one included
        assertThat((Object) at(answer, "items", 1, "phase")).isEqualTo("merged");
    }

    /// The Work tab hides a package merged more than 7 days ago, unless asked for `merged=all`.
    @Test
    void dashboard_workTab_hidesOldMerged_unlessAskedForAll() {
        var page = get("/", Map.of("tab", "work")).body();
        var all = get("/", Map.of("tab", "work", "merged", "all")).body();

        assertThat(page).contains("<main class=\"tabbed\" data-tab=\"work\">",
                                  "<b><bdi>T1-WP5</bdi></b>",
                                  "1 merged more than 7 days ago hidden")
                  .doesNotContain("<b><bdi>T1-WP1</bdi></b>");
        assertThat(all).contains("<b><bdi>T1-WP1</bdi></b>").doesNotContain("hidden ·");
    }

    /// A store that fails answers 500 on every route, never an empty page or list.
    @Test
    void aFailingStore_answers500_everywhere() {
        failing = true;
        var answers = new ArrayList<Integer>();

        for (var path : List.of("/",
                                "/api/v1/prs",
                                "/api/v1/checkouts",
                                "/api/v1/sources",
                                "/api/v1/summary",
                                "/api/v1/work")) {
            answers.add(get(path, Map.of()).status());
        }

        answers.add(get("/checkouts", Map.of("status", "ACTIVE")).status());
        answers.add(get("/non-git", Map.of("host", "bigboy", "root", "r")).status());
        assertThat(answers).containsOnly(500);
    }
}
