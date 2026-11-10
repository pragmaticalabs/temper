package org.pragmatica.kiln.core.feed;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `REQUIRED_LIST_CHANGED` and the `required_list_history` versions (design §2.1, F18, NOTE 9). A list's contexts are
/// its checks' labels, sorted; a base that requires nothing has none. An unreadable list ([RequiredChecks.Unknown])
/// has no contexts to compare, so it neither changes the version nor reports. The first list seen for a base is its
/// version 1 and reports nothing (a first sight is no change).
public sealed interface ListFeed {
    String JOIN = ",";

    static Option<List<String>> contexts(RequiredChecks list) {
        return switch (list) {
            case RequiredChecks.Required required -> some(required.checks().stream().map(RequiredCheck::label).distinct().sorted().toList());
            case RequiredChecks.NoneRequired _ -> some(List.of());
            case RequiredChecks.Unknown _ -> none();
        };
    }

    static Option<FeedChange> changed(BranchName base,
                                      Option<List<String>> stored,
                                      List<String> now,
                                      long version,
                                      Instant at) {
        return stored.filter(previous -> !previous.equals(now))
                     .map(previous -> new FeedChange(FeedKind.REQUIRED_LIST_CHANGED,
                                                     none(),
                                                     base.value(),
                                                     String.join(JOIN, previous),
                                                     String.join(JOIN, now),
                                                     at,
                                                     FeedChange.payload("base",
                                                                        base.value(),
                                                                        "version",
                                                                        version,
                                                                        "old",
                                                                        previous,
                                                                        "new",
                                                                        now)));
    }

    record unused() implements ListFeed {}
}
