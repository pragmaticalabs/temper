package org.pragmatica.temper.controller.task;

import java.time.Instant;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.controller.submit.Mirror;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.NewTask;
import org.pragmatica.temper.domain.task.ProtectedRefs;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.TaskStore;


/// `POST /tasks` (m3-design §5.4): a branch that matches a protected pattern is `protected-branch`, a base the mirror
/// does not have after a fetch is `base-unknown` (both 422); then the store creates the task TRIAGED/READY, and the
/// dispatcher's next sweep applies T1. A git that cannot run fails as `GitError` (503), nothing created.
@FunctionalInterface
public interface CreateTask {
    record Request(NewTask task) {
        public static Request request(NewTask task) {
            return new Request(task);
        }
    }

    Promise<TaskRow> execute(Request request);

    static CreateTask createTask(TaskStore tasks, Mirror mirror, ProtectedRefs protectedRefs, Supplier<Instant> clock) {
        return request -> unprotected(request.task(),
                                      protectedRefs).async()
                                     .flatMap(task -> knownBase(mirror, task))
                                     .flatMap(task -> tasks.create(task,
                                                                   clock.get()));
    }

    private static Result<NewTask> unprotected(NewTask task, ProtectedRefs protectedRefs) {
        return Verify.ensure(task,
                             candidate -> !protectedRefs.isProtected(candidate.branch()),
                             TaskRefusal.PROTECTED_BRANCH);
    }

    private static Promise<NewTask> knownBase(Mirror mirror, NewTask task) {
        return mirror.branchTip(task.baseRef())
                     .flatMap(tip -> baseFound(tip, task));
    }

    private static Promise<NewTask> baseFound(Option<Sha> tip, NewTask task) {
        return tip.map(_ -> task)
                  .async(TaskRefusal.BASE_UNKNOWN);
    }
}
