package org.pragmatica.temper.domain.task;

import java.util.Arrays;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// A task's status within its stage (m3-design §5.1): READY to claim, LEASED to a worker at the lease epoch, WAITING on
/// linked jobs or CI (not claimable), HELD for the maintainer, CLOSED. Stored and sent as its name (`tasks.status`).
public enum Status {
    READY,
    LEASED,
    WAITING,
    HELD,
    CLOSED;
    /// Parses the stored and wire form, the name; case-insensitive for the CLI's `--status`.
    public static Result<Status> status(String raw) {
        return option(raw).flatMap(Status::byName)
                     .toResult(invalid("status").apply(raw));
    }
    private static Option<Status> byName(String raw) {
        return Option.from(Arrays.stream(values())
                                 .filter(status -> status.name()
                                                         .equalsIgnoreCase(raw.trim()))
                                 .findFirst());
    }
}
