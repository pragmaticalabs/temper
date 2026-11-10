package org.pragmatica.kiln.service.web;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PullsPage;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.NonGitView;
import org.pragmatica.kiln.service.store.Page;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.WorkBoard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.model.Observed.observed;
import static org.pragmatica.kiln.service.web.WebFixtures.BIGBOY;
import static org.pragmatica.kiln.service.web.WebFixtures.CLOUD;
import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.PERIODS;
import static org.pragmatica.kiln.service.web.WebFixtures.ago;
import static org.pragmatica.kiln.service.web.WebFixtures.branch;
import static org.pragmatica.kiln.service.web.WebFixtures.check;
import static org.pragmatica.kiln.service.web.WebFixtures.fresh;
import static org.pragmatica.kiln.service.web.WebFixtures.ok;
import static org.pragmatica.kiln.service.web.WebFixtures.path;
import static org.pragmatica.kiln.service.web.WebFixtures.pr101;
import static org.pragmatica.kiln.service.web.WebFixtures.pullRequest;
import static org.pragmatica.kiln.service.web.WebFixtures.scanned;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Every text value that reaches a page is hostile input (branch names, titles, reasons, the digest): each field below
/// carries its own marker `z"'<zN>&`, and every marker must appear escaped, `z&quot;&#39;&lt;zN&gt;&amp;`, while no
/// raw `<z` survives anywhere. A field the renderer stopped showing fails as loudly as one it stopped escaping.
class EscapingTest {
    private static final int MARKERS = 34;
    private static final Pattern RAW = Pattern.compile("<z\\d");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private static String hostile(int marker) {
        return "z\"'<z" + marker + ">&";
    }

    private static String escaped(int marker) {
        return "z&quot;&#39;&lt;z" + marker + "&gt;&amp;";
    }

    private static Sources sources(SourceState temper) {
        return new Sources(List.of(new SourceStatus(Source.GITHUB, new SourceState.GithubDown(hostile(1), ago(30)), some(ago(30))),
                                   ok(Source.MIRROR, 30),
                                   new SourceStatus(Source.TEMPER, temper, some(ago(30))),
                                   new SourceStatus(Source.host(BIGBOY), new SourceState.Unreachable(hostile(3), ago(30)), some(ago(30))),
                                   new SourceStatus(Source.host(CLOUD), new SourceState.Incomplete(hostile(4), ago(30)), some(ago(30))),
                                   ok(Source.MAC, 30)),
                           PERIODS);
    }

    private static List<PrView> prs() {
        var base = pr101(0);
        var hostileCheck = check(hostile(9), some(15368L));
        var pr = new PrView(observed(pullRequest(101, hostile(6), false, branch(hostile(7)), 'a', 60, List.of(), List.of()), ago(30)),
                            some(observed(new OpenDetail(some(false), some(hostile(12)), none()), ago(30))),
                            base.behind(),
                            some(observed(new RequiredChecks.Required(List.of(hostileCheck), hostile(8)), ago(30))),
                            List.of(observed(new Cell(hostileCheck, hostile(10), 0, List.of(hostile(11))), ago(30))),
                            some(observed(new GateRead.Answered("x", hostile(13), none(), some(hostile(14))), ago(30))),
                            List.of(observed(new PrView.LinkedCheckout(BIGBOY, path("/w/" + hostile(15)), CheckoutStatus.ACTIVE),
                                             ago(30))));
        var none = new PrView(observed(pullRequest(102, "none", false, branch("main"), 'b', 70, List.of(), List.of()), ago(30)),
                              none(), none(),
                              some(observed(new RequiredChecks.NoneRequired(hostile(16), false), ago(30))),
                              List.of(), some(observed(new GateRead.SchemaBroken(hostile(18)), ago(30))), List.of());
        var unknown = new PrView(observed(pullRequest(103, "unknown", false, branch("main"), 'c', 80, List.of(), List.of()), ago(30)),
                                 none(), none(), some(observed(new RequiredChecks.Unknown(hostile(17)), ago(30))), List.of(), none(),
                                 List.of());

        return List.of(pr, none, unknown);
    }

    private static BaseView runs() {
        return new BaseView(List.of(new BaseView.Run(1, hostile(24), hostile(19), hostile(20), some(hostile(21)), "<z22>abc",
                                                     "https://x/\"<z23>", ago(60), ago(30)),
                                    new BaseView.Run(2, "main", "evil", "completed", none(), "0000000",
                                                     "javascript:alert(1)", ago(60), ago(30))),
                            List.of(new BaseView.Issue(7, hostile(25), "https://x/'<z26>", ago(60), ago(30))));
    }

    private static List<CheckoutView> listed() {
        return List.of(scanned(BIGBOY, "/w/" + hostile(28), 60, CheckoutStatus.NEEDS_SALVAGE,
                               new Preservation.Unpreserved(List.of(hostile(30))), PrLink.NONE, some(branch(hostile(29))), none(),
                               "/w/.git", none()));
    }

    private static DashboardView view(SourceState temper) {
        var base = fresh();

        return new DashboardView(sources(temper),
                                 List.of(branch("main")),
                                 List.of(BIGBOY, CLOUD),
                                 base.budget(),
                                 List.of(observed(PullsPage.Skipped.skipped(WebFixtures.number(5), hostile(5), none()), ago(30))),
                                 base.passes(),
                                 prs(),
                                 runs(),
                                 base.counts(),
                                 listed(),
                                 List.of(scanned(HostName.MAC, "/m/" + hostile(33), 60, CheckoutStatus.REMOVABLE,
                                                 new Preservation.Preserved(), PrLink.NONE, none(), none(), "/m/.git", none())),
                                 Map.of(BIGBOY, List.of(new NonGitView(hostile(31), path("/n/" + hostile(32)), ago(30)))),
                                 some(observed("digest " + hostile(34) + "\n</pre><script>alert(1)</script>", ago(30))), WorkBoard.EMPTY);
    }

    private static String rendered() {
        return String.join("\n",
                           Dashboard.render(view(new SourceState.TemperSchema(hostile(2), ago(30))), NOW, none()),
                           Dashboard.render(view(new SourceState.TemperDown(hostile(27), ago(30))), NOW, none()),
                           CheckoutPages.drillDown(CheckoutStatus.NEEDS_SALVAGE, some(BIGBOY), new Page<>(listed(), 1, 1), List.of(BIGBOY, CLOUD),
                                                   sources(new SourceState.Ok(ago(30))), NOW),
                           CheckoutPages.nonGit(BIGBOY, hostile(31), view(new SourceState.Ok(ago(30))).nonGit().get(BIGBOY),
                                                List.of(BIGBOY, CLOUD), sources(new SourceState.Ok(ago(30))), NOW));
    }

    /// Positive: every marker is present, escaped. Negative: no raw `<z` anywhere, and the digest's `</pre><script>`
    /// stays text.
    @Test
    void escaping_hostileInputInEveryField_isEscaped() {
        var html = rendered();

        IntStream.rangeClosed(1, MARKERS)
                 .filter(marker -> marker != 22 && marker != 23 && marker != 26)
                 .forEach(marker -> assertThat(html).as("marker " + marker).contains(escaped(marker)));
        assertThat(html).contains("&lt;z22&gt;ab", "href=\"https://x/&quot;&lt;z23&gt;\"", "href=\"https://x/&#39;&lt;z26&gt;\"",
                                  "&lt;/pre&gt;&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(RAW.matcher(html).find()).as("a raw <z marker").isFalse();
    }

    /// The control: the same render without escaping would carry the raw markers, so the check above can fail.
    @Test
    void escaping_control_rawMarkersAreDetectable() {
        assertThat(RAW.matcher(hostile(1)).find()).isTrue();
        assertThat(Html.escape(hostile(1))).isEqualTo(escaped(1));
    }

    /// No JavaScript on any page, hostile input included: no `<script>` tag, no `on*=` attribute, no `javascript:` link.
    @Test
    void noJavaScript_onAnyPage() {
        var pages = List.of(rendered(),
                            Dashboard.render(fresh(), NOW, none()),
                            Dashboard.render(WebFixtures.degraded(), NOW, none()));

        pages.forEach(EscapingTest::assertNoScript);
    }

    /// The control: each forbidden form is detected by the same check.
    @Test
    void noJavaScript_control_detectsEachForm() {
        assertThat(scriptFound("<p>ok</script>")).isFalse();
        assertThat(scriptFound("<script>x</script>")).isTrue();
        assertThat(scriptFound("<p onclick=\"x\">")).isTrue();
        assertThat(scriptFound("<a href=\"javascript:x\">")).isTrue();
        assertThat(scriptFound("<p>onclick=x</p>")).isFalse();
    }

    private static void assertNoScript(String html) {
        assertThat(scriptFound(html)).as("a script, an on*= attribute or a javascript: URL").isFalse();
    }

    /// Only tags are examined: escaped text holds no `<`, so every tag on the page is the renderer's own.
    private static boolean scriptFound(String html) {
        Matcher tags = TAG.matcher(html);

        return tags.results()
                   .map(tag -> tag.group().toLowerCase())
                   .anyMatch(tag -> tag.startsWith("<script") || tag.matches("(?s).*\\son[a-z]+\\s*=.*") || tag.contains("javascript:"));
    }
}
