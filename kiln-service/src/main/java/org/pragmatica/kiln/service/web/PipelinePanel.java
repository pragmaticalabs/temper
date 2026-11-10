package org.pragmatica.kiln.service.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.pipeline.PrMeasure;
import org.pragmatica.kiln.core.pipeline.Removal;
import org.pragmatica.kiln.core.pipeline.WeekRow;
import org.pragmatica.kiln.service.pipeline.PipelineReader;
import org.pragmatica.kiln.service.pipeline.PipelineStatus;
import org.pragmatica.kiln.service.pipeline.PipelineView;


/// The Pipeline tab (#102): the pipeline baseline's state with "N of M PRs measured", one row per ISO week (UTC, by
/// merge time) of machine-hours split three ways and by job family, queue removals by kind and sync merges, and per week
/// a drill-down list of its PRs. A state that is not healthy shows its reason and marks the tab red; job names no family
/// rule knew are listed.
final class PipelinePanel {
    static final String WEEK_HEADER = "<tr><th>week</th><th>PRs measured</th><th>machine-h</th><th>PR runs</th>"
                                    + "<th>queue, 1st entry</th><th>after removal</th><th>full reactor</th><th>ember</th>"
                                    + "<th>forge</th><th>consensus</th><th>other</th><th>removals (flake / real / conflict / manual / ?)</th>"
                                    + "<th>base syncs</th><th>other merges</th><th>base-sync h</th></tr>";

    static final String PR_HEADER = "<tr><th>PR</th><th>merged</th><th>machine-h</th><th>PR runs</th><th>queue, 1st entry</th>"
                                  + "<th>after removal</th><th>full reactor</th><th>ember</th><th>forge</th><th>consensus</th>"
                                  + "<th>other</th><th>removals</th><th>base syncs</th><th>other merges</th><th>base-sync h</th>"
                                  + "<th>runs / attempts</th></tr>";

    private PipelinePanel() {}

    static String render(DashboardView view, Instant now) {
        var pipeline = view.pipeline();
        var red = red(pipeline.status());

        return Html.panel("pipeline",
                          "Pipeline baseline",
                          red,
                          Html.asOf(pipeline.status().lastOk(),
                                    now),
                          Html.lines(Stream.of(Stream.of(scope(pipeline),
                                                         state(pipeline, now)),
                                               unknown(pipeline),
                                               weeks(pipeline))
                                           .flatMap(lines -> lines)
                                           .toList()));
    }

    /// Only a failed read and a refused credential are red; filling, a budget pause and "no cycle yet" are not.
    static boolean red(PipelineStatus status) {
        return status.kind() == PipelineStatus.Kind.FAILED || status.kind() == PipelineStatus.Kind.NEEDS_APP;
    }

    private static String scope(PipelineView pipeline) {
        var scope = pipeline.scope();

        return "<p>" + Html.text(scope.repo())
             + " PRs merged into <code>" + Html.text(scope.base())
             + "</code> since " + scope.since()
             + "; " + pipeline.measured()
             + " of " + pipeline.total()
             + " PRs measured" + (pipeline.excluded() > 0
                                  ? " (" + pipeline.excluded()
                                   + " INCOMPLETE and left out of the sums: only complete measurements are summed; kind and reason in the row)"
                                  : "")
             + ". JSON: "
             + "<a href=\"/api/v1/pipeline/baseline\">/api/v1/pipeline/baseline</a>. A base sync is a merge commit whose "
             + "headline names the base (headline rule); base-sync h are the PR runs on such merges. INCOMPLETE: runs that "
             + "never completed within " + PipelineReader.GIVE_UP.toDays()
             + " days of the merge.</p>";
    }

    /// The state and its reason; `since` is shown once a cycle has run.
    private static String state(PipelineView pipeline, Instant now) {
        var status = pipeline.status();

        return "<p" + (red(status)
                       ? " class=\"stale\""
                       : "")
             + ">state " + status.kind()
                                 .name() + (status.kind() == PipelineStatus.Kind.NEVER
                                            ? ""
                                            : " since " + Html.stamp(status.since(), now))
             + ": " + Html.text(status.reason())
             + "</p>";
    }

    private static Stream<String> unknown(PipelineView pipeline) {
        var names = pipeline.unknown();

        return names.isEmpty()
               ? Stream.of()
               : Stream.of("<p class=\"warn\">job names no family rule knows (filed under other): " + names.stream()
                                                                                                           .map(Html::text)
                                                                                                           .collect(Collectors.joining(", "))
                          + "</p>");
    }

    private static Stream<String> weeks(PipelineView pipeline) {
        var weeks = pipeline.weeks();

        return weeks.isEmpty()
               ? Stream.of("<p>no merged PR listed yet</p>")
               : Stream.of(Stream.of("<table>", WEEK_HEADER),
                           weeks.reversed().stream().map(PipelinePanel::weekRow),
                           Stream.of("</table>"),
                           weeks.reversed().stream().map(week -> drillDown(pipeline, week)))
                       .flatMap(lines -> lines);
    }

    private static String weekRow(WeekRow week) {
        return "<tr>" + cells(week.week(),
                              week.measured()
                             + " / " + week.prs() + (week.incomplete() > 0
                                                     ? " (" + week.incomplete()
                                                      + " INCOMPLETE" + (week.excluded() > 0
                                                                         ? ", " + week.excluded()
                                                                          + " left out of the sums"
                                                                         : "")
                                                      + ")"
                                                     : ""),
                              hours(week.totalSeconds()),
                              hours(week.prSeconds()),
                              hours(week.queueSeconds()),
                              hours(week.rerunSeconds()),
                              hours(week.fullReactorSeconds()),
                              hours(week.emberSeconds()),
                              hours(week.forgeSeconds()),
                              hours(week.consensusSeconds()),
                              hours(week.otherSeconds()),
                              week.removals()
                             + " (" + week.flakes()
                             + " / " + week.realFailures()
                             + " / " + week.conflicts()
                             + " / " + week.manual()
                             + " / " + week.unclassified()
                             + ")",
                              Long.toString(week.baseSyncs()),
                              Long.toString(week.otherMerges()),
                              hours(week.syncSeconds()))
             + "</tr>";
    }

    private static String drillDown(PipelineView pipeline, WeekRow week) {
        var prs = pipeline.measures().stream().filter(measure -> measure.week()
                                                                        .equals(week.week())).toList();

        return "<details><summary>" + week.week()
             + ": " + prs.size()
             + " PRs measured</summary>\n" + (prs.isEmpty()
                                              ? "<p>none measured yet</p>"
                                              : prTable(pipeline, prs))
             + "\n</details>";
    }

    private static String prTable(PipelineView pipeline, List<PrMeasure> prs) {
        return Html.lines(Stream.of(Stream.of("<table>", PR_HEADER),
                                    prs.stream().map(measure -> prRow(pipeline, measure)),
                                    Stream.of("</table>"))
                                .flatMap(lines -> lines)
                                .toList());
    }

    private static String prRow(PipelineView pipeline, PrMeasure measure) {
        return "<tr><td>" + Html.external("https://github.com/" + pipeline.scope().repo() + "/pull/" + measure.number(),
                                          "#" + measure.number()) + progress(measure)
             + " " + Html.text(measure.title())
             + "</td>" + cells(measure.mergedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                               hours(measure.totalSeconds()),
                               hours(measure.prSeconds()),
                               hours(measure.queueSeconds()),
                               hours(measure.rerunSeconds()),
                               hours(measure.fullReactorSeconds()),
                               hours(measure.emberSeconds()),
                               hours(measure.forgeSeconds()),
                               hours(measure.consensusSeconds()),
                               hours(measure.otherSeconds()),
                               removals(measure.removals()),
                               Integer.toString(measure.baseSyncs()),
                               Integer.toString(measure.otherMerges()),
                               hours(measure.syncSeconds()),
                               measure.runs() + " / " + measure.attempts())
             + "</tr>";
    }

    private static String progress(PrMeasure measure) {
        return measure.excluded()
               ? " (INCOMPLETE, left out of the sums, " + measure.excludedKind() + ": " + Html.text(measure.why()) + ")"
               : measure.complete()
                 ? ""
                 : " (runs in progress)";
    }

    private static String removals(List<Removal> removals) {
        return removals.isEmpty()
               ? "0"
               : removals.stream()
                         .map(removal -> removal.kind()
                                                .name()
                                                .toLowerCase(Locale.ROOT) + " (" + removal.reason() + ")")
                         .collect(Collectors.joining(", "));
    }

    /// Escaped cells: kiln's own numbers and times, and a removal's reason (escaped; the title and the job names are
    /// GitHub text and go through [Html#text]).
    private static String cells(String... values) {
        return Stream.of(values)
                     .map(value -> "<td>" + Html.escape(value) + "</td>")
                     .collect(Collectors.joining());
    }

    /// Machine-hours with one decimal.
    static String hours(long seconds) {
        return String.format(Locale.ROOT, "%.1f", seconds / 3600.0);
    }
}
