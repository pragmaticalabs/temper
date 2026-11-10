package org.pragmatica.kiln.service.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.lang.Option;


/// The pages' HTML vocabulary (spec §6): escaping, the page shell, panels, times and ages. Server-rendered with no
/// JavaScript at all: no `<script>`, no `on*=` attribute, no `javascript:` link, and the page refreshes itself through
/// `<meta http-equiv="refresh">`.
///
/// A parameter named `text` is raw and is escaped here; one named `html` is already markup. Every value that comes
/// from GitHub, temper, a host or the Mac is raw: branch names, titles, reasons and the digest are hostile input.
public sealed interface Html {
    int REFRESH_SECONDS = 30;
    String STALE = "<span class=\"flag\">STALE</span>";
    String HTTPS = "https://";
    /// Replaced in every text: control characters but `\n` and `\t`, U+2028/U+2029, and an unpaired surrogate
    /// (`\p{Cs}` matches only a lone one: a valid pair is one code point outside the category).
    Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]|[\\u2028\\u2029]|\\p{Cs}");
    /// Replaced in verbatim text (the digest in `<pre>`): the same, but `\r` is kept too, so CRLF survives.
    Pattern VERBATIM_CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t\\r]]|[\\u2028\\u2029]|\\p{Cs}");
    String REPLACEMENT = "\uFFFD";
    /// A clock this far ahead is skew, never a fresh observation.
    Duration SKEW = Duration.ofSeconds(60);

    /// The tabs of the dashboard, by section id; the first is shown when the URL names none.
    List<String> TABS = List.of("prs",
                                "work",
                                "bases",
                                "checkouts",
                                "temper",
                                "pipeline",
                                "conflicts",
                                "questions",
                                "sources");

    /// pragmatica.dev's tokens (website/styles/style.css lines 5-43: paper, ink, line, amber, the font stacks and the
    /// dark block), plus three status colours, each at least 4.5:1 on --paper, --paper-raised and --code-bg in both themes. Amber
    /// means only "you are here": the active tab's ring, never a status.
    String TOKENS = ":root{--paper:#f4f3f0;--paper-raised:#fbfaf7;--ink:#1a2840;--ink-soft:#4a5468;--ink-faint:#b6b1a5;"
                  + "--line:#c9c5bc;--code-bg:#eceae4;--amber:#f59e0b;--amber-dark:#d97706;--amber-light:#fbbf24;"
                  + "--ui:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;"
                  + "--mono:'JetBrains Mono','Fira Code',ui-monospace,SFMono-Regular,Menlo,monospace;"
                  + "--ok:#126b33;--warn:#9a4507;--error:#b91c1c}"
                  + "@media (prefers-color-scheme:dark){:root{--paper:#14181f;--paper-raised:#1a1f28;--ink:#e8ecf5;"
                  + "--ink-soft:#9aa5bd;--ink-faint:#5a6478;--line:#2c3341;--code-bg:#1e2530;"
                  + "--ok:#4ade80;--warn:#fb923c;--error:#f87171}}";

    String LAYOUT = "*{box-sizing:border-box}"
                  + "body{margin:0;background:var(--paper);color:var(--ink);font-family:var(--ui);font-size:14px;line-height:1.45}"
                  + "a{color:var(--ink);text-decoration-color:var(--line);text-underline-offset:2px}"
                  + "header.top{position:sticky;top:0;z-index:2;background:var(--paper);border-bottom:1px solid var(--line);"
                  + "padding:8px 16px}"
                  + "h1{font-size:16px;margin:0 0 6px}h2{font-size:17px;margin:6px 0}h3{font-size:15px;margin:8px 0 4px}"
                  + "main{padding:4px 16px 16px}"
                  + ".strip,.tabs{display:flex;flex-wrap:wrap;gap:6px;margin:6px 0;align-items:center}"
                  + ".chip{border:1.5px solid var(--ink);border-radius:22px;padding:4px 12px;font-size:13.5px;"
                  + "text-decoration:none;background:transparent;color:var(--ink)}"
                  + ".chip .count{margin-left:6px;font-size:12px;color:var(--ink-soft)}.chip .count.warn{color:var(--warn)}"
                  + ".chip.red{border-color:var(--error)}"
                  + ".badge{border:1.5px solid currentColor;border-radius:22px;padding:2px 10px;font-size:12.5px;"
                  + "background:var(--paper-raised)}"
                  + ".ok{color:var(--ok)}.warn{color:var(--warn)}.error{color:var(--error)}"
                  + ".panel{background:var(--paper-raised);border:1px solid var(--line);border-radius:8px;margin:8px 0;"
                  + "padding:6px 14px}"
                  + ".host{border:1px solid var(--line);border-radius:6px;padding:4px 10px;margin:8px 0}"
                  + ".panel.red,.host.red{border:2px solid var(--error)}"
                  + ".flag{color:var(--paper-raised);background:var(--error);padding:0 5px;border-radius:4px;font-size:11.5px;"
                  + "font-weight:600}"
                  + ".stale{color:var(--error)}"
                  + ".grey,.asof,small{color:var(--ink-soft)}.asof{font-size:12px;margin:2px 0 6px}"
                  + "table{width:100%;border-collapse:collapse;margin:0 0 14px;font-size:13.5px}"
                  + "th,td{text-align:left;padding:6px 8px;border:1px solid var(--line);vertical-align:top}"
                  + "th{background:var(--paper);font-weight:600}"
                  + "td.red{border-left:4px solid var(--error)}"
                  + ".cells{margin-top:4px}"
                  + ".cell{display:inline-block;border:1px solid var(--line);border-radius:10px;padding:0 6px;margin:1px;"
                  + "font-size:12.5px;white-space:nowrap}"
                  + ".cell.ok{border-color:var(--ok)}.cell.fail{color:var(--error)}"
                  + ".cell.stale{color:var(--error);border-color:var(--error)}"
                  + "pre{white-space:pre-wrap;font-family:var(--mono);font-size:12.5px;background:var(--code-bg);padding:8px;"
                  + "border-radius:6px;color:var(--ink)}";

    /// The dashboard's tabs: the server names the open one in `main[data-tab]` (from `?tab=`, an allowlisted id), and
    /// only that section shows. A CSS-only tab (`:target`, a radio's `:checked`) does not survive the 30 s meta refresh in
    /// Chrome: a reload of a URL with a fragment is a same-document navigation, and form state is not restored (measured
    /// 2026-11-01); a query is reloaded as it is. The open tab wears pragmatica's amber ring as an inset shadow, so a red
    /// tab's red border survives it, and a 2px ink underline, a cue of at least 3:1 (WCAG 1.4.11) where amber on light
    /// paper is 1.94:1.
    String TABBED = "main.tabbed>section.panel{display:none}" + TABS.stream()
                                                                    .map(id -> "main.tabbed[data-tab=\"" + id
                                                                              + "\"]>section#" + id)
                                                                    .collect(Collectors.joining(","))
                  + "{display:block}"
                  + ".chip.active{box-shadow:0 0 0 2px var(--amber) inset;font-weight:600;"
                  + "text-decoration:underline 2px var(--ink);text-underline-offset:4px}";

    /// The Work tab's board ([WorkPage]): one column per phase, side by side, scrolling sideways when the page is narrow.
    String BOARD = ".board{display:grid;grid-template-columns:repeat(9,minmax(150px,1fr));gap:8px;overflow-x:auto;"
                 + "margin:6px 0 14px}"
                 + ".column{background:var(--paper);border:1px solid var(--line);border-radius:6px;padding:4px 6px;"
                 + "min-height:60px}"
                 + ".column h3{font-size:13.5px;margin:2px 0 6px}"
                 + ".card{background:var(--paper-raised);border:1px solid var(--line);border-radius:6px;padding:4px 6px;"
                 + "margin:0 0 6px;font-size:12.5px;overflow-wrap:anywhere}"
                 + ".card p{margin:2px 0}.card .round{font-family:var(--mono);font-weight:600}";

    String STYLE = TOKENS + LAYOUT + TABBED + BOARD;

    /// Escapes the five characters HTML gives meaning to, in text and in quoted attributes alike, after replacing every
    /// [#CONTROL] character with U+FFFD: a NUL, an escape sequence or a lone surrogate never reaches the page.
    static String escape(String text) {
        return escaped(CONTROL, text);
    }

    /// Verbatim text for `<pre>`: escaped and isolated as [#text], keeping `\r` beside `\n` and `\t`.
    static String verbatim(String raw) {
        return "<bdi>" + escaped(VERBATIM_CONTROL, raw) + "</bdi>";
    }

    private static String escaped(Pattern control, String text) {
        return control.matcher(text)
                      .replaceAll(REPLACEMENT)
                      .replace("&", "&amp;")
                      .replace("<", "&lt;")
                      .replace(">", "&gt;")
                      .replace("\"", "&quot;")
                      .replace("'", "&#39;");
    }

    /// A user-supplied value as text: escaped, and isolated in `<bdi>`, so a bidi override (U+202E) in a title or a
    /// branch name cannot reorder the text around it.
    static String text(String raw) {
        return "<bdi>" + escape(raw) + "</bdi>";
    }

    /// A whole page: the doctype, the 30 s refresh, the style, a sticky header holding the title and `topHtml` (the
    /// status strip and the tabs, so they show on every page and every tab), and the body.
    static String page(String title, String topHtml, String bodyHtml) {
        return String.join("\n",
                           "<!DOCTYPE html>",
                           "<html lang=\"en\">",
                           "<head>",
                           "<meta charset=\"utf-8\">",
                           "<meta name=\"color-scheme\" content=\"light dark\">",
                           "<meta http-equiv=\"refresh\" content=\"" + REFRESH_SECONDS + "\">",
                           "<title>kiln: " + escape(title) + "</title>",
                           "<style>" + STYLE + "</style>",
                           "</head>",
                           "<body>",
                           "<header class=\"top\">",
                           "<h1>kiln: " + escape(title) + "</h1>",
                           topHtml,
                           "</header>",
                           bodyHtml,
                           "</body>",
                           "</html>",
                           "");
    }

    /// Every `class` attribute; user text cannot hold one, since its quotes are escaped.
    Pattern CLASS = Pattern.compile("class=\"([^\"]*)\"");
    /// The class tokens that mark a fact red or stale.
    Set<String> MARKS = Set.of("red", "stale", "flag");

    /// Whether the markup marks anything red or stale: what a section's tab is flagged by, read from the very classes
    /// that draw the marks, so the tab and its section cannot disagree.
    static boolean marked(String html) {
        return CLASS.matcher(html)
                    .results()
                    .flatMap(match -> Arrays.stream(match.group(1).split(" ")))
                    .anyMatch(MARKS::contains);
    }

    /// The status strip: one badge per source, above the tabs on every page, so a failed or stale source shows whatever
    /// tab or page is open.
    static String strip(List<Source> sources, Sources statuses, Instant now) {
        return "<nav class=\"strip\" aria-label=\"sources\">" + sources.stream()
                                                                       .map(source -> badge(source, statuses, now))
                                                                       .collect(Collectors.joining(" "))
             + "</nav>";
    }

    /// A source's badge: its key, its state word and the age of its last success, coloured by [#level], with the
    /// `STALE` flag when stale, so the state never rests on colour alone.
    static String badge(Source source, Sources statuses, Instant now) {
        return "<span class=\"badge " + level(source, statuses, now)
             + "\"><b>" + escape(source.key())
             + "</b> " + statuses.of(source)
                                 .map(status -> word(status.state()))
                                 .or("NO STATE")
             + " · " + statuses.lastSuccess(source)
                               .map(at -> age(at, now))
                               .or("never") + flag(statuses.stale(source, now))
             + "</span>";
    }

    /// Never greener than the source: `error` when stale or never recorded (the red of the panels), `warn` when its last
    /// success is within the bound but its state is a failure, `ok` only for an OK state within the bound.
    static String level(Source source, Sources statuses, Instant now) {
        if (statuses.stale(source, now)) {
            return "error";
        }

        return statuses.of(source)
                       .filter(status -> status.state() instanceof SourceState.Ok)
                       .isPresent()
               ? "ok"
               : "warn";
    }

    /// A state's name as the spec spells it, without its detail.
    static String word(SourceState state) {
        return switch (state) {
            case SourceState.Ok _ -> "OK";
            case SourceState.Unreachable _ -> "UNREACHABLE";
            case SourceState.Incomplete _ -> "INCOMPLETE";
            case SourceState.RateLimited _ -> "RATE-LIMITED";
            case SourceState.GithubDown _ -> "GITHUB-DOWN";
            case SourceState.TemperDown _ -> "TEMPER-DOWN";
            case SourceState.TemperSchema _ -> "TEMPER-SCHEMA";
        };
    }

    /// A panel: red, and flagged `STALE` in its heading, when its source is older than 3 × its period.
    static String panel(String id, String titleHtml, boolean red, String asOfHtml, String bodyHtml) {
        return String.join("\n",
                           "<section id=\"" + id + "\" class=\"panel" + (red
                                                                         ? " red"
                                                                         : "") + "\">",
                           "<h2>" + titleHtml + flag(red) + "</h2>",
                           asOfHtml,
                           bodyHtml,
                           "</section>");
    }

    static String flag(boolean stale) {
        return stale
               ? " " + STALE
               : "";
    }

    /// A panel's `as of` line: the time of the source's last success, or `never`.
    static String asOf(Option<Instant> at, Instant now) {
        return "<p class=\"asof\">as of " + at.map(time -> stamp(time, now))
                                              .or("never") + "</p>";
    }

    /// A time and its age: `2026-10-30T12:00:00Z (2m ago)`.
    static String stamp(Instant at, Instant now) {
        return at.truncatedTo(ChronoUnit.SECONDS) + " (" + age(at, now) + ")";
    }

    /// The age of an observation: `2m ago`; `in 5s` within the skew allowed; beyond it, `in the future (clock skew)`.
    static String age(Instant at, Instant now) {
        return future(at, now)
               ? "in the future (clock skew)"
               : until(at, now);
    }

    /// The distance to a time that may lie ahead (a rate-limit reset): `in 40m`, or `2m ago` once passed.
    static String until(Instant at, Instant now) {
        var seconds = Duration.between(at, now).getSeconds();

        return seconds < 0
               ? "in " + span(-seconds)
               : span(seconds) + " ago";
    }

    /// A time and its distance, for a time that may lie ahead: `2026-10-30T12:40:00Z (in 40m)`.
    static String deadline(Instant at, Instant now) {
        return at.truncatedTo(ChronoUnit.SECONDS) + " (" + until(at, now) + ")";
    }

    /// Whether `at` lies more than [#SKEW] after `now`: the clocks disagree, and the time proves nothing fresh.
    static boolean future(Instant at, Instant now) {
        return at.isAfter(now.plus(SKEW));
    }

    /// `42s`, `5m`, `3h 12m` under two days, then `4d 3h`.
    static String span(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }

        if (seconds < 3_600) {
            return seconds / 60 + "m";
        }

        if (seconds < 172_800) {
            return seconds / 3_600 + "h " + seconds % 3_600 / 60 + "m";
        }

        return seconds / 86_400 + "d " + seconds % 86_400 / 3_600 + "h";
    }

    /// Whether a fact observed at `observedAt` is older than 3 × its source's period at `now` (the bound
    /// [SourceStatus#staleAt] applies to a source, applied to one fact), or lies in the future beyond [#SKEW]: a time
    /// the clocks disagree on is never fresh.
    static boolean stale(Instant observedAt, Instant now, Duration period) {
        return observedAt.isBefore(now.minus(period.multipliedBy(SourceStatus.STALE_PERIODS))) || future(observedAt, now);
    }

    /// A source's state as the spec spells it, each failure with the time it began.
    static String state(SourceState state, Instant now) {
        return switch (state) {
            case SourceState.Ok _ -> "OK";
            case SourceState.Unreachable unreachable -> "UNREACHABLE(" + text(unreachable.reason()) + ") since " + stamp(unreachable.since(),
                                                                                                                         now);
            case SourceState.Incomplete incomplete -> "INCOMPLETE(" + text(incomplete.reason()) + ") at " + stamp(incomplete.at(),
                                                                                                                  now);
            case SourceState.RateLimited limited -> "RATE-LIMITED(reset " + deadline(limited.reset(), now) + ") since " + stamp(limited.since(),
                                                                                                                                now);
            case SourceState.GithubDown down -> "GITHUB-DOWN(" + text(down.status()) + ") since " + stamp(down.since(),
                                                                                                          now);
            case SourceState.TemperDown down -> "TEMPER-DOWN(" + text(down.reason()) + ") since " + stamp(down.since(),
                                                                                                          now);
            case SourceState.TemperSchema schema -> "TEMPER-SCHEMA(" + text(schema.field()) + ") since " + stamp(schema.since(),
                                                                                                                 now);
        };
    }

    /// A fact with its observation age; a stale one is marked, never shown as current.
    static String observed(String html, Instant observedAt, Instant now, Duration period) {
        var stale = stale(observedAt, now, period);

        return "<span class=\"" + (stale
                                   ? "fact stale"
                                   : "fact")
             + "\">" + html
             + " <small>(observed " + age(observedAt, now)
             + ")</small>" + flag(stale)
             + "</span>";
    }

    /// A link to one of kiln's own pages, its query in the given order; every query value is URL-encoded, then the
    /// whole is escaped.
    static String link(String path, List<Map.Entry<String, String>> query, String textHtml) {
        return "<a href=\"" + escape(path + "?" + query.stream().map(Html::parameter).collect(Collectors.joining("&")))
             + "\">" + textHtml
             + "</a>";
    }

    private static String parameter(Map.Entry<String, String> entry) {
        return entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8);
    }

    /// A link to GitHub, only for an `https://` URL; any other is shown as text, so no `javascript:` URL is ever a link.
    static String external(String url, String textHtml) {
        return url.startsWith(HTTPS)
               ? "<a href=\"" + escape(url) + "\">" + textHtml + "</a>"
               : textHtml;
    }

    /// A size in KiB as the largest unit that keeps it ≥ 1: `512 KiB`, `1.5 MiB`, `2.0 GiB`.
    static String size(long kb) {
        if (kb < 1_024) {
            return kb + " KiB";
        }

        if (kb < 1_048_576) {
            return String.format(Locale.ROOT, "%.1f MiB", kb / 1_024.0);
        }

        return String.format(Locale.ROOT, "%.1f GiB", kb / 1_048_576.0);
    }

    /// The first 7 characters of a commit id, as the dashboard shows a head; a shorter text is shown whole.
    static String abbreviated(String sha) {
        return sha.substring(0,
                             Math.min(7, sha.length()));
    }

    /// Lines joined by a newline.
    static String lines(List<String> lines) {
        return String.join("\n", lines);
    }

    record unused() implements Html {}
}
