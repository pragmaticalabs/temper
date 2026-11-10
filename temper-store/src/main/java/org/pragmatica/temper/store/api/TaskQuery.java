package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.Status;
import org.pragmatica.temper.domain.task.TaskId;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// `GET /tasks` filters (m3-design §11.1), as [JobQuery]: each present filter must match, newest first, `limit` in
/// 1..[#MAX_LIMIT], `before` the keyset cursor of the next page.
public record TaskQuery(Option<Stage> stage, Option<Status> status, int limit, Option<TaskId> before) {
    public static final int MAX_LIMIT = 1000;
    public static final int DEFAULT_LIMIT = 50;

    public static Result<TaskQuery> taskQuery(Option<Stage> stage,
                                              Option<Status> status,
                                              int limit,
                                              Option<TaskId> before) {
        return Verify.ensure(limit, Verify.Is::between, 1, MAX_LIMIT, invalid("limit")).map(valid -> new TaskQuery(stage,
                                                                                                                   status,
                                                                                                                   valid,
                                                                                                                   before));
    }
}
