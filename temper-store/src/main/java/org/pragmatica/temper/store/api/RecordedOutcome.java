package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;


/// An outcome recorded. `merge` is present only for `merged` when this transaction inserted the
/// `merges` row (route `temper-merge`): then `merged` is emitted, and the PR's queue removal with it.
public record RecordedOutcome(String id, String outcome, Option<Merge> merge) {
    public static RecordedOutcome recordedOutcome(String id, String outcome, Option<Merge> merge) {
        return new RecordedOutcome(id, outcome, merge);
    }

    /// The merge the GO recorded: the GO's basis and `merging` tip, and the queue entry the PR left with the head of
    /// that queue after the removal (both none when the PR was not queued).
    public record Merge(GoBasis basis,
                        Option<String> baseTipBefore,
                        Option<QueueEntry> dequeued,
                        Option<QueueEntry> next) {
        public static Merge merge(GoBasis basis,
                                  Option<String> baseTipBefore,
                                  Option<QueueEntry> dequeued,
                                  Option<QueueEntry> next) {
            return new Merge(basis, baseTipBefore, dequeued, next);
        }
    }
}
