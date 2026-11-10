package org.pragmatica.temper.domain.task;

import java.util.Arrays;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// The stage a task is in (m3-design §5.1). Stored and sent as its name (`tasks.stage`).
public enum Stage {
    TRIAGED,
    DESIGN,
    CODE,
    VERIFY,
    FIX,
    CI,
    GATE,
    MERGE_READY,
    NEEDS_RULING,
    MERGED,
    ABANDONED;
    /// MERGED and ABANDONED: the task is closed and no command moves it again.
    public boolean isTerminal() {
        return this == MERGED || this == ABANDONED;
    }
    /// The stages a worker claims (T2): DESIGN, CODE, VERIFY, FIX and GATE (M3a).
    public boolean isWorkerStage() {
        return switch (this) {
            case DESIGN, CODE, VERIFY, FIX, GATE -> true;
            case TRIAGED, CI, MERGE_READY, NEEDS_RULING, MERGED, ABANDONED -> false;
        };
    }
    /// VERIFY or a stage only reachable through it (FIX, CI, GATE, MERGE_READY, MERGED): what a task in `after` must
    /// have reached before its follower leaves TRIAGED (T1, ruling R-M3-18).
    public boolean isVerifyOrLater() {
        return switch (this) {
            case VERIFY, FIX, CI, GATE, MERGE_READY, MERGED -> true;
            case TRIAGED, DESIGN, CODE, NEEDS_RULING, ABANDONED -> false;
        };
    }
    /// The status a task has on entering this stage (T4): READY for TRIAGED and a worker stage, WAITING for CI (on the
    /// watcher), HELD for the maintainer's two queues, CLOSED for a terminal stage.
    public Status entryStatus() {
        return switch (this) {
            case TRIAGED, DESIGN, CODE, VERIFY, FIX, GATE -> Status.READY;
            case CI -> Status.WAITING;
            case MERGE_READY, NEEDS_RULING -> Status.HELD;
            case MERGED, ABANDONED -> Status.CLOSED;
        };
    }
    /// Parses the stored and wire form, the name; case-insensitive for the CLI's `--stage`.
    public static Result<Stage> stage(String raw) {
        return option(raw).flatMap(Stage::byName)
                     .toResult(invalid("stage").apply(raw));
    }
    private static Option<Stage> byName(String raw) {
        return Option.from(Arrays.stream(values())
                                 .filter(stage -> stage.name()
                                                       .equalsIgnoreCase(raw.trim()))
                                 .findFirst());
    }
}
