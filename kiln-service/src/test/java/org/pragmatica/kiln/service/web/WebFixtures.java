package org.pragmatica.kiln.service.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.Derived;
import org.pragmatica.kiln.core.checkout.LinkRule;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.github.Cell;
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
import org.pragmatica.kiln.core.scan.BranchTip;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.NonGitView;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassOutcome;
import org.pragmatica.kiln.service.store.PassView;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.WorkBoard;
import org.pragmatica.lang.Option;

import static org.pragmatica.kiln.core.model.Observed.observed;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The dashboard's test inputs: a `fresh` scenario where every source answered within its bound, and a `degraded` one
/// where every source failed or aged (rate limited, mirror stale, temper down, one host unreachable, one incomplete, the
/// Mac never pushed). Both render the same facts, so the goldens differ only by the freshness rules.
final class WebFixtures {
    static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    static final Periods PERIODS = new Periods(Duration.ofSeconds(60),
                                               Duration.ofSeconds(60),
                                               Duration.ofSeconds(60),
                                               Duration.ofMinutes(2),
                                               Duration.ofHours(2));
    static final HostName BIGBOY = host("bigboy");
    static final HostName CLOUD = host("cloudbb-3");
    static final BranchName RC5 = branch("release-1.0.0-rc5");
    static final BranchName MAIN = branch("main");
    static final RequiredCheck CI = check("ci", none());
    static final RequiredCheck CI_PINNED = check("ci", some(15368L));
    static final RequiredCheck LINT = check("lint", none());

    private WebFixtures() {}

    static Instant ago(long seconds) {
        return NOW.minusSeconds(seconds);
    }

    static HostName host(String name) {
        return HostName.hostName(name).unwrap();
    }

    static BranchName branch(String name) {
        return BranchName.branchName(name).unwrap();
    }

    static CheckoutPath path(String path) {
        return CheckoutPath.checkoutPath(path).unwrap();
    }

    static Sha sha(char digit) {
        return Sha.sha(String.valueOf(digit).repeat(40)).unwrap();
    }

    static PrNumber number(long number) {
        return PrNumber.prNumber(number).unwrap();
    }

    static RequiredCheck check(String context, Option<Long> app) {
        return new RequiredCheck(CheckContext.checkContext(context).unwrap(),
                                 app.map(id -> AppId.appId(id).unwrap()));
    }

    // ---- sources ---------------------------------------------------------------------------------------------------
    static Sources freshSources() {
        return new Sources(List.of(ok(Source.GITHUB, 30),
                                   ok(Source.MIRROR, 40),
                                   ok(Source.TEMPER, 50),
                                   ok(Source.HOSTS, 55),
                                   ok(Source.host(BIGBOY), 100),
                                   ok(Source.host(CLOUD), 110),
                                   ok(Source.MAC, 3_600)),
                           PERIODS);
    }

    /// GitHub rate limited for 10 min, the mirror 5 min old, temper down, bigboy unreachable, cloudbb-3 incomplete but
    /// recent, and no Mac state at all.
    static Sources degradedSources() {
        return new Sources(List.of(new SourceStatus(Source.GITHUB,
                                                    new SourceState.RateLimited(NOW.plusSeconds(2_400), ago(600)),
                                                    some(ago(600))),
                                   ok(Source.MIRROR, 300),
                                   new SourceStatus(Source.TEMPER,
                                                    new SourceState.TemperDown("connect refused", ago(1_200)),
                                                    some(ago(1_200))),
                                   new SourceStatus(Source.host(BIGBOY),
                                                    new SourceState.Unreachable("ssh exit 255", ago(1_800)),
                                                    some(ago(1_800))),
                                   new SourceStatus(Source.host(CLOUD),
                                                    new SourceState.Incomplete("count 3, rows 2", ago(60)),
                                                    some(ago(60)))),
                           PERIODS);
    }

    static SourceStatus ok(Source source, long secondsAgo) {
        return new SourceStatus(source, new SourceState.Ok(ago(secondsAgo)), some(ago(secondsAgo)));
    }

    // ---- views -----------------------------------------------------------------------------------------------------
    static DashboardView fresh() {
        return view(freshSources(), passes(), 0);
    }

    /// The same facts observed 10 min earlier, under failed sources.
    static DashboardView degraded() {
        return view(degradedSources(), degradedPasses(), 600);
    }

    static DashboardView view(Sources sources, List<PassView> passes, long aged) {
        return new DashboardView(sources,
                                 List.of(RC5, MAIN),
                                 List.of(BIGBOY, CLOUD),
                                 some(observed(new RateBudget(4_990, NOW.plusSeconds(2_400), 0, 1500, 1500), ago(30 + aged))),
                                 List.of(observed(PullsPage.Skipped.skipped(number(77), "base: invalid branch", none()),
                                                  ago(30 + aged))),
                                 passes,
                                 prs(aged),
                                 runs(aged),
                                 counts(),
                                 listed(aged),
                                 mac(),
                                 Map.of(BIGBOY,
                                        List.of(new NonGitView("/home/builder/work/co", path("/home/builder/work/co/notes"), ago(100)),
                                                new NonGitView("/home/builder/work/co", path("/home/builder/work/co/tmp"), ago(100)),
                                                new NonGitView("/tmp/scratch", path("/tmp/scratch/x"), ago(100)))),
                                 some(observed("temper digest\n  jobs: 3 <running> & 1 queued\n", ago(20 + aged))), WorkBoard.EMPTY);
    }

    static List<PassView> passes() {
        return List.of(new PassView(BIGBOY, PassKind.HOT, PassOutcome.COMPLETE, 181, some(181), ago(180), some(ago(100)), none(), none()),
                       new PassView(BIGBOY, PassKind.FULL, PassOutcome.RUNNING, 300, none(), ago(3_600), none(), some(3), some(10)),
                       new PassView(HostName.MAC, PassKind.PUSH, PassOutcome.COMPLETE, 3, some(3), ago(3_700), some(ago(3_600)), none(), none()));
    }

    /// bigboy's hot pass failed (no row count), cloudbb-3's answered without its trailer, the Mac never pushed.
    static List<PassView> degradedPasses() {
        return List.of(new PassView(BIGBOY, PassKind.HOT, PassOutcome.UNREACHABLE, 0, none(), ago(70), some(ago(60)), none(), none()),
                       new PassView(BIGBOY, PassKind.FULL, PassOutcome.COMPLETE, 990, some(990), ago(30_000), some(ago(28_800)), none(), none()),
                       new PassView(CLOUD, PassKind.HOT, PassOutcome.INCOMPLETE, 2, some(3), ago(70), some(ago(60)), none(), none()));
    }

    // ---- PRs -------------------------------------------------------------------------------------------------------
    /// #101 REQUIRED with a success, a `success*` under a pinned label and a failure; #102 draft, stacked,
    /// NONE-REQUIRED; #103 UNKNOWN(403) holding a `success` cell; #104 a base never read. Given out of order.
    static List<PrView> prs(long aged) {
        return List.of(pr103(aged), pr101(aged), pr104(aged), pr102(aged));
    }

    static PullRequest pullRequest(long number, String title, boolean draft, BranchName base, char head, long updatedAgo,
                                   List<PrNumber> closes, List<PrNumber> partOf) {
        return new PullRequest(number(number),
                               title,
                               PrState.OPEN,
                               draft,
                               base,
                               branch("kiln-" + number),
                               some("pragmaticalabs/pragmatica"),
                               sha(head),
                               none(),
                               none(),
                               ago(updatedAgo),
                               List.of(),
                               closes,
                               partOf);
    }

    static PrView pr101(long aged) {
        return new PrView(observed(pullRequest(101, "Add the kiln dashboard", false, MAIN, 'a', 300, List.of(number(90)), List.of(number(80))),
                                   ago(30 + aged)),
                          some(observed(new OpenDetail(some(true), some("clean"), some(ago(7_200))), ago(30 + aged))),
                          some(observed(new BehindFacts(some(3), some(true)), ago(40 + aged))),
                          some(observed(new RequiredChecks.Required(List.of(CI, CI_PINNED, LINT), "non_admins"), ago(30 + aged))),
                          List.of(observed(new Cell(CI, "success", 0, List.of("success")), ago(30 + aged)),
                                  observed(new Cell(CI_PINNED, "success*", 0, List.of("success")), ago(30 + aged)),
                                  observed(new Cell(LINT, "failure", 1, List.of("failure", "success")), ago(30 + aged))),
                          some(observed(new GateRead.Answered(sha('a').value(), "PASS", some(42L), some("temper")), ago(50 + aged))),
                          List.of(observed(new PrView.LinkedCheckout(BIGBOY,
                                                                     path("/home/builder/work/co/kiln-wp10"),
                                                                     CheckoutStatus.ACTIVE),
                                           ago(100))));
    }

    static PrView pr102(long aged) {
        return new PrView(observed(pullRequest(102, "Stacked follow-up", true, branch("kiln-101"), 'b', 60, List.of(), List.of()),
                                   ago(30 + aged)),
                          none(),
                          none(),
                          some(observed(new RequiredChecks.NoneRequired(RequiredChecks.NO_CHECKS, true), ago(30 + aged))),
                          List.of(),
                          some(observed(new GateRead.SchemaBroken("answer"), ago(50 + aged))),
                          List.of());
    }

    static PrView pr103(long aged) {
        return new PrView(observed(pullRequest(103, "Checks unreadable", false, RC5, 'c', 1_800, List.of(), List.of()),
                                   ago(30 + aged)),
                          none(),
                          some(observed(new BehindFacts(none(), none()), ago(40 + aged))),
                          some(observed(new RequiredChecks.Unknown("403"), ago(30 + aged))),
                          List.of(observed(new Cell(CI, "success", 0, List.of()), ago(30 + aged))),
                          none(),
                          List.of());
    }

    static PrView pr104(long aged) {
        return new PrView(observed(pullRequest(104, "Base never read", false, RC5, 'd', 2_400, List.of(), List.of()),
                                   ago(30 + aged)),
                          none(),
                          some(observed(new BehindFacts(some(0), some(false)), ago(40 + aged))),
                          none(),
                          List.of(observed(new Cell(CI, "success", 0, List.of()), ago(30 + aged))),
                          none(),
                          List.of());
    }

    // ---- bases -----------------------------------------------------------------------------------------------------
    static BaseView runs(long aged) {
        return new BaseView(List.of(new BaseView.Run(9001, RC5.value(), "CI", "completed", some("success"), sha('e').value(),
                                                     "https://github.com/pragmaticalabs/pragmatica/actions/runs/9001",
                                                     ago(3_600), ago(30 + aged)),
                                    new BaseView.Run(9002, Dashboard.SCHEDULE, "nightly", "completed", some("failure"), sha('f').value(),
                                                     "https://github.com/pragmaticalabs/pragmatica/actions/runs/9002",
                                                     ago(30_000), ago(30 + aged))),
                            List.of(new BaseView.Issue(200, "Nightly failed", "https://github.com/pragmaticalabs/pragmatica/issues/200",
                                                       ago(29_000), ago(30 + aged))));
    }

    // ---- checkouts -------------------------------------------------------------------------------------------------
    static List<HostCounts> counts() {
        return List.of(new HostCounts(BIGBOY,
                                      Map.of(CheckoutStatus.ACTIVE, 1,
                                             CheckoutStatus.IN_USE, 1,
                                             CheckoutStatus.NEEDS_SALVAGE, 1,
                                             CheckoutStatus.UNSCANNED, 1,
                                             CheckoutStatus.REMOVABLE, 120,
                                             CheckoutStatus.BASE_CLEAN, 3),
                                      5_000_000,
                                      125,
                                      some(ago(72_000))),
                       new HostCounts(HostName.MAC,
                                      Map.of(CheckoutStatus.ACTIVE, 1,
                                             CheckoutStatus.REMOVABLE, 1,
                                             CheckoutStatus.NEEDS_SALVAGE, 1),
                                      0,
                                      0,
                                      some(ago(3_600))));
    }

    static CheckoutFacts facts(String path, Option<BranchName> branch, char head, int modified, Option<Integer> salvage, String commonDir) {
        return new CheckoutFacts(path(path),
                                 some(sha(head)),
                                 branch,
                                 some("git@github.com:pragmaticalabs/pragmatica.git"),
                                 branch.map(name -> List.of(new BranchTip(name, sha(head)))).or(List.of()),
                                 modified,
                                 0,
                                 none(),
                                 ago(600),
                                 List.of(),
                                 false,
                                 commonDir,
                                 0,
                                 salvage);
    }

    static CheckoutView scanned(HostName host, String path, long idle, CheckoutStatus status, Preservation preservation,
                                PrLink link, Option<BranchName> branch, Option<Integer> salvage, String commonDir,
                                Option<Long> sizeKb) {
        return new CheckoutView(new CheckoutRow(host,
                                                path(path),
                                                commonDir,
                                                ago(idle),
                                                some(facts(path, branch, 'a', 0, salvage, commonDir)),
                                                some(HostName.MAC.equals(host) ? PassKind.PUSH : PassKind.HOT),
                                                some(ago(100)),
                                                ago(100),
                                                sizeKb,
                                                sizeKb.map(_ -> ago(3_000))),
                                some(new DerivedRow(path(path),
                                                    new Derived(status, preservation, link, some(false)),
                                                    some(ago(100)),
                                                    some(ago(40)),
                                                    some(ago(30)),
                                                    ago(90))));
    }

    static CheckoutView unscanned(HostName host, String path, long idle) {
        return new CheckoutView(new CheckoutRow(host, path(path), path + "/.git", ago(idle), none(), none(), none(), ago(100),
                                                none(), none()),
                                none());
    }

    /// One row of each listed status, plus a REMOVABLE row the dashboard must not list.
    static List<CheckoutView> listed(long aged) {
        return List.of(scanned(BIGBOY, "/home/builder/work/co/kiln-wp10", 60, CheckoutStatus.ACTIVE, new Preservation.Preserved(),
                               new PrLink(List.of(number(101)), LinkRule.BRANCH), some(branch("kiln-101")), some(0),
                               "/home/builder/work/co/kiln-wp10/.git", some(1_024L)),
                       scanned(BIGBOY, "/home/builder/work/co/bb-temper", 7_200, CheckoutStatus.IN_USE, new Preservation.HostBareOnly(),
                               PrLink.NONE, none(), none(), "/home/builder/work/co/bb-temper/.git", none()),
                       scanned(BIGBOY, "/home/builder/work/co/old-fix", 400_000, CheckoutStatus.NEEDS_SALVAGE,
                               new Preservation.Unpreserved(List.of("unpushed:fix-x", "dirty:2")), PrLink.NONE,
                               some(branch("fix-x")), some(2), "/home/builder/work/co/old-fix/.git", some(3_000_000L)),
                       unscanned(BIGBOY, "/home/builder/work/co/new-one", 30),
                       scanned(BIGBOY, "/home/builder/work/co/done", 900_000, CheckoutStatus.REMOVABLE, new Preservation.Preserved(),
                               PrLink.NONE, some(branch("done")), some(0), "/home/builder/work/co/done/.git", some(10L)));
    }

    /// Two worktrees of one repository holding 131 salvage refs (counted once), and a repository with none.
    static List<CheckoutView> mac() {
        return List.of(scanned(HostName.MAC, "/Users/s/src/pragmatica", 600, CheckoutStatus.ACTIVE, new Preservation.Preserved(),
                               PrLink.NONE, some(MAIN), some(131), "/Users/s/src/pragmatica/.git", none()),
                       scanned(HostName.MAC, "/private/tmp/scratch-501/a/b/work/wt", 90_000, CheckoutStatus.NEEDS_SALVAGE,
                               new Preservation.Unpreserved(List.of("unpushed:wip")), PrLink.NONE, some(branch("wip")), some(131),
                               "/Users/s/src/pragmatica/.git", none()),
                       scanned(HostName.MAC, "/Users/s/src/ticketing", 900_000, CheckoutStatus.REMOVABLE,
                               new Preservation.Preserved(), PrLink.NONE, some(MAIN), some(0), "/Users/s/src/ticketing/.git",
                               none()));
    }

    static Observed<Cell> cell(RequiredCheck check, String value, long secondsAgo) {
        return observed(new Cell(check, value, 0, List.of()), ago(secondsAgo));
    }
}
