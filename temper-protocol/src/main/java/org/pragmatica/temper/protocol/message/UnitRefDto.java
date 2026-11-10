package org.pragmatica.temper.protocol.message;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;


/// Wire form of [UnitRef] and the unit's attempt (protocol v3, m4-design §7): a heartbeat's running unit, or the unit
/// an unsolicited failure is about, names the attempt it belongs to.
public record UnitRefDto(long job, String unit, int attempt) {
    public static UnitRefDto unitRefDto(UnitRef ref, int attempt) {
        return new UnitRefDto(ref.job().value(),
                              ref.unit().value(),
                              attempt);
    }

    public Result<UnitRef> toDomain() {
        return Result.all(JobId.jobId(job),
                          UnitName.unitName(unit))
                     .map(UnitRef::unitRef);
    }
}
