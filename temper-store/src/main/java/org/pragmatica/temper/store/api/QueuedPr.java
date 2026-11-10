package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// One merge-queue row as `GET fleet/merge-queue` shows it: its place, who added it and when,
/// and the newest outstanding GO for the PR, if any.
public record QueuedPr(QueueEntry entry, String addedBy, Instant addedAt, Option<OpenGo> go) {
    /// An outstanding GO (not consumed, not invalidated, created less than 10 minutes ago) and when it expires.
    public record OpenGo(String id, String sha, Instant expiresAt) {
        public static OpenGo openGo(String id, String sha, Instant expiresAt) {
            return new OpenGo(id, sha, expiresAt);
        }
    }

    public static QueuedPr queuedPr(QueueEntry entry, String addedBy, Instant addedAt, Option<OpenGo> go) {
        return new QueuedPr(entry, addedBy, addedAt, go);
    }
}
