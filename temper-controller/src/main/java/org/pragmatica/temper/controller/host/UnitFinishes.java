package org.pragmatica.temper.controller.host;

import java.time.Instant;

import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.domain.verdict.Verdict;
import org.pragmatica.temper.store.api.UnitFinishRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The ways a unit ends without a `UnitExited` report: lost (ERROR(lost), spec §6.4), refused at start
/// (VOID(disk) / ERROR(infra), §7.4), cancelled before it started, and a failed workspace preparation
/// (ERROR(seed-isolation) or ERROR(infra)). A reason is stored as reported, detail included (`infra: docker build
/// failed`); the verdict shows only its token ([Verdict#token]) and the result record carries the detail.
public sealed interface UnitFinishes {
    String LOST = "lost";
    String INFRA = Verdict.INFRA;
    String SEED_ISOLATION = "seed-isolation";

    static UnitFinishRow lost(Instant now) {
        return UnitFinishRow.unitFinishRow(none(), none(), now, none(), 0, none(), some(LOST));
    }

    static UnitFinishRow voided(String reason, Instant now) {
        return UnitFinishRow.unitFinishRow(none(), none(), now, none(), 0, some(reason), none());
    }

    static UnitFinishRow errored(String reason, Instant now) {
        return UnitFinishRow.unitFinishRow(none(), none(), now, none(), 0, none(), some(reason));
    }

    /// Never started because it was cancelled: `killedBy = cancel` makes the store judge it CANCELLED.
    static UnitFinishRow cancelled(Instant now) {
        return UnitFinishRow.unitFinishRow(none(), some(KillCause.CANCEL), now, none(), 0, none(), none());
    }

    /// A worker `Failure` as an error reason: `seed-isolation` is kept (A13); anything else is an infrastructure
    /// failure. The worker's detail follows the token: `seed-isolation: 3 hardlinked files`, `infra: collect: …`.
    static String workerFailure(String code, String detail) {
        return SEED_ISOLATION.equals(code)
               ? SEED_ISOLATION + ": " + detail
               : INFRA + ": " + code + ": " + detail;
    }

    record unused() implements UnitFinishes {}
}
