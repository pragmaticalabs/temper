package org.pragmatica.temper.domain.model;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// Job identifier (a bigserial value, so always positive).
public record JobId(long value) {
    public static Result<JobId> jobId(long raw) {
        return Verify.ensure(raw, Verify.Is::positive, invalid("job id")).map(JobId::new);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
