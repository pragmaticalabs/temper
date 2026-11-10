package org.pragmatica.kiln.service.web;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.NonGitView;
import org.pragmatica.kiln.service.store.Page;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.some;


/// The checkout pages (spec §6 panel 4, kiln-f1-design §4): the drill-down of one status (`GET /checkouts`, 100 rows a
/// page, sorted by idle time, longest first, with sizes) and the non-git list (`GET /non-git`), plus the checkout table
/// the dashboard shares. Every row shows the pass and time of its last observation (spec §5.5) and the age of every fact
/// it shows; the rows of a stale or failed host are marked stale (spec §5.6).
public sealed interface CheckoutPages {
    String HEADER = "<tr><th>checkout</th><th>status</th><th>last activity</th><th>head</th><th>preservation</th>"
                  + "<th>linked PRs</th><th>salvage refs</th><th>last observed</th><th>size</th></tr>";

    /// Idle time, longest first: the oldest `activityAt` first, then host and path, so the order is total.
    Comparator<CheckoutView> IDLE_FIRST = Comparator.comparing((CheckoutView view) -> view.row()
                                                                                          .activityAt())
                                                    .thenComparing(view -> view.row()
                                                                               .host()
                                                                               .value())
                                                    .thenComparing(view -> view.row()
                                                                               .path()
                                                                               .value());

    /// One drill-down page of `status`, on one host or on every host. The hosts it rests on, and so its colour, its
    /// `as of` line and the states it lists, come from the configured hosts and the source states, never from the rows
    /// on the page: an empty page while every host is unreachable is red, not a clean "no rows".
    static String drillDown(CheckoutStatus status,
                            Option<HostName> host,
                            Page<CheckoutView> page,
                            List<HostName> configured,
                            Sources sources,
                            Instant now) {
        // A lambda, not `List::of`: that reference matches both `Option.map` overloads (Fn1 and Supplier).
        var hosts = host.map(name -> List.of(name)).or(() -> everyHost(configured, sources));

        return Html.page(status(status) + " checkouts",
                         top(sources.all(configured), sources, now),
                         main(Html.panel("drill-down",
                                         status(status) + " checkouts on " + host.map(HostName::value).or("every host"),
                                         sources.anyStale(Sources.sourcesOf(hosts), now),
                                         Html.asOf(sources.oldestSuccess(Sources.sourcesOf(hosts)),
                                                   now),
                                         Html.lines(List.of(hostStates(hosts, sources, now),
                                                            pager(status, host, page),
                                                            table(page.rows(), sources, now))))));
    }

    /// The header of a drill-down page: the same status strip as the dashboard's, and the tabs as links back to it.
    private static String top(List<Source> strip, Sources sources, Instant now) {
        return Html.lines(List.of(Html.strip(strip, sources, now), Dashboard.tabLinks()));
    }

    private static String main(String html) {
        return Html.lines(List.of("<main>", html, "</main>"));
    }

    /// Every host a page without a host filter shows rows of: the configured hosts, every host with a state, and the
    /// Mac, whose rows the store's every-host page holds too.
    static List<HostName> everyHost(List<HostName> configured, Sources sources) {
        return Stream.concat(sources.hosts(configured).stream(),
                             Stream.of(HostName.MAC))
                     .toList();
    }

    /// Each host's state and last success, stale ones flagged.
    private static String hostStates(List<HostName> hosts, Sources sources, Instant now) {
        return Html.lines(Stream.of(Stream.of("<ul class=\"hosts\">"),
                                    hosts.stream().map(host -> hostState(host, sources, now)),
                                    Stream.of("</ul>"))
                                .flatMap(lines -> lines)
                                .toList());
    }

    private static String hostState(HostName host, Sources sources, Instant now) {
        var source = Sources.sourceOf(host);
        var stale = sources.stale(source, now);

        return "<li" + staleClass(stale)
             + "><b>" + host.value()
             + "</b>: " + sources.of(source)
                                 .map(status -> Html.state(status.state(),
                                                           now))
                                 .or("no state recorded")
             + "; last success " + sources.lastSuccess(source)
                                          .map(at -> Html.stamp(at, now))
                                          .or("never") + Html.flag(stale)
             + "</li>";
    }

    /// The non-git directories of one host under one root, by path. The strip shows every source, the configured hosts
    /// included, as the dashboard's does: a configured host never read is red here too.
    static String nonGit(HostName host,
                         String root,
                         List<NonGitView> rows,
                         List<HostName> configured,
                         Sources sources,
                         Instant now) {
        var stale = sources.rowsStale(host, now);

        return Html.page("non-git directories",
                         top(sources.all(configured), sources, now),
                         main(Html.panel("non-git",
                                         "non-git directories under " + Html.text(root) + " on " + host.value(),
                                         sources.stale(Sources.sourceOf(host), now),
                                         Html.asOf(sources.lastSuccess(Sources.sourceOf(host)),
                                                   now),
                                         nonGitList(rows.stream()
                                                        .filter(view -> view.root()
                                                                            .equals(root))
                                                        .sorted(Comparator.comparing((NonGitView view) -> view.path()
                                                                                                              .value()))
                                                        .toList(),
                                                    stale,
                                                    now))));
    }

    private static String nonGitList(List<NonGitView> rows, boolean stale, Instant now) {
        return rows.isEmpty()
               ? "<p>no non-git directory listed</p>"
               : Html.lines(Stream.of(Stream.of("<ul>"),
                                      rows.stream().map(view -> nonGitItem(view, stale, now)),
                                      Stream.of("</ul>"))
                                  .flatMap(lines -> lines)
                                  .toList());
    }

    private static String nonGitItem(NonGitView view, boolean stale, Instant now) {
        return "<li" + staleClass(stale)
             + ">" + Html.text(view.path().value())
             + " <small>(listed " + Html.age(view.listedAt(), now)
             + ")</small>" + Html.flag(stale)
             + "</li>";
    }

    /// The checkout table, sorted by idle time, longest first; `no rows` when empty.
    static String table(List<CheckoutView> rows, Sources sources, Instant now) {
        return rows.isEmpty()
               ? "<p>no rows</p>"
               : Html.lines(Stream.of(Stream.of("<table>", HEADER),
                                      rows.stream()
                                          .sorted(IDLE_FIRST)
                                          .map(view -> row(view,
                                                           sources.rowsStale(view.row().host(),
                                                                             now),
                                                           now)),
                                      Stream.of("</table>"))
                                  .flatMap(lines -> lines)
                                  .toList());
    }

    static String row(CheckoutView view, boolean stale, Instant now) {
        var row = view.row();

        return "<tr" + staleClass(stale)
             + ">" + cell(Html.text(row.host().value() + ":" + row.path().value())) + cell(statusCell(view, now)) + cell(Html.stamp(row.activityAt(),
                                                                                                                                    now)) + cell(head(row.facts())) + cell(preservation(view.derived())) + cell(linked(view.derived())) + cell(salvage(row.facts())) + cell(lastObserved(row,
                                                                                                                                                                                                                                                                                         stale,
                                                                                                                                                                                                                                                                                         now)) + cell(size(row,
                                                                                                                                                                                                                                                                                                           now))
             + "</tr>";
    }

    /// `ACTIVE` → `ACTIVE`, `IN_USE` → `IN-USE`: the spec's spelling.
    static String status(CheckoutStatus status) {
        return status.name()
                     .replace('_', '-');
    }

    /// The query of a drill-down page; `host` only when one is chosen.
    static List<Map.Entry<String, String>> drillQuery(CheckoutStatus status, Option<HostName> host, int page) {
        return Stream.of(some(Map.entry("status",
                                        status.name())),
                         host.map(name -> Map.entry("host",
                                                    name.value())),
                         some(Map.entry("page",
                                        Integer.toString(page))))
                     .flatMap(Option::stream)
                     .toList();
    }

    /// The page number and count, `(out of range)` past either end; the links go only to pages that exist.
    private static String pager(CheckoutStatus status, Option<HostName> host, Page<CheckoutView> page) {
        return "<p>page " + page.page()
             + " of " + page.pages() + range(page) + previous(status, host, page) + next(status, host, page)
             + " · <a href=\"/\">dashboard</a></p>";
    }

    private static String range(Page<CheckoutView> page) {
        return page.page() >= 1 && page.page() <= page.pages()
               ? ""
               : " (out of range)";
    }

    /// The page before, clamped to the last page: a page past the end links back to the last one.
    private static String previous(CheckoutStatus status, Option<HostName> host, Page<CheckoutView> page) {
        return some(Math.min(page.page() - 1,
                             page.pages())).filter(target -> target >= 1)
                   .map(target -> " · " + Html.link("/checkouts",
                                                    drillQuery(status, host, target),
                                                    "previous"))
                   .or("");
    }

    /// The page after, clamped to the first page: a page before the start links to page 1.
    private static String next(CheckoutStatus status, Option<HostName> host, Page<CheckoutView> page) {
        return some(Math.max(page.page() + 1,
                             1)).filter(target -> target <= page.pages())
                   .map(target -> " · " + Html.link("/checkouts",
                                                    drillQuery(status, host, target),
                                                    "next"))
                   .or("");
    }

    private static String staleClass(boolean stale) {
        return stale
               ? " class=\"stale\""
               : "";
    }

    private static String cell(String html) {
        return "<td>" + html + "</td>";
    }

    /// The status with the age of the derivation it came from; `UNSCANNED` has none.
    private static String statusCell(CheckoutView view, Instant now) {
        return status(view.status()) + view.derived()
                                           .filter(_ -> view.status() != CheckoutStatus.UNSCANNED)
                                           .map(derived -> " <small>(derived " + Html.age(derived.derivedAt(),
                                                                                          now)
                                                          + ")</small>")
                                           .or("");
    }

    private static String head(Option<CheckoutFacts> facts) {
        return facts.map(CheckoutPages::headOf)
                    .or("no facts");
    }

    private static String headOf(CheckoutFacts facts) {
        return facts.branch()
                    .map(branch -> Html.text(branch.value()))
                    .or("detached")
             + " " + facts.head()
                          .map(Sha::abbreviated)
                          .or("unborn");
    }

    private static String preservation(Option<DerivedRow> derived) {
        return derived.map(row -> preservationOf(row.derived().preservation()))
                      .or("not derived");
    }

    private static String preservationOf(Preservation preservation) {
        return switch (preservation) {
            case Preservation.Preserved _ -> "preserved";
            case Preservation.HostBareOnly _ -> "host bare only";
            case Preservation.Unpreserved unpreserved -> "unpreserved: " + Html.text(String.join(", ",
                                                                                                 unpreserved.reasons()));
        };
    }

    private static String linked(Option<DerivedRow> derived) {
        return derived.map(row -> linkOf(row.derived().link()))
                      .or("not derived");
    }

    private static String linkOf(PrLink link) {
        return link.prs()
                   .isEmpty()
               ? "none"
               : link.prs()
                     .stream()
                     .map(PrNumber::toString)
                     .collect(Collectors.joining(", "))
                + " (" + link.rule()
                + ")";
    }

    private static String salvage(Option<CheckoutFacts> facts) {
        return facts.flatMap(CheckoutFacts::salvageRefs)
                    .map(String::valueOf)
                    .or("unknown");
    }

    /// The pass and time of the row's last observation (spec §5.5); a row with no full facts shows when it was listed.
    private static String lastObserved(CheckoutRow row, boolean stale, Instant now) {
        return row.factsAt()
                  .map(at -> passName(row) + " " + Html.stamp(at, now))
                  .or(() -> "listed " + Html.stamp(row.listedAt(),
                                                   now) + ", no full facts") + Html.flag(stale);
    }

    private static String passName(CheckoutRow row) {
        return row.factsPass()
                  .map(kind -> kind.name()
                                   .toLowerCase(Locale.ROOT))
                  .or("pass");
    }

    private static String size(CheckoutRow row, Instant now) {
        return row.sizeKb()
                  .map(kb -> sized(kb,
                                   row.sizeAt(),
                                   now))
                  .or("unmeasured");
    }

    /// A size with its age (spec §5.5: "a row shows size with its age").
    private static String sized(long kb, Option<Instant> at, Instant now) {
        return Html.size(kb) + at.map(measured -> " <small>(measured " + Html.age(measured, now) + ")</small>")
                                 .or("");
    }

    record unused() implements CheckoutPages {}
}
