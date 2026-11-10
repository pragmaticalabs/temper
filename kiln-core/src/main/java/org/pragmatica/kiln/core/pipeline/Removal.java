package org.pragmatica.kiln.core.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// One merge-queue removal whose reason is not `merged` (#102), classified by what reached the PR between the queue
/// entry the removal ended and the next entry (or the merge, when none follows). `reason` is GitHub's string, kept raw;
/// `detail` says what the classification saw.
///
/// The PR's head at each entry is not in GitHub's data: `RemovedFromMergeQueueEvent.beforeCommit` is the queue's own
/// squash commit (its one parent is the base; checked on #2784, #2803, #2830), and the queue branch names the base sha.
/// So the PR side is read instead: a commit whose committer date falls inside the window was made after the entry and
/// pushed before the next one (a push while queued removes the PR again, so it cannot land later unseen), and a
/// `HeadRefForcePushedEvent` inside the window says the head was rewritten. [guess: a commit made before the entry but
/// pushed only after the removal reads as no new commit, so such a removal reads FLAKE; rare, since a commit made while
/// queued is normally pushed at once.]
public record Removal(Instant at, String reason, Kind kind, String detail) {
    public static final String MERGED = "merged";
    public static final String MANUAL = "manual";

    public enum Kind {
        /// The reason names a conflict, or the only commits added before the next entry are merges (syncs).
        CONFLICT,
        /// Non-merge commits were added before the next entry, or the head was force-pushed.
        REAL_FAILURE,
        /// The PR re-entered the queue (or merged) with no new commit.
        FLAKE,
        /// Removed by hand (reason `manual`): neither a flake nor a failure, counted apart; `detail` still says what the
        /// window saw.
        MANUAL,
        /// No queue entry precedes the removal in the timeline read, so there is no window to look at.
        UNCLASSIFIED
    }

    /// Every removal of `pr` whose reason is not `merged`, in timeline order.
    public static List<Removal> removals(MergedPr pr) {
        return pr.queue()
                 .stream()
                 .filter(MergedPr.QueueEvent.Removed.class::isInstance)
                 .map(MergedPr.QueueEvent.Removed.class::cast)
                 .filter(removed -> !merged(removed))
                 .map(removed -> classified(pr, removed))
                 .toList();
    }

    public static boolean merged(MergedPr.QueueEvent.Removed removed) {
        return removed.reason()
                      .equalsIgnoreCase(MERGED);
    }

    static Removal classified(MergedPr pr, MergedPr.QueueEvent.Removed removed) {
        var reason = removed.reason();

        if (reason.toLowerCase(Locale.ROOT).contains("conflict")) {
            return new Removal(removed.at(), reason, Kind.CONFLICT, "reason names a conflict");
        }

        var read = entryBefore(pr,
                               removed.at()).map(entry -> byWindow(pr,
                                                                   removed,
                                                                   entry,
                                                                   nextEntry(pr,
                                                                             removed.at())))
                              .or(new Removal(removed.at(),
                                              reason,
                                              Kind.UNCLASSIFIED,
                                              "no queue entry before the removal"));

        return reason.equalsIgnoreCase(MANUAL)
               ? new Removal(removed.at(), reason, Kind.MANUAL, "removed by hand; " + read.detail())
               : read;
    }

    /// The window is (entry, end]: `end` is the next entry, or the merge when the PR never re-entered.
    private static Removal byWindow(MergedPr pr, MergedPr.QueueEvent.Removed removed, Instant entry, Instant end) {
        var reason = removed.reason();
        var forced = pr.queue()
                       .stream()
                       .filter(MergedPr.QueueEvent.ForcePushed.class::isInstance)
                       .anyMatch(event -> inside(event.at(),
                                                 entry,
                                                 end));

        if (forced) {
            return new Removal(removed.at(), reason, Kind.REAL_FAILURE, "head force-pushed");
        }

        var added = pr.commits().stream().filter(commit -> inside(commit.committed(), entry, end)).toList();

        if (added.isEmpty()) {
            return new Removal(removed.at(), reason, Kind.FLAKE, "re-entered with no new commit");
        }

        return added.stream()
                    .allMatch(MergedPr.Commit::merge)
               ? new Removal(removed.at(), reason, Kind.CONFLICT, added.size() + " sync merge(s) added")
               : new Removal(removed.at(), reason, Kind.REAL_FAILURE, added.size() + " commit(s) added");
    }

    private static boolean inside(Instant at, Instant entry, Instant end) {
        return at.isAfter(entry) && !at.isAfter(end);
    }

    /// The latest entry at or before `at`.
    private static Option<Instant> entryBefore(MergedPr pr, Instant at) {
        return Option.from(entries(pr).filter(entry -> !entry.isAfter(at)).max(Instant::compareTo));
    }

    /// The first entry after `at`, else the merge.
    private static Instant nextEntry(MergedPr pr, Instant at) {
        return entries(pr).filter(entry -> entry.isAfter(at))
                      .min(Instant::compareTo)
                      .orElse(pr.mergedAt());
    }

    private static Stream<Instant> entries(MergedPr pr) {
        return pr.queue()
                 .stream()
                 .filter(MergedPr.QueueEvent.Added.class::isInstance)
                 .map(MergedPr.QueueEvent::at);
    }
}
