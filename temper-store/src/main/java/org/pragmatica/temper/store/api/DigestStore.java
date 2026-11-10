package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Promise;


/// The agent digest's reads (dashboard design §2). Read-only, and nothing else: no subscriber row and no ack, so the
/// cursor lives with the caller (`GET subscribers/{name}/next` creates its subscriber, which is why the digest does
/// not use it).
public interface DigestStore {
    /// The most waiting jobs (QUEUED, or with a unit still QUEUED) one digest lists; [DigestSnapshot#queued] still
    /// counts them all.
    int QUEUE_LIMIT = 100;
    /// One `REPEATABLE READ, READ ONLY` transaction: every part comes from the same committed state, and Postgres
    /// itself refuses a write inside it. The events follow [EventWindow#eventWindow]; a `since` ahead of the event
    /// log fails with [StoreError.CursorAhead].
    Promise<DigestSnapshot> snapshot(DigestQuery query);
}
