package org.pragmatica.temper.domain.task;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// Task identifier (`tasks.id`, a bigserial value, so always positive).
public record TaskId(long value) {
    public static Result<TaskId> taskId(long raw) {
        return Verify.ensure(raw, Verify.Is::positive, invalid("task id")).map(TaskId::new);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
