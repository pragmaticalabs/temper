package org.pragmatica.temper.store.api;

/// One `merge_queue` row: positions are dense 1..n per (repo, base).
public record QueueEntry(String repo, String base, int position, long pr) {
    public static QueueEntry queueEntry(String repo, String base, int position, long pr) {
        return new QueueEntry(repo, base, position, pr);
    }
}
