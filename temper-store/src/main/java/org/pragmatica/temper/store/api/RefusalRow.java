package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.refusal.RefusalRule;


/// A `refusals` row (spec §2.5, m1-design §6): the rule (code and matcher), the issue it waits on, why, the
/// free-text end condition, and when it was expired (none while active).
public record RefusalRow(RefusalRule rule,
                         Option<Integer> issue,
                         String reason,
                         Option<String> until,
                         Option<Instant> expiredAt) {
    public static RefusalRow refusalRow(RefusalRule rule,
                                        Option<Integer> issue,
                                        String reason,
                                        Option<String> until,
                                        Option<Instant> expiredAt) {
        return new RefusalRow(rule, issue, reason, until, expiredAt);
    }
}
