package org.pragmatica.kiln.service.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.kiln.service.store.WorkBoard;
import org.pragmatica.kiln.service.store.WorkCard;
import org.pragmatica.kiln.service.store.WorkPhase;
import org.pragmatica.lang.Option;


/// The Work tab: the team's work packages as a kanban board, one column per [WorkPhase] in its declared order (`parked`
/// last), each package a card in the column of its current phase. A card shows the id and title, the round (`r2`), the
/// coder and verifier, the time since the package was submitted and the time in its current phase (`3h 12m`,
/// [Html#span]), and the last note. Every recorded text is escaped ([Html#text]); the note is free text.
///
/// A package merged more than [#MERGED_SHOWN] ago is hidden, and the count of hidden ones is shown with a link that
/// shows them (`?tab=work&merged=all`). The panel states the ledger's freshness, the time of its last event. The ledger
/// has no polling period, so nothing here is ever marked stale: an old last event is a quiet team, not a failed source.
public sealed interface WorkPage {
    /// A merged package stays on the board this long after it entered `merged`.
    Duration MERGED_SHOWN = Duration.ofDays(7);
    String SHOW_ALL = "<a href=\"/?tab=work&amp;merged=all\">show all</a>";

    static String render(WorkBoard board, Instant now, boolean allMerged) {
        var shown = board.cards().stream().filter(card -> allMerged || !hidden(card, now)).toList();

        return Html.panel("work",
                          "Work",
                          false,
                          freshness(board.lastEventAt(), now),
                          Html.lines(Stream.of(hiddenLine(board.cards().size() - shown.size()),
                                               columns(shown, now))
                                           .filter(html -> !html.isEmpty())
                                           .toList()));
    }

    /// The packages in flight, the tab's count: every one not merged and not parked.
    static long inFlight(WorkBoard board) {
        return board.cards()
                    .stream()
                    .filter(card -> card.phase() != WorkPhase.MERGED && card.phase() != WorkPhase.PARKED)
                    .count();
    }

    /// Merged more than [#MERGED_SHOWN] before `now`.
    static boolean hidden(WorkCard card, Instant now) {
        return card.phase() == WorkPhase.MERGED && card.phaseSince()
                                                       .isBefore(now.minus(MERGED_SHOWN));
    }

    /// The panel's `as of` line, as every panel has one: the time of the ledger's last event.
    private static String freshness(Option<Instant> last, Instant now) {
        return "<p class=\"asof\">as of " + last.map(at -> Html.stamp(at, now) + ": the last ledger event")
                                                .or("never: no ledger event recorded")
             + "</p>";
    }

    private static String hiddenLine(int hidden) {
        return hidden == 0
               ? ""
               : "<p class=\"grey\">" + hidden + " merged more than 7 days ago hidden · " + SHOW_ALL + "</p>";
    }

    private static String columns(List<WorkCard> cards, Instant now) {
        return Html.lines(Stream.of(Stream.of("<div class=\"board\">"),
                                    Arrays.stream(WorkPhase.values()).map(phase -> column(phase, cards, now)),
                                    Stream.of("</div>"))
                                .flatMap(lines -> lines)
                                .toList());
    }

    private static String column(WorkPhase phase, List<WorkCard> cards, Instant now) {
        var inPhase = cards.stream().filter(card -> card.phase() == phase).toList();

        return Html.lines(Stream.of(Stream.of("<div class=\"column\" data-phase=\"" + phase.label() + "\">",
                                              "<h3>" + phase.label()
                                             + " <span class=\"count\">" + inPhase.size()
                                             + "</span></h3>"),
                                    inPhase.stream().map(card -> card(card, now)),
                                    Stream.of("</div>"))
                                .flatMap(lines -> lines)
                                .toList());
    }

    static String card(WorkCard card, Instant now) {
        return "<div class=\"card\"><p><b>" + Html.text(card.id())
             + "</b>" + card.title()
                            .map(title -> " " + Html.text(title))
                            .or("")
             + "</p><p><span class=\"round\">r" + card.round()
             + "</span>" + people(card)
             + "</p><p><small>since submitted " + elapsed(card.submittedAt(), now)
             + " · in " + card.phase()
                              .label()
             + " " + elapsed(card.phaseSince(), now)
             + "</small></p>" + card.note()
                                    .map(note -> "<p>" + Html.text(note) + "</p>")
                                    .or("")
             + "</div>";
    }

    private static String people(WorkCard card) {
        return card.coder()
                   .map(coder -> " · coder " + Html.text(coder))
                   .or("") + card.verifier()
                                 .map(verifier -> " · verifier " + Html.text(verifier))
                                 .or("");
    }

    /// The time from `since` to `now` ([Html#span]); a time ahead of `now` within [Html#SKEW] is `0s`, and beyond it
    /// `clock skew`, never a negative span.
    static String elapsed(Instant since, Instant now) {
        return Html.future(since, now)
               ? "(clock skew)"
               : Html.span(Math.max(0,
                                    Duration.between(since, now).getSeconds()));
    }

    record unused() implements WorkPage {}
}
