package org.pragmatica.kiln.core.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.BotIssue;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// `BOT_ISSUE_OPENED` and `BOT_ISSUE_CLOSED` (design §2.1, review F11). Issues are read with `state=all&since=`, so an
/// issue opened and closed while kiln was stopped is first seen closed, and reports both, opened first. A reopened issue
/// is opened again; an issue seen again in the state stored reports nothing.
public sealed interface IssueFeed {
    String OPEN = "open";
    /// The first read (no issue stored) reaches this far back.
    Duration FIRST_READ = Duration.ofDays(7);

    static Instant since(Option<Instant> newestUpdated, Instant at) {
        return newestUpdated.or(at.minus(FIRST_READ));
    }

    static List<FeedChange> changes(Option<String> stored, BotIssue issue, Instant at) {
        return kinds(stored, issue).stream()
                    .map(kind -> change(kind, stored, issue, at))
                    .toList();
    }

    /// Opened: a first sight, or a closed issue open again; closed: closed now and not before. Opened comes first.
    private static List<FeedKind> kinds(Option<String> stored, BotIssue issue) {
        var wasClosed = stored.filter(BotIssue.CLOSED::equals).isPresent();
        var opened = stored.isEmpty() || wasClosed && !issue.closed();
        var closed = issue.closed() && !wasClosed;

        return Stream.of(Map.entry(opened, FeedKind.BOT_ISSUE_OPENED),
                         Map.entry(closed, FeedKind.BOT_ISSUE_CLOSED))
                     .filter(Map.Entry::getKey)
                     .map(Map.Entry::getValue)
                     .toList();
    }

    private static FeedChange change(FeedKind kind, Option<String> stored, BotIssue issue, Instant at) {
        return new FeedChange(kind,
                              none(),
                              Long.toString(issue.number()),
                              stored.or(FeedChange.NONE),
                              issue.state().or(OPEN),
                              at,
                              FeedChange.payload("number",
                                                 issue.number(),
                                                 "title",
                                                 issue.title(),
                                                 "url",
                                                 issue.htmlUrl(),
                                                 "createdAt",
                                                 issue.createdAt().toString()));
    }

    record unused() implements IssueFeed {}
}
