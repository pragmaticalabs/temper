package org.pragmatica.kiln.service.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.source.Source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.PERIODS;
import static org.pragmatica.kiln.service.web.WebFixtures.ago;
import static org.pragmatica.kiln.service.web.WebFixtures.ok;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The vocabulary every page uses: escaping, the page shell, ages at each unit boundary, the stale bound, links, sizes.
class HtmlTest {
    /// Each marker class is read on its own, a `stale` with no `flag` beside it included; unmarked markup and escaped
    /// text that merely spells a marker are not marks.
    @Test
    void marked_eachMarkerAlone() {
        assertThat(Html.marked("<p class=\"stale\">x</p>")).isTrue();
        assertThat(Html.marked("<span class=\"red\">x</span>")).isTrue();
        assertThat(Html.marked("<i class=\"flag\">x</i>")).isTrue();
        assertThat(Html.marked("<p class=\"counts stale\">x</p>")).isTrue();
        assertThat(Html.marked("<p class=\"counts\">x <bdi>stale</bdi></p>")).isFalse();
        assertThat(Html.marked("<p>class=&quot;stale&quot; &quot;red&quot; flag</p>")).isFalse();
        assertThat(Html.marked("<span class=\"cell ok\">✓</span>")).isFalse();
        assertThat(Html.marked("")).isFalse();
    }

    @Test
    void escape_theFiveCharacters() {
        assertThat(Html.escape("a&b<c>d\"e'f")).isEqualTo("a&amp;b&lt;c&gt;d&quot;e&#39;f");
        assertThat(Html.escape("plain")).isEqualTo("plain");
    }

    @Test
    void page_refreshesEvery30s_andEscapesItsTitle() {
        var page = Html.page("<t>", "<nav>top</nav>", "<p>body</p>");

        assertThat(page).startsWith("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                        .contains("<meta http-equiv=\"refresh\" content=\"30\">", "<title>kiln: &lt;t&gt;</title>",
                                  "<meta name=\"color-scheme\" content=\"light dark\">",
                                  "<header class=\"top\">\n<h1>kiln: &lt;t&gt;</h1>\n<nav>top</nav>\n</header>\n<p>body</p>\n</body>\n</html>\n");
    }

    @Test
    void span_eachUnitBoundary() {
        assertThat(Html.span(0)).isEqualTo("0s");
        assertThat(Html.span(59)).isEqualTo("59s");
        assertThat(Html.span(60)).isEqualTo("1m");
        assertThat(Html.span(3_599)).isEqualTo("59m");
        assertThat(Html.span(3_600)).isEqualTo("1h 0m");
        assertThat(Html.span(3_660)).isEqualTo("1h 1m");
        assertThat(Html.span(172_799)).isEqualTo("47h 59m");
        assertThat(Html.span(172_800)).isEqualTo("2d 0h");
        assertThat(Html.span(266_400)).isEqualTo("3d 2h");
    }

    @Test
    void age_pastAndFuture() {
        assertThat(Html.age(ago(90), NOW)).isEqualTo("1m ago");
        assertThat(Html.age(NOW, NOW)).isEqualTo("0s ago");
        assertThat(Html.age(NOW.plusSeconds(1), NOW)).isEqualTo("in 1s");
        assertThat(Html.stamp(ago(90).plusMillis(500), NOW)).isEqualTo("2026-10-30T11:58:30Z (1m ago)");
    }

    /// Stale strictly past 3 × the period: 180 s is fresh under 60 s, 181 s is stale.
    @Test
    void stale_strictlyPastThreePeriods() {
        assertThat(Html.stale(ago(180), NOW, Duration.ofSeconds(60))).isFalse();
        assertThat(Html.stale(ago(181), NOW, Duration.ofSeconds(60))).isTrue();
        assertThat(Html.observed("x", ago(181), NOW, Duration.ofSeconds(60))).isEqualTo(
                "<span class=\"fact stale\">x <small>(observed 3m ago)</small> <span class=\"flag\">STALE</span></span>");
        assertThat(Html.observed("x", ago(180), NOW, Duration.ofSeconds(60))).isEqualTo(
                "<span class=\"fact\">x <small>(observed 3m ago)</small></span>");
    }

    @Test
    void panel_redAndPlain() {
        assertThat(Html.panel("p", "T&lt;", true, "<p>a</p>", "<p>b</p>")).isEqualTo(
                "<section id=\"p\" class=\"panel red\">\n<h2>T&lt; <span class=\"flag\">STALE</span></h2>\n<p>a</p>\n<p>b</p>\n</section>");
        assertThat(Html.panel("p", "T", false, "a", "b")).isEqualTo("<section id=\"p\" class=\"panel\">\n<h2>T</h2>\na\nb\n</section>");
    }

    @Test
    void asOf_timeOrNever() {
        assertThat(Html.asOf(some(ago(60)), NOW)).isEqualTo("<p class=\"asof\">as of 2026-10-30T11:59:00Z (1m ago)</p>");
        assertThat(Html.asOf(none(), NOW)).isEqualTo("<p class=\"asof\">as of never</p>");
    }

    @Test
    void link_encodesThenEscapes() {
        assertThat(Html.link("/non-git", List.of(Map.entry("host", "bigboy"), Map.entry("root", "/a b&\"<")), "list")).isEqualTo(
                "<a href=\"/non-git?host=bigboy&amp;root=%2Fa+b%26%22%3C\">list</a>");
    }

    @Test
    void external_onlyHttps() {
        assertThat(Html.external("https://github.com/x", "t")).isEqualTo("<a href=\"https://github.com/x\">t</a>");
        assertThat(Html.external("javascript:alert(1)", "t")).isEqualTo("t");
        assertThat(Html.external("http://github.com/x", "t")).isEqualTo("t");
    }

    @Test
    void size_eachUnit() {
        assertThat(Html.size(0)).isEqualTo("0 KiB");
        assertThat(Html.size(1_023)).isEqualTo("1023 KiB");
        assertThat(Html.size(1_024)).isEqualTo("1.0 MiB");
        assertThat(Html.size(1_048_575)).isEqualTo("1024.0 MiB");
        assertThat(Html.size(1_048_576)).isEqualTo("1.0 GiB");
        assertThat(Html.size(1_572_864)).isEqualTo("1.5 GiB");
    }

    @Test
    void abbreviated_sevenOrWhole() {
        assertThat(Html.abbreviated("0123456789")).isEqualTo("0123456");
        assertThat(Html.abbreviated("abc")).isEqualTo("abc");
    }

    @Test
    void sources_staleNeverAndOldest() {
        var sources = new Sources(List.of(ok(Source.GITHUB, 30), ok(Source.MAC, 7_000)), PERIODS);

        assertThat(sources.stale(Source.MIRROR, NOW)).isTrue();
        assertThat(sources.stale(Source.GITHUB, NOW)).isFalse();
        assertThat(sources.stale(Source.MAC, NOW)).isFalse();
        assertThat(sources.oldestSuccess(List.of(Source.GITHUB, Source.MAC))).isEqualTo(some(ago(7_000)));
        assertThat(sources.oldestSuccess(List.of(Source.GITHUB, Source.MIRROR))).isEqualTo(none());
        assertThat(sources.oldestSuccess(List.of())).isEqualTo(none());
        assertThat(Sources.sourceOf(HostName.MAC)).isEqualTo(Source.MAC);
    }

    @Test
    void periods_perSource() {
        assertThat(PERIODS.of(Source.GITHUB)).isEqualTo(Duration.ofSeconds(60));
        assertThat(PERIODS.of(Source.host(WebFixtures.BIGBOY))).isEqualTo(Duration.ofMinutes(2));
        assertThat(PERIODS.of(Source.MAC)).isEqualTo(Duration.ofHours(2));
        assertThat(new Periods(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(4),
                               Duration.ofSeconds(5)).of(Source.TEMPER)).isEqualTo(Duration.ofSeconds(3));
        assertThat(new Periods(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(4),
                               Duration.ofSeconds(5)).of(Source.MIRROR)).isEqualTo(Duration.ofSeconds(2));
    }
}
