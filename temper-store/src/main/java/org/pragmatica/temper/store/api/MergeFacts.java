package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// What the store holds about one merge sighting, read under the T2 lock: the newest GO that
/// gates it (outcome `merging` or `merged`, at that sha, or at any sha when the sha is unknown), whether a merge of the
/// PR is recorded already (at any sha, by either route: a PR merges once, R3-2.i), and the queue holding the PR, by position (empty when the PR is not queued).
public record MergeFacts(Option<GatingGo> gate, boolean recorded, List<QueueEntry> queue) {
    public MergeFacts {
        queue = List.copyOf(queue);
    }

    public static MergeFacts mergeFacts(Option<GatingGo> gate, boolean recorded, List<QueueEntry> queue) {
        return new MergeFacts(gate, recorded, queue);
    }

    /// The GO a merge rests on: its id and the base tip recorded by its `merging` outcome.
    public record GatingGo(String id, Option<String> baseTipBefore) {
        public static GatingGo gatingGo(String id, Option<String> baseTipBefore) {
            return new GatingGo(id, baseTipBefore);
        }
    }

    /// The facts for one key, read in the caller's transaction.
    @FunctionalInterface
    public interface Reader {
        Promise<MergeFacts> facts(MergeKey key);
    }
}
