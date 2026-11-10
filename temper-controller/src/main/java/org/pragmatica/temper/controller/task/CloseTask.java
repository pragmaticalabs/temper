package org.pragmatica.temper.controller.task;

import java.time.Instant;
import java.util.function.Supplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.TaskStore;


/// `POST tasks/{id}/close` (m3-design §5.2 T15, T16; the maintainer): `abandoned` closes any non-terminal task; `merged` asks
/// GitHub whether the task's PR is merged (§10.5) and closes only then, and a task without a PR is never merged. A PR
/// state GitHub could not answer fails the close (fail closed), never closes it.
@FunctionalInterface
public interface CloseTask {
    record Request(TaskId task, boolean merged, String actor) {
        public static Request request(TaskId task, boolean merged, String actor) {
            return new Request(task, merged, actor);
        }
    }

    Promise<TaskRow> execute(Request request);

    static CloseTask closeTask(TaskStore tasks, PullMerges merges, Supplier<Instant> clock) {
        return request -> closed(tasks, merges, clock, request);
    }

    private static Promise<TaskRow> closed(TaskStore tasks,
                                           PullMerges merges,
                                           Supplier<Instant> clock,
                                           Request request) {
        return request.merged()
               ? mergedClose(tasks, merges, clock, request)
               : tasks.command(request.task(), new Command.CloseAbandoned(), request.actor(), clock.get());
    }

    private static Promise<TaskRow> mergedClose(TaskStore tasks,
                                                PullMerges merges,
                                                Supplier<Instant> clock,
                                                Request request) {
        return tasks.task(request.task())
                    .flatMap(row -> row.async(StoreError.TaskNotFound.FACTORY.apply(request.task())))
                    .flatMap(row -> isMerged(merges, row))
                    .flatMap(merged -> tasks.command(request.task(),
                                                     new Command.CloseMerged(merged),
                                                     request.actor(),
                                                     clock.get()));
    }

    private static Promise<Boolean> isMerged(PullMerges merges, TaskRow row) {
        return row.state()
                  .pr()
                  .map(merges::isMerged)
                  .or(() -> Promise.success(false));
    }
}
