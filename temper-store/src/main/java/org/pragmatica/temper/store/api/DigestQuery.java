package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// What a digest reads (dashboard design §2): the events after `since`, the [EventCursor] the caller got from its
/// previous digest, at most `max` of them. Without `since` the digest starts at the head of the event log, so a first read
/// shows what stands now and replays nothing older. `max` is 1..[#MAX_EVENTS].
public record DigestQuery(Option<EventCursor> since, int max) {
    public static final int DEFAULT_MAX = 10;
    public static final int MAX_EVENTS = 1000;

    public static Result<DigestQuery> digestQuery(Option<EventCursor> since, int max) {
        return Verify.ensure(max, Verify.Is::between, 1, MAX_EVENTS, invalid("max")).map(valid -> new DigestQuery(since,
                                                                                                                  valid));
    }
}
