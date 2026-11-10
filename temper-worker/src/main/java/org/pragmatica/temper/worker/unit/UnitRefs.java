package org.pragmatica.temper.worker.unit;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;


/// Parses the `job`/`unit` pair every unit message carries; the unit name becomes a path segment and a docker
/// name, so it must pass the domain pattern before anything uses it.
public sealed interface UnitRefs {
    static Result<UnitRef> unitRef(long job, String unit) {
        return Result.all(JobId.jobId(job),
                          UnitName.unitName(unit))
                     .map(UnitRef::unitRef);
    }

    record unused() implements UnitRefs {}
}
