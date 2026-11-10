package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.refusal.RefusalRule;


/// One `refusals` row in `GET /api/v1/refusals` (m1-design §6): the code, the matcher as stored, the issue it
/// waits on, why, the free-text end condition, and when it was expired (absent while active).
public record RefusalDto(String code,
                         RefusalMatcherDto matcher,
                         Option<Integer> issue,
                         String reason,
                         Option<String> until,
                         Option<Instant> expiredAt) {
    public static RefusalDto refusalDto(RefusalRule rule,
                                        Option<Integer> issue,
                                        String reason,
                                        Option<String> until,
                                        Option<Instant> expiredAt) {
        return new RefusalDto(rule.code(), RefusalMatcherDto.from(rule), issue, reason, until, expiredAt);
    }
}
