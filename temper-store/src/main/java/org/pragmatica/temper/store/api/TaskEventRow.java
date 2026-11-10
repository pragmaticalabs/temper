package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.temper.domain.task.TaskEvent;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskState;


/// A `task_events` row (m3-design §5.3): the event and the after-image of the task's projected state it left, written
/// once and never deleted in M3. Folding a task's rows in `seq` order gives its `tasks` row ([#state] of the last one).
public record TaskEventRow(long seq, Instant at, TaskId task, TaskEvent event, String actor, TaskState state) {
    public static TaskEventRow taskEventRow(long seq,
                                            Instant at,
                                            TaskId task,
                                            TaskEvent event,
                                            String actor,
                                            TaskState state) {
        return new TaskEventRow(seq, at, task, event, actor, state);
    }
}
