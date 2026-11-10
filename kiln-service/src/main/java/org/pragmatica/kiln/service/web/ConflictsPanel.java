package org.pragmatica.kiln.service.web;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.conflict.Prediction;
import org.pragmatica.kiln.service.conflict.ConflictBoard;
import org.pragmatica.lang.Option;


/// The Conflicts tab (temper #100): the merge-tree early warning's current predictions against the release tip, hot
/// files marked `HOT`, the predictions carried from an earlier step that this one could not re-check, and what the last
/// checks cost (fetch time and bytes, check time). It states the limit first: textual conflicts only. Red while the
/// last step failed; the board stays as the last good step left it.
final class ConflictsPanel {
    private ConflictsPanel() {}

    private static final String HEADER = "<tr><th>kind</th><th>PRs (landing order that conflicts)</th><th>conflicting paths</th></tr>";

    private static final String COST_HEADER = "<tr><th>at</th><th>tip checks</th><th>pair checks</th><th>check</th>"
                                            + "<th>fetch</th><th>fetched</th></tr>";

    static String render(DashboardView view, Instant now) {
        return view.conflicts()
                   .map(board -> board(board, now))
                   .or(() -> Html.panel("conflicts",
                                        "Conflicts",
                                        false,
                                        Html.asOf(Option.none(),
                                                  now),
                                        limit() + "\n<p>the conflict watch is off (no <code>[conflicts]</code>)</p>"));
    }

    /// The tab's count: the current predictions.
    static int count(DashboardView view) {
        return view.conflicts()
                   .map(board -> board.predictions()
                                      .size())
                   .or(0);
    }

    private static String board(ConflictBoard board, Instant now) {
        return Html.panel("conflicts",
                          "Conflicts",
                          board.failure().isPresent(),
                          Html.asOf(board.checkedAt(), now),
                          Html.lines(Stream.of(limit(),
                                               state(board),
                                               failure(board),
                                               predictions(board.predictions(),
                                                           "no textual conflict predicted"),
                                               board.carried()
                                                    .isEmpty()
                                               ? ""
                                               : "<h3>carried (not re-checked this step)</h3>\n" + predictions(board.carried(),
                                                                                                               ""),
                                               costs(board.costs(),
                                                     now))
                                           .filter(html -> !html.isEmpty())
                                           .toList()));
    }

    private static String limit() {
        return "<p><strong>Limit:</strong> " + Html.escape(ConflictBoard.LIMIT) + "</p>";
    }

    private static String state(ConflictBoard board) {
        return "<p>" + Html.escape(board.branch().value())
             + " at <code>" + board.tip()
                                   .map(sha -> Html.abbreviated(sha.value()))
                                   .or("?")
             + "</code>; " + board.open()
             + " open PRs checked" + (board.unknown() > 0
                                      ? ", " + board.unknown() + " not checkable yet"
                                      : "") + (board.skipped() > 0
                                               ? "; <strong class=\"warn\">pairs skipped: " + board.skipped()
                                                + "</strong> (over the per-step cap, run by the next steps)"
                                               : "")
             + "</p>";
    }

    private static String failure(ConflictBoard board) {
        return board.failure()
                    .map(message -> "<p class=\"red\">last step failed: " + Html.escape(message) + "</p>")
                    .or("");
    }

    private static String predictions(List<Prediction> predictions, String none) {
        return predictions.isEmpty()
               ? (none.isEmpty()
                  ? ""
                  : "<p>" + none + "</p>")
               : Html.lines(Stream.of(Stream.of("<table>", HEADER),
                                      predictions.stream().map(ConflictsPanel::row),
                                      Stream.of("</table>"))
                                  .flatMap(lines -> lines)
                                  .toList());
    }

    private static String row(Prediction prediction) {
        return "<tr><td>" + prediction.kind()
                                      .word()
             + "</td><td>" + prs(prediction)
             + "</td><td>" + prediction.paths()
                                       .stream()
                                       .map(ConflictsPanel::path)
                                       .collect(Collectors.joining("<br>"))
             + "</td></tr>";
    }

    /// A tip prediction's PR; a pair's conflicting landing orders, one per line (`#2711 then #2713`), or both PRs when
    /// the order is unknown (PR #108 review F4).
    private static String prs(Prediction prediction) {
        return prediction.firsts()
                         .isEmpty()
               ? prediction.prs()
                           .stream()
                           .map(pr -> "#" + pr.value())
                           .collect(Collectors.joining(" + "))
               : prediction.firsts()
                           .stream()
                           .map(first -> "#" + first.value()
                                        + " then " + prediction.prs()
                                                               .stream()
                                                               .filter(pr -> !pr.equals(first))
                                                               .map(pr -> "#" + pr.value())
                                                               .collect(Collectors.joining()))
                           .collect(Collectors.joining("<br>"));
    }

    private static String path(String path) {
        return Prediction.hot(path)
               ? "<strong class=\"warn\">HOT</strong> <code>" + Html.escape(path) + "</code>"
               : "<code>" + Html.escape(path) + "</code>";
    }

    private static String costs(List<ConflictBoard.Cost> costs, Instant now) {
        return costs.isEmpty()
               ? "<p>no check has run yet</p>"
               : Html.lines(Stream.of(Stream.of("<h3>cost per push (last " + costs.size() + " steps that checked)</h3>",
                                                "<table>",
                                                COST_HEADER),
                                      costs.stream().map(cost -> costRow(cost, now)),
                                      Stream.of("</table>"))
                                  .flatMap(lines -> lines)
                                  .toList());
    }

    /// none is unknown, never "nothing fetched": a `gc` or repack in the same step hides that step's fetch.
    private static final String UNKNOWN_BYTES = "unknown: the store was compacted or unreadable during the step";

    private static String costRow(ConflictBoard.Cost cost, Instant now) {
        return "<tr><td>" + Html.stamp(cost.at(), now)
             + "</td><td>" + cost.tipChecks()
             + "</td><td>" + cost.pairChecks()
             + "</td><td>" + cost.checkMillis()
             + " ms</td><td>" + cost.fetchMillis()
                                    .map(ms -> ms + " ms")
                                    .or("n/a")
             + "</td><td>" + cost.fetchedBytes()
                                 .map(bytes -> Html.size(bytes / 1024))
                                 .or("<span title=\"" + UNKNOWN_BYTES + "\">n/a</span>")
             + "</td></tr>";
    }
}
