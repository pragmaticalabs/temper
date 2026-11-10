package org.pragmatica.kiln.core.feed;

/// The change kinds kiln derives from GitHub for the change feed (design §2.1, §2.5), beside the
/// summary's [org.pragmatica.kiln.core.summary.ChangeKind]. The `change.kind` CHECK (kiln V3) admits each of them.
/// `STALE_RUN_ALIVE` is T4's and is not produced here. `BASE_MOVED` is CI interface T2's (kiln V6).
public enum FeedKind {
    CHECK_CONCLUDED,
    CHECKS_SETTLED,
    PUSH_RUN_CONCLUDED,
    SCHEDULED_RUN_CONCLUDED,
    BOT_ISSUE_OPENED,
    BOT_ISSUE_CLOSED,
    RUN_CANCELLED,
    REQUIRED_LIST_CHANGED,
    BASE_MOVED
}
