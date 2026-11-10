package org.pragmatica.temper.domain.task;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// One `task_events` row a transition appends (m3-design §5.3, §9.1): its kind and the facts it records (`reason`,
/// `clock`, `kind`, …, as text). The store adds the after-image of [TaskState] to every row, so the row equals the fold
/// of its events (the projection rule).
public record TaskEvent(Kind kind, Map<String, String> facts) {
    public TaskEvent {
        facts = Map.copyOf(facts);
    }

    public static TaskEvent taskEvent(Kind kind, Map<String, String> facts) {
        return new TaskEvent(kind, facts);
    }

    public static TaskEvent taskEvent(Kind kind) {
        return new TaskEvent(kind, Map.of());
    }

    /// The same event with one more fact (the store adds the id of a linked job it inserted after the transition).
    public TaskEvent with(String name, String value) {
        var more = new HashMap<>(facts);

        more.put(name, value);

        return new TaskEvent(kind, more);
    }

    /// Event kinds; stored lower case with `-`. `ready` carries `reason` (`created`, `advanced`, `requeued`,
    /// `resumed`, `ruled`, `bounced`, §9.1, `advanced` [G] for T1/T4/T9/T12); the lease's end is `evidence-accepted`,
    /// `waiting`, `released`, `lease-expired` or `parked`.
    public enum Kind {
        CREATED,
        ADVANCED,
        READY,
        LEASED,
        HEARTBEAT,
        JOB_LINKED,
        EVIDENCE_ACCEPTED,
        WAITING,
        RELEASED,
        LEASE_EXPIRED,
        LEASE_EXTENDED,
        PARKED,
        RULED,
        CI_DECIDED,
        BOUNCED,
        CLOSED;
        public String wire() {
            return name().toLowerCase(Locale.ROOT)
                       .replace('_', '-');
        }
        public static Result<Kind> kind(String raw) {
            return option(raw).flatMap(Kind::byWire)
                         .toResult(invalid("task event kind").apply(raw));
        }
        private static Option<Kind> byWire(String raw) {
            return Option.from(Arrays.stream(values()).filter(kind -> kind.wire()
                                                                          .equals(raw)).findFirst());
        }
    }
}
