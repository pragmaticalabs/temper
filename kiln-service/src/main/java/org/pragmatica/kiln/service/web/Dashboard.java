package org.pragmatica.kiln.service.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.github.PullsPage;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.NonGitView;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassView;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.some;


/// The dashboard (spec §6): six panels, each with an `as of` line, each red when its source is older than 3 × its
/// period. Every fact shows its observation age, and a stale fact is marked, never shown as current.
///
/// Only one thing is ever green: a required check's cell whose value is exactly `success`, under a `REQUIRED` list,
/// observed within its bound. `success*`, `NONE-REQUIRED`, `UNKNOWN` and a stale cell are never green, and there is no
/// combined "green" anywhere in F1. No count reads 0 for want of a source: a host with no rows says so, and a count
/// line carries the oldest fact it rests on (spec §5.6).
public sealed interface Dashboard {
    /// The statuses the dashboard lists row by row; the others are drill-down pages (spec §6 panel 4).
    Set<CheckoutStatus> LISTED = EnumSet.of(CheckoutStatus.ACTIVE,
                                            CheckoutStatus.IN_USE,
                                            CheckoutStatus.NEEDS_SALVAGE,
                                            CheckoutStatus.UNSCANNED);

    List<CheckoutStatus> DRILL_DOWN = List.of(CheckoutStatus.REMOVABLE,
                                              CheckoutStatus.BASE_CLEAN,
                                              CheckoutStatus.STALE_UNOWNED,
                                              CheckoutStatus.UNOWNED);

    String SCHEDULE = "schedule";

    String PR_HEADER = "<tr><th>PR</th><th>head</th><th>behind</th><th>required checks</th><th>mergeable_state</th>"
                     + "<th>temper gate</th><th>checkouts</th><th>closing keywords</th></tr>";

    /// Last activity, newest first; then the higher number, so the order is total.
    Comparator<PrView> NEWEST_ACTIVITY = Comparator.comparing((PrView view) -> view.pr()
                                                                                   .value()
                                                                                   .updatedAt(),
                                                              Comparator.reverseOrder())
                                                   .thenComparing(view -> view.pr()
                                                                              .value()
                                                                              .number()
                                                                              .value(),
                                                                  Comparator.reverseOrder());

    /// Values a cell shows as failed (✗); `success` alone is ✓, and every other value (waiting, `UNKNOWN`, `missing`,
    /// `success*`) is … with the value shown beside it.
    Set<String> FAILED = Set.of("failure", "cancelled", "timed_out", "action_required", "startup_failure", "error");

    /// One page: the status strip and the tabs in the sticky header, the six panels below, of which the `tab` one shows
    /// ([#tab]: an allowlisted id, Open PRs otherwise).
    static String render(DashboardView view, Instant now, Option<String> tab) {
        return render(view, now, tab, false);
    }

    /// As [#render(DashboardView, Instant, Option)]; `allMerged` shows the Work tab's merged items however old
    /// ([WorkPage#render]).
    static String render(DashboardView view, Instant now, Option<String> tab, boolean allMerged) {
        var open = tab(tab);
        var sections = sections(view, now, allMerged);

        return Html.page("dashboard",
                         Html.lines(List.of(Html.strip(headerSources(view), view.sources(), now),
                                            tabs(view, now, open, sections))),
                         Html.lines(Stream.of(Stream.of("<main class=\"tabbed\" data-tab=\"" + open + "\">"),
                                              sections.values().stream(),
                                              Stream.of("</main>"))
                                          .flatMap(lines -> lines)
                                          .toList()));
    }

    /// Every section's markup by tab id, in page order.
    static Map<String, String> sections(DashboardView view, Instant now) {
        return sections(view, now, false);
    }

    static Map<String, String> sections(DashboardView view, Instant now, boolean allMerged) {
        var sections = new LinkedHashMap<String, String>();

        sections.put("sources", header(view, now));
        sections.put("prs", prs(view, now));
        sections.put("work",
                     WorkPage.render(view.work(), now, allMerged));
        sections.put("bases", bases(view, now));
        sections.put("checkouts", checkouts(view, now));
        sections.put("temper", temper(view, now));
        sections.put("pipeline", PipelinePanel.render(view, now));
        sections.put("conflicts", ConflictsPanel.render(view, now));
        sections.put("questions", questions(now));

        return sections;
    }

    // ---- tabs --------------------------------------------------------------------------------------------------------
    /// The tabs, in [Html#TABS] order, each with its count where one is natural and the `STALE` flag when anything in
    /// its section is marked red or stale ([Html#marked]): the worst mark inside a closed tab shows on the tab, and the
    /// active tab's amber ring never hides it.
    static String tabs(DashboardView view, Instant now, String open, Map<String, String> sections) {
        return "<nav class=\"tabs\" aria-label=\"panels\">" + String.join(" ",
                                                                          tab("prs",
                                                                              open,
                                                                              count(Integer.toString(view.prs().size()),
                                                                                    false),
                                                                              sections),
                                                                          tab("work",
                                                                              open,
                                                                              count(Long.toString(WorkPage.inFlight(view.work())),
                                                                                    false),
                                                                              sections),
                                                                          tab("bases", open, issues(view), sections),
                                                                          tab("checkouts",
                                                                              open,
                                                                              salvageCount(view),
                                                                              sections),
                                                                          tab("temper", open, "", sections),
                                                                          tab("pipeline",
                                                                              open,
                                                                              count(view.pipeline()
                                                                                        .measured()
                                                                                   + " / " + view.pipeline()
                                                                                                 .total(),
                                                                                    false),
                                                                              sections),
                                                                          tab("conflicts",
                                                                              open,
                                                                              count(Integer.toString(ConflictsPanel.count(view)),
                                                                                    ConflictsPanel.count(view) > 0),
                                                                              sections),
                                                                          tab("questions", open, "", sections),
                                                                          tab("sources",
                                                                              open,
                                                                              notOk(view, now),
                                                                              sections))
             + "</nav>";
    }

    /// The same tabs as links back to the dashboard, for the drill-down pages.
    static String tabLinks() {
        return "<nav class=\"tabs\" aria-label=\"panels\">" + Html.TABS.stream()
                                                                       .map(id -> "<a class=\"chip\" href=\"/?tab=" + id
                                                                                 + "\">" + label(id)
                                                                                 + "</a>")
                                                                       .collect(Collectors.joining(" "))
             + "</nav>";
    }

    static String label(String id) {
        return switch (id) {
            case "prs" -> "Open PRs";
            case "work" -> "Work";
            case "conflicts" -> "Conflicts";
            case "bases" -> "Base branches";
            case "checkouts" -> "Checkouts";
            case "temper" -> "temper";
            case "pipeline" -> "Pipeline";
            case "questions" -> "Questions";
            default -> "Sources";
        };
    }

    private static String tab(String id, String open, String countHtml, Map<String, String> sections) {
        var red = Html.marked(sections.getOrDefault(id, ""));

        return "<a class=\"chip" + (id.equals(open)
                                    ? " active"
                                    : "") + (red
                                             ? " red"
                                             : "")
             + "\" href=\"/?tab=" + id
             + "\">" + label(id) + countHtml + Html.flag(red)
             + "</a>";
    }

    /// The open tab: `raw` when it is one of [Html#TABS], else the first. Only an allowlisted id is ever written into the
    /// page, never the request's text.
    static String tab(Option<String> raw) {
        return raw.filter(Html.TABS::contains)
                  .or(Html.TABS.getFirst());
    }

    private static String count(String text, boolean warn) {
        return "<span class=\"count" + (warn
                                        ? " warn"
                                        : "") + "\">" + text + "</span>";
    }

    private static String issues(DashboardView view) {
        return view.runs()
                   .issues()
                   .isEmpty()
               ? ""
               : count(view.runs().issues().size() + " issues",
                       true);
    }

    /// `N salvage`: the NEEDS-SALVAGE checkouts of every host and the Mac; `≥N` when a host has no counts, `?` when none
    /// has, never a 0 that a missing host could make read low.
    private static String salvageCount(DashboardView view) {
        var hosts = Stream.concat(hosts(view).stream(), Stream.of(HostName.MAC)).toList();
        var known = view.counts()
                        .stream()
                        .mapToInt(counts -> counts.counts()
                                                  .getOrDefault(CheckoutStatus.NEEDS_SALVAGE, 0))
                        .sum();
        var missing = hosts.stream().anyMatch(host -> countsOf(view, host).isEmpty());

        return view.counts()
                   .isEmpty()
               ? count("? salvage", true)
               : count((missing
                        ? "≥"
                        : "") + known + " salvage",
                       known > 0 || missing);
    }

    /// `N not OK`: the sources whose badge is not `ok`.
    private static String notOk(DashboardView view, Instant now) {
        var failing = headerSources(view).stream()
                                   .filter(source -> !"ok".equals(Html.level(source,
                                                                             view.sources(),
                                                                             now)))
                                   .count();

        return failing == 0
               ? ""
               : count(failing + " not OK", true);
    }

    // ---- panel 1: sources --------------------------------------------------------------------------------------------
    static String header(DashboardView view, Instant now) {
        var sources = headerSources(view);

        return Html.panel("sources",
                          "Sources",
                          view.sources().anyStale(sources, now),
                          Html.asOf(view.sources().oldestSuccess(sources),
                                    now),
                          list(sources.stream().map(source -> sourceLine(view, source, now))));
    }

    /// GitHub, the mirror, temper, every host by name, and the Mac.
    private static List<Source> headerSources(DashboardView view) {
        return view.sources()
                   .all(hosts(view));
    }

    private static String sourceLine(DashboardView view, Source source, Instant now) {
        var stale = view.sources().stale(source, now);

        return "<li" + staleClass(stale)
             + "><b>" + Html.escape(source.key())
             + "</b>: " + view.sources()
                              .of(source)
                              .map(status -> Html.state(status.state(),
                                                        now))
                              .or("no state recorded")
             + "; last success " + view.sources()
                                       .lastSuccess(source)
                                       .map(at -> Html.stamp(at, now))
                                       .or("never") + Html.flag(stale) + detail(view, source, now)
             + "</li>";
    }

    /// What a source shows beside its state: GitHub its budget and skipped PRs, a host its passes, the Mac its push.
    private static String detail(DashboardView view, Source source, Instant now) {
        return switch (source) {
            case Source.GitHub _ -> budget(view, now) + skipped(view, now);
            case Source.Host host -> "; hot pass: " + pass(view, host.name(), PassKind.HOT, now) + "; full pass: " + pass(view,
                                                                                                                          host.name(),
                                                                                                                          PassKind.FULL,
                                                                                                                          now);
            case Source.Mac _ -> "; push: " + pass(view, HostName.MAC, PassKind.PUSH, now);
            case Source.Mirror _, Source.Temper _, Source.Hosts _ -> "";
        };
    }

    private static String budget(DashboardView view, Instant now) {
        return "; " + view.budget()
                          .map(budget -> Html.observed("rate budget " + budget.value()
                                                                              .remaining()
                                                      + " left, resets " + Html.deadline(budget.value().reset(),
                                                                                         now),
                                                       budget.observedAt(),
                                                       now,
                                                       period(view, Source.GITHUB)))
                          .or("rate budget not read");
    }

    private static String skipped(DashboardView view, Instant now) {
        return view.skipped()
                   .isEmpty()
               ? ""
               : "\n" + list(view.skipped().stream().map(skipped -> skippedLine(view, skipped, now)));
    }

    private static String skippedLine(DashboardView view, Observed<PullsPage.Skipped> skipped, Instant now) {
        return "<li>" + Html.observed("skipped " + skipped.value().number() + ": " + Html.text(skipped.value().reason()),
                                      skipped.observedAt(),
                                      now,
                                      period(view, Source.GITHUB))
             + "</li>";
    }

    private static String pass(DashboardView view, HostName host, PassKind kind, Instant now) {
        return lastPass(view, host, kind).map(pass -> passText(pass, now))
                       .or("none recorded");
    }

    private static Option<PassView> lastPass(DashboardView view, HostName host, PassKind kind) {
        return Option.from(view.passes()
                               .stream()
                               .filter(pass -> pass.host()
                                                   .equals(host) && pass.kind() == kind)
                               .findFirst());
    }

    /// A running pass shows its progress `k of N` (spec §5.5); a finished one its outcome, rows and end.
    /// A failed pass (`UNREACHABLE`, `INCOMPLETE`) shows no row count: a failure never makes a count read 0, and an
    /// incomplete listing's count is not the host's. A full pass records each chunk as it lands (WP8), so its row holds
    /// the last chunk's outcome and rows, and shows `chunk k of N` beside them (WP9).
    private static String passText(PassView pass, Instant now) {
        return switch (pass.outcome()) {
            case RUNNING -> "RUNNING" + progress(pass) + ", started " + Html.stamp(pass.startedAt(), now);
            case UNREACHABLE, INCOMPLETE -> pass.outcome() + chunk(pass) + ", " + finished(pass, now);
            case COMPLETE -> "COMPLETE" + chunk(pass) + ", " + pass.rows() + expected(pass) + " rows, " + finished(pass,
                                                                                                                   now);
        };
    }

    private static String chunk(PassView pass) {
        return Option.all(pass.progressK(),
                          pass.progressN())
                     .map((done, total) -> " (chunk " + done + " of " + total + ")")
                     .or("");
    }

    /// ` of M`: the listing's own trailer count, when one was read (WP9).
    private static String expected(PassView pass) {
        return pass.expected()
                   .map(count -> " of " + count)
                   .or("");
    }

    private static String finished(PassView pass, Instant now) {
        return "finished " + pass.finishedAt()
                                 .map(at -> Html.stamp(at, now))
                                 .or("never");
    }

    private static String progress(PassView pass) {
        return Option.all(pass.progressK(),
                          pass.progressN())
                     .map((done, total) -> " " + done + " of " + total)
                     .or("");
    }

    // ---- panel 2: open PRs -------------------------------------------------------------------------------------------
    static String prs(DashboardView view, Instant now) {
        return Html.panel("prs",
                          "Open PRs",
                          view.sources().stale(Source.GITHUB, now),
                          Html.asOf(view.sources().lastSuccess(Source.GITHUB),
                                    now),
                          view.prs()
                              .isEmpty()
                          ? "<p>no open PR observed</p>"
                          : table(PR_HEADER,
                                  view.prs().stream().sorted(NEWEST_ACTIVITY).map(pr -> prRow(view, pr, now))));
    }

    private static String prRow(DashboardView view, PrView pr, Instant now) {
        var github = period(view, Source.GITHUB);

        return "<tr>" + cell(title(view, pr.pr(), now)) + cell(head(pr, now)) + cell(behind(view, pr, now)) + checksCell(pr,
                                                                                                                         now,
                                                                                                                         github) + cell(mergeable(pr.detail(),
                                                                                                                                                  now,
                                                                                                                                                  github)) + cell(gate(view,
                                                                                                                                                                       pr,
                                                                                                                                                                       now)) + cell(linked(view,
                                                                                                                                                                                           pr,
                                                                                                                                                                                           now)) + cell(closing(pr.pr()
                                                                                                                                                                                                                  .value()))
             + "</tr>";
    }

    /// The check area, red while the required list is stale or was never read: no cell under it can be current.
    private static String checksCell(PrView pr, Instant now, Duration period) {
        return "<td" + (listStale(pr, now, period)
                        ? " class=\"red\""
                        : "") + ">" + checks(pr, now, period) + "</td>";
    }

    private static boolean listStale(PrView pr, Instant now, Duration period) {
        return pr.required()
                 .map(required -> Html.stale(required.observedAt(),
                                             now,
                                             period))
                 .or(true);
    }

    private static String title(DashboardView view, Observed<PullRequest> observed, Instant now) {
        var pr = observed.value();

        return Html.observed(pr.number()
                            + " " + Html.text(pr.title()) + draft(pr)
                            + "<br>base " + Html.text(pr.base().value()) + stacked(view, pr.base()),
                             observed.observedAt(),
                             now,
                             period(view, Source.GITHUB));
    }

    private static String draft(PullRequest pr) {
        return pr.draft()
               ? " <span class=\"grey\">[draft]</span>"
               : "";
    }

    /// A base that is not a configured one is stacked: CI may not run on it.
    private static String stacked(DashboardView view, BranchName base) {
        return view.bases()
                   .contains(base)
               ? ""
               : " <span class=\"grey\">(stacked)</span>";
    }

    /// The head's 7 characters and its commit's age, when GitHub gave the commit date.
    private static String head(PrView pr, Instant now) {
        return pr.pr()
                 .value()
                 .head()
                 .abbreviated()
             + ", " + pr.detail()
                        .flatMap(detail -> detail.value()
                                                 .headCommittedAt())
                        .map(at -> "committed " + Html.stamp(at, now))
                        .or("commit time unknown");
    }

    private static String behind(DashboardView view, PrView pr, Instant now) {
        return pr.behind()
                 .map(behind -> Html.observed(behindText(behind.value()),
                                              behind.observedAt(),
                                              now,
                                              period(view, Source.MIRROR)))
                 .or("behind not read");
    }

    /// `behindBy` and `behindTreeEmpty`; an absent value reads `UNKNOWN`, never 0 (K-8).
    private static String behindText(BehindFacts facts) {
        return "behind " + facts.behindBy()
                                .map(String::valueOf)
                                .or("UNKNOWN")
             + ", " + facts.behindTreeEmpty()
                           .map(Dashboard::tree)
                           .or("tree UNKNOWN");
    }

    /// `behindTreeEmpty`: the tree at the merge base equals the base tip's tree (maintainer ruling).
    private static String tree(boolean equal) {
        return equal
               ? "tree-equal"
               : "tree differs";
    }

    /// The required-check state and one cell per check. A base whose list was never read is `UNKNOWN(not read)`.
    private static String checks(PrView pr, Instant now, Duration period) {
        return pr.required()
                 .map(required -> checksOf(required,
                                           pr.cells(),
                                           now,
                                           period))
                 .or(() -> grey("UNKNOWN(not read)") + cells(pr.cells(),
                                                             false,
                                                             now,
                                                             period));
    }

    private static String checksOf(Observed<RequiredChecks> required,
                                   List<Observed<Cell>> cells,
                                   Instant now,
                                   Duration period) {
        return switch (required.value()) {
            case RequiredChecks.Required list -> Html.observed("REQUIRED(" + list.checks().size() + ", " + Html.text(list.enforcement()) + ")",
                                                               required.observedAt(),
                                                               now,
                                                               period) + requiredCells(list,
                                                                                       cells,
                                                                                       !Html.stale(required.observedAt(),
                                                                                                   now,
                                                                                                   period),
                                                                                       now,
                                                                                       period);
            case RequiredChecks.NoneRequired none -> Html.observed(grey("NONE-REQUIRED(" + Html.text(none.why()) + ")") + stackedBase(none),
                                                                   required.observedAt(),
                                                                   now,
                                                                   period) + cells(cells, false, now, period);
            case RequiredChecks.Unknown unknown -> Html.observed(grey("UNKNOWN(" + Html.text(unknown.status()) + ")"),
                                                                 required.observedAt(),
                                                                 now,
                                                                 period) + cells(cells, false, now, period);
        };
    }

    private static String stackedBase(RequiredChecks.NoneRequired none) {
        return none.stacked()
               ? " stacked base: CI may not run"
               : "";
    }

    /// One cell per required check, in the list's order, `no cell` where none was read; then any cell the list no
    /// longer requires, never green. `listFresh`: the list itself is within its bound; a stale list greens nothing, since
    /// the checks it names may no longer be the required ones.
    private static String requiredCells(RequiredChecks.Required list,
                                        List<Observed<Cell>> cells,
                                        boolean listFresh,
                                        Instant now,
                                        Duration period) {
        return Stream.concat(list.checks().stream().map(check -> requiredCell(check, cells, listFresh, now, period)),
                             cells.stream()
                                  .filter(cell -> !list.checks()
                                                       .contains(cell.value().check()))
                                  .map(cell -> cellHtml(cell, false, now, period) + " (not required)"))
                     .collect(Collectors.collectingAndThen(Collectors.toList(),
                                                           Dashboard::cellRow));
    }

    private static String requiredCell(RequiredCheck check,
                                       List<Observed<Cell>> cells,
                                       boolean listFresh,
                                       Instant now,
                                       Duration period) {
        return Option.from(cells.stream().filter(cell -> cell.value()
                                                             .check()
                                                             .equals(check)).findFirst())
                     .map(cell -> cellHtml(cell, listFresh, now, period))
                     .or(() -> "<span class=\"cell\" title=\"" + Html.escape(check.label())
                              + ": no cell\">… " + Html.text(check.label())
                              + " no cell</span>");
    }

    private static String cells(List<Observed<Cell>> cells, boolean greenable, Instant now, Duration period) {
        return cellRow(cells.stream().map(cell -> cellHtml(cell, greenable, now, period)).toList());
    }

    /// The cells of one PR on one wrapping line under the list's state; nothing when there are none.
    private static String cellRow(List<String> cells) {
        return cells.isEmpty()
               ? ""
               : "<div class=\"cells\">" + String.join(" ", cells) + "</div>";
    }

    /// A cell, compact: a glyph ([#glyph]) and its label, the value beside it unless it is green, the foreign runs and
    /// the `STALE` flag; the full detail (value, foreign runs, shown values, age) in its title. Green only when `greenable` (a fresh `REQUIRED` list), exactly `success` ([Cell#isSuccess]) and fresh, never
    /// in the future ([Html#stale]). Labelled by [RequiredCheck#label()], never by the key, which holds U+001F.
    private static String cellHtml(Observed<Cell> observed, boolean greenable, Instant now, Duration period) {
        var cell = observed.value();
        var stale = Html.stale(observed.observedAt(), now, period);
        var green = greenable && cell.isSuccess() && !stale;

        return "<span class=\"cell" + (green
                                       ? " ok"
                                       : "") + (FAILED.contains(cell.value())
                                                ? " fail"
                                                : "") + (stale
                                                         ? " stale"
                                                         : "")
             + "\" title=\"" + Html.escape(detail(observed, now))
             + "\">" + glyph(cell, green)
             + " " + Html.text(cell.check().label()) + value(cell, green) + foreign(cell) + Html.flag(stale)
             + "</span>";
    }

    /// The glyph follows the level shown, never the value alone: ✓ only for a green cell (a fresh success under a fresh
    /// `REQUIRED` list); ✗ for a failure; ? for a success that is not green (under `UNKNOWN`, a list not read, a check
    /// not required, or stale); … for anything else.
    private static String glyph(Cell cell, boolean green) {
        if (green) {
            return "✓";
        }

        if (FAILED.contains(cell.value())) {
            return "✗";
        }

        return cell.isSuccess()
               ? "?"
               : "…";
    }

    /// The value beside the label, unless the cell is green (the ✓ says it).
    private static String value(Cell cell, boolean green) {
        return green
               ? ""
               : " " + Html.text(cell.value());
    }

    private static String foreign(Cell cell) {
        return cell.foreign() > 0
               ? " (foreign " + cell.foreign() + ")"
               : "";
    }

    /// The cell's whole fact as plain text for its title: label, value, foreign runs, shown values, observation age.
    private static String detail(Observed<Cell> observed, Instant now) {
        var cell = observed.value();

        return cell.check()
                   .label()
             + ": " + cell.value() + foreign(cell) + (cell.shown()
                                                          .isEmpty()
                                                      ? ""
                                                      : " · shown: " + String.join(", ", cell.shown()))
             + " · observed " + Html.age(observed.observedAt(), now);
    }

    /// `mergeable_state`: shown, never a gate.
    private static String mergeable(Option<Observed<OpenDetail>> detail, Instant now, Duration period) {
        return detail.map(observed -> Html.observed(mergeableText(observed.value()),
                                                    observed.observedAt(),
                                                    now,
                                                    period))
                     .or("not read");
    }

    private static String mergeableText(OpenDetail detail) {
        return Html.text(detail.mergeableState().or("unknown"))
             + ", mergeable " + detail.mergeable()
                                      .map(String::valueOf)
                                      .or("unknown");
    }

    /// The temper gate answer, or `TEMPER-SCHEMA(field)`; while temper is down or answers without a field, its state
    /// (`TEMPER-DOWN`, `TEMPER-SCHEMA(field)`) and then the last answer read, never as if current.
    private static String gate(DashboardView view, PrView pr, Instant now) {
        return temperFailure(view) + pr.gate()
                                       .map(gate -> Html.observed(gateText(gate.value()),
                                                                  gate.observedAt(),
                                                                  now,
                                                                  period(view, Source.TEMPER)))
                                       .or("none read");
    }

    private static String temperFailure(DashboardView view) {
        return view.sources()
                   .of(Source.TEMPER)
                   .map(status -> failurePrefix(status.state()))
                   .or("");
    }

    private static String failurePrefix(SourceState state) {
        return switch (state) {
            case SourceState.TemperDown _ -> "TEMPER-DOWN; last: ";
            case SourceState.TemperSchema schema -> "TEMPER-SCHEMA(" + Html.text(schema.field()) + "); last: ";
            default -> "";
        };
    }

    private static String gateText(GateRead gate) {
        return switch (gate) {
            case GateRead.Answered answered -> Html.text(answered.answer()) + answered.job().map(job -> ", job " + job).or("") + answered.judge().map(judge -> ", judge " + Html.text(judge)).or("");
            case GateRead.SchemaBroken broken -> "TEMPER-SCHEMA(" + Html.text(broken.field()) + ")";
        };
    }

    private static String linked(DashboardView view, PrView pr, Instant now) {
        return pr.checkouts()
                 .isEmpty()
               ? "none linked"
               : pr.checkouts()
                   .stream()
                   .map(linked -> linkedCheckout(view, linked, now))
                   .collect(Collectors.joining("<br>"));
    }

    private static String linkedCheckout(DashboardView view, Observed<PrView.LinkedCheckout> observed, Instant now) {
        var linked = observed.value();

        return Html.observed(Html.text(linked.host().value() + ":" + linked.path().value())
                            + " (" + CheckoutPages.status(linked.status())
                            + ")",
                             observed.observedAt(),
                             now,
                             period(view,
                                    Sources.sourceOf(linked.host())));
    }

    private static String closing(PullRequest pr) {
        var parts = Stream.of(numbers("closes ",
                                      pr.closes()),
                              numbers("part of ",
                                      pr.partOf()))
                          .flatMap(Option::stream)
                          .toList();

        return parts.isEmpty()
               ? "none"
               : String.join("; ", parts);
    }

    private static Option<String> numbers(String prefix, List<PrNumber> numbers) {
        return some(numbers).filter(list -> !list.isEmpty())
                   .map(list -> prefix + joined(list));
    }

    private static String joined(List<PrNumber> numbers) {
        return numbers.stream()
                      .map(PrNumber::toString)
                      .collect(Collectors.joining(", "));
    }

    // ---- panel 3: base branches --------------------------------------------------------------------------------------
    static String bases(DashboardView view, Instant now) {
        return Html.panel("bases",
                          "Base branches",
                          view.sources().stale(Source.GITHUB, now),
                          Html.asOf(view.sources().lastSuccess(Source.GITHUB),
                                    now),
                          Html.lines(Stream.concat(scopes(view).stream().map(scope -> scopeRuns(view, scope, now)),
                                                   Stream.of(issues(view, now)))
                                           .toList()));
    }

    /// The configured bases in order, then the scheduled runs, then any other scope the store holds.
    private static List<String> scopes(DashboardView view) {
        return Stream.of(view.bases().stream().map(BranchName::value),
                         Stream.of(SCHEDULE),
                         view.runs().runs().stream().map(BaseView.Run::scope).sorted())
                     .flatMap(scopes -> scopes)
                     .distinct()
                     .toList();
    }

    private static String scopeRuns(DashboardView view, String scope, Instant now) {
        var runs = view.runs().runs().stream().filter(run -> run.scope()
                                                                .equals(scope)).toList();

        return "<h3>" + scopeTitle(scope)
             + "</h3>\n" + (runs.isEmpty()
                            ? "<p>no runs observed</p>"
                            : list(runs.stream().map(run -> "<li>" + runLine(view, run, now) + "</li>")));
    }

    private static String scopeTitle(String scope) {
        return SCHEDULE.equals(scope)
               ? "scheduled runs"
               : "runs on " + Html.text(scope);
    }

    private static String runLine(DashboardView view, BaseView.Run run, Instant now) {
        return Html.observed(Html.external(run.htmlUrl(),
                                           Html.text(run.name()))
                            + ": " + Html.text(run.status()) + run.conclusion()
                                                                  .map(conclusion -> " / " + Html.text(conclusion))
                                                                  .or("")
                            + ", head " + Html.text(Html.abbreviated(run.headSha()))
                            + ", created " + Html.stamp(run.createdAt(), now),
                             run.observedAt(),
                             now,
                             period(view, Source.GITHUB));
    }

    private static String issues(DashboardView view, Instant now) {
        return "<h3>open github-actions issues</h3>\n" + (view.runs()
                                                              .issues()
                                                              .isEmpty()
                                                          ? "<p>none observed</p>"
                                                          : list(view.runs()
                                                                     .issues()
                                                                     .stream()
                                                                     .map(issue -> "<li>" + issueLine(view, issue, now)
                                                                                  + "</li>")));
    }

    private static String issueLine(DashboardView view, BaseView.Issue issue, Instant now) {
        return Html.observed(Html.external(issue.htmlUrl(), "#" + issue.number())
                            + " " + Html.text(issue.title())
                            + ", created " + Html.stamp(issue.createdAt(), now),
                             issue.observedAt(),
                             now,
                             period(view, Source.GITHUB));
    }

    // ---- panel 4: checkouts ------------------------------------------------------------------------------------------
    static String checkouts(DashboardView view, Instant now) {
        var hosts = hosts(view);
        var sources = checkoutSources(view);

        return Html.panel("checkouts",
                          "Checkouts",
                          checkoutsRed(view, now),
                          Html.asOf(view.sources().oldestSuccess(sources),
                                    now),
                          Html.lines(Stream.concat(hosts.stream().map(host -> hostSection(view, host, now)),
                                                   Stream.of(macSection(view, now)))
                                           .toList()));
    }

    private static List<Source> checkoutSources(DashboardView view) {
        return Sources.sourcesOf(Stream.concat(hosts(view).stream(), Stream.of(HostName.MAC)).toList());
    }

    /// The checkouts panel is red when any host's or the Mac's source is stale.
    private static boolean checkoutsRed(DashboardView view, Instant now) {
        return view.sources()
                   .anyStale(checkoutSources(view),
                             now);
    }

    /// The configured hosts, and every other host that still has a state, a pass, counts or rows; never the Mac.
    static List<HostName> hosts(DashboardView view) {
        return Stream.of(view.hosts().stream(),
                         view.sources().statuses().stream().map(SourceStatus::source).flatMap(Dashboard::hostOf),
                         view.passes().stream().map(PassView::host),
                         view.counts().stream().map(HostCounts::host),
                         view.listed().stream().map(listed -> listed.row()
                                                                    .host()))
                     .flatMap(hosts -> hosts)
                     .filter(host -> !HostName.MAC.equals(host))
                     .distinct()
                     .sorted(Comparator.comparing(HostName::value))
                     .toList();
    }

    private static Stream<HostName> hostOf(Source source) {
        return source instanceof Source.Host host
               ? Stream.of(host.name())
               : Stream.empty();
    }

    private static String hostSection(DashboardView view, HostName host, Instant now) {
        var stale = view.sources().stale(Sources.sourceOf(host), now);

        return Html.lines(List.of("<div class=\"host" + (stale
                                                         ? " red"
                                                         : "") + "\">",
                                  "<h3>" + host.value() + Html.flag(stale) + "</h3>",
                                  Html.asOf(view.sources().lastSuccess(Sources.sourceOf(host)),
                                            now),
                                  countLine(view, host, now),
                                  drillLine(view, host, now),
                                  nonGitLines(view, host),
                                  CheckoutPages.table(view.listed()
                                                          .stream()
                                                          .filter(listed -> listed.row()
                                                                                  .host()
                                                                                  .equals(host))
                                                          .filter(listed -> LISTED.contains(listed.status()))
                                                          .toList(),
                                                      view.sources(),
                                                      now),
                                  "</div>"));
    }

    /// The Mac, always in full: every row whatever its status, when it last pushed, and the salvage refs it alone holds.
    private static String macSection(DashboardView view, Instant now) {
        var stale = view.sources().stale(Source.MAC, now);

        return Html.lines(List.of("<div class=\"host mac" + (stale
                                                             ? " red"
                                                             : "") + "\">",
                                  "<h3>mac" + Html.flag(stale) + "</h3>",
                                  Html.asOf(view.sources().lastSuccess(Source.MAC),
                                            now),
                                  "<p>pushed " + lastPass(view, HostName.MAC, PassKind.PUSH).flatMap(PassView::finishedAt)
                                                         .map(at -> Html.stamp(at, now))
                                                         .or("never")
                                 + "</p>",
                                  salvage(view.mac()),
                                  countLine(view, HostName.MAC, now),
                                  nonGitLines(view, HostName.MAC),
                                  CheckoutPages.table(view.mac(), view.sources(), now),
                                  "</div>"));
    }

    /// Salvage refs live in a repository's common dir, which its worktrees share: each common dir is counted once.
    private static String salvage(List<CheckoutView> mac) {
        Map<String, Option<Integer>> byRepository = mac.stream()
                                                       .collect(Collectors.toMap(view -> view.row()
                                                                                             .commonDir(),
                                                                                 Dashboard::salvageOf,
                                                                                 Dashboard::either,
                                                                                 TreeMap::new));

        return byRepository.isEmpty()
               ? "<p>salvage refs: not reported</p>"
               : salvageLine(byRepository);
    }

    /// The exact total when every repository reported its count; otherwise a lower bound with the repositories that
    /// did not, never a plain count that a missing fact could make read low.
    private static String salvageLine(Map<String, Option<Integer>> byRepository) {
        var total = byRepository.values().stream().flatMap(Option::stream).mapToInt(Integer::intValue).sum();
        var unreported = byRepository.values().stream().filter(Option::isEmpty).count();

        return unreported == 0
               ? "<p>" + total
                + " copies held only on the Mac (salvage refs in " + holding(byRepository)
                + " repositories)</p>"
               : "<p>≥" + total + " copies held only on the Mac (" + unreported + " repositories not reported)</p>";
    }

    private static long holding(Map<String, Option<Integer>> byRepository) {
        return byRepository.values()
                           .stream()
                           .flatMap(Option::stream)
                           .filter(count -> count > 0)
                           .count();
    }

    private static Option<Integer> salvageOf(CheckoutView view) {
        return view.row()
                   .facts()
                   .flatMap(CheckoutFacts::salvageRefs);
    }

    /// One repository's count from any worktree that reported it; the worktrees share it, so the larger is kept.
    private static Option<Integer> either(Option<Integer> one, Option<Integer> other) {
        return Option.all(one, other)
                     .map(Integer::max)
                     .orElse(() -> one)
                     .orElse(() -> other);
    }

    private static Option<HostCounts> countsOf(DashboardView view, HostName host) {
        return Option.from(view.counts().stream().filter(counts -> counts.host()
                                                                         .equals(host)).findFirst());
    }

    /// One line of counts per status with the known total size and the oldest fact the counts rest on. A host with no
    /// rows says so: a source failure never makes a count read 0.
    private static String countLine(DashboardView view, HostName host, Instant now) {
        return countsOf(view, host).map(counts -> countText(counts,
                                                            view.sources().rowsStale(host, now),
                                                            now))
                       .or("<p class=\"counts\">no rows observed</p>");
    }

    private static String countText(HostCounts counts, boolean stale, Instant now) {
        return "<p class=\"counts" + (stale
                                      ? " stale"
                                      : "")
             + "\">" + Arrays.stream(CheckoutStatus.values())
                             .map(status -> CheckoutPages.status(status) + " " + counts.counts()
                                                                                       .getOrDefault(status, 0))
                             .collect(Collectors.joining(" · "))
             + " · total " + total(counts) + knownSize(counts)
             + " · oldest fact " + counts.oldestFactsAt()
                                         .map(at -> Html.stamp(at, now))
                                         .or("none (no full facts yet)") + Html.flag(stale)
             + "</p>";
    }

    /// The known total size: `size not measured` when no row is measured (the Mac's never are, spec §5.4), a lower bound
    /// `≥X (k of n measured)` while some are not, never a 0 standing for unknown; exact only when every row is measured,
    /// and `size inconsistent` when the store counts more measured rows than rows.
    private static String knownSize(HostCounts counts) {
        if (counts.measuredRows() == 0) {
            return " · size not measured";
        }

        if (counts.measuredRows() > total(counts)) {
            return " · size inconsistent (" + counts.measuredRows() + " measured of " + total(counts) + " rows)";
        }

        return counts.measuredRows() < total(counts)
               ? " · known size ≥" + Html.size(counts.knownSizeKb())
                + " (" + counts.measuredRows()
                + " of " + total(counts)
                + " measured)"
               : " · known size " + Html.size(counts.knownSizeKb());
    }

    private static int total(HostCounts counts) {
        return counts.counts()
                     .values()
                     .stream()
                     .mapToInt(Integer::intValue)
                     .sum();
    }

    /// The drill-down statuses with their counts; `?` when the host has no counts, never 0.
    private static String drillLine(DashboardView view, HostName host, Instant now) {
        var stale = view.sources().rowsStale(host, now);

        return "<p" + staleClass(stale)
             + ">drill down: " + DRILL_DOWN.stream()
                                           .map(status -> Html.link("/checkouts",
                                                                    CheckoutPages.drillQuery(status,
                                                                                             some(host),
                                                                                             1),
                                                                    CheckoutPages.status(status)
                                                                   + " " + drillCount(view, host, status)))
                                           .collect(Collectors.joining(" · ")) + Html.flag(stale)
             + "</p>";
    }

    private static String drillCount(DashboardView view, HostName host, CheckoutStatus status) {
        return countsOf(view, host).map(counts -> String.valueOf(counts.counts().getOrDefault(status, 0)))
                       .or("?");
    }

    /// `N non-git directories under <root>`, per root, each with its drill-down link.
    private static String nonGitLines(DashboardView view, HostName host) {
        Map<String, Long> byRoot = view.nonGit()
                                       .getOrDefault(host,
                                                     List.of())
                                       .stream()
                                       .collect(Collectors.groupingBy(NonGitView::root,
                                                                      TreeMap::new,
                                                                      Collectors.counting()));

        return byRoot.isEmpty()
               ? "<p>no non-git directory listed</p>"
               : nonGitRoots(host, byRoot);
    }

    private static String nonGitRoots(HostName host, Map<String, Long> byRoot) {
        return Html.lines(byRoot.entrySet().stream().map(entry -> nonGitLine(host, entry)).toList());
    }

    private static String nonGitLine(HostName host, Map.Entry<String, Long> root) {
        return "<p>" + root.getValue()
             + " non-git directories under " + Html.text(root.getKey())
             + " " + Html.link("/non-git",
                               List.of(Map.entry("host", host.value()),
                                       Map.entry("root", root.getKey())),
                               "list")
             + "</p>";
    }

    // ---- panel 5: temper ---------------------------------------------------------------------------------------------
    /// temper's digest text, verbatim and escaped, in `<pre>`, then the Hosts section ([HostsPanel]) [guess: the contract
    /// names a `Hosts` panel but no tab, so it is a section of this tab].
    static String temper(DashboardView view, Instant now) {
        return Html.panel("temper",
                          "temper",
                          view.sources().stale(Source.TEMPER, now),
                          Html.asOf(view.digest().map(Observed::observedAt),
                                    now),
                          view.digest()
                              .map(digest -> digest(digest,
                                                    now,
                                                    period(view, Source.TEMPER)))
                              .or("<p>no digest read yet</p>")
                         + "\n" + HostsPanel.render(view, now));
    }

    private static String digest(Observed<String> digest, Instant now, Duration period) {
        return (Html.stale(digest.observedAt(), now, period)
                ? "<p class=\"stale\">digest observed " + Html.age(digest.observedAt(), now) + Html.flag(true)
                 + "</p>\n"
                : "")
             + "<pre>" + Html.verbatim(digest.value())
             + "</pre>";
    }

    // ---- panel 6: questions ------------------------------------------------------------------------------------------
    /// The F3 placeholder: no source in F1.
    static String questions(Instant now) {
        return Html.panel("questions",
                          "Questions",
                          false,
                          "<p class=\"asof\">as of " + Html.stamp(now, now) + ": no source in F1</p>",
                          "<p class=\"grey\">Open questions arrive in F3.</p>");
    }

    // ---- shared ------------------------------------------------------------------------------------------------------
    private static Duration period(DashboardView view, Source source) {
        return view.sources()
                   .periods()
                   .of(source);
    }

    private static String list(Stream<String> items) {
        return Html.lines(Stream.of(Stream.of("<ul>"), items, Stream.of("</ul>")).flatMap(lines -> lines).toList());
    }

    private static String table(String header, Stream<String> rows) {
        return Html.lines(Stream.of(Stream.of("<table>", header),
                                    rows,
                                    Stream.of("</table>"))
                                .flatMap(lines -> lines)
                                .toList());
    }

    private static String cell(String html) {
        return "<td>" + html + "</td>";
    }

    private static String grey(String html) {
        return "<span class=\"grey\">" + html + "</span>";
    }

    private static String staleClass(boolean stale) {
        return stale
               ? " class=\"stale\""
               : "";
    }

    record unused() implements Dashboard {}
}
