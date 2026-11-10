package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.task.TaskBrief;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskState;


/// A `tasks` row (m3-design §5.3): the task's static facts, as created, and its current [TaskState], the projection of
/// its `task_events`.
public record TaskRow(TaskId id,
                      String slug,
                      String title,
                      List<Integer> issues,
                      TaskBrief brief,
                      String repo,
                      Ref baseRef,
                      Ref branch,
                      boolean critical,
                      int priority,
                      List<TaskId> after,
                      OwnerTag createdBy,
                      Instant createdAt,
                      TaskState state) {
    public TaskRow {
        issues = List.copyOf(issues);
        after = List.copyOf(after);
    }

    public static TaskRow taskRow(TaskId id,
                                  String slug,
                                  String title,
                                  List<Integer> issues,
                                  TaskBrief brief,
                                  String repo,
                                  Ref baseRef,
                                  Ref branch,
                                  boolean critical,
                                  int priority,
                                  List<TaskId> after,
                                  OwnerTag createdBy,
                                  Instant createdAt,
                                  TaskState state) {
        return new TaskRow(id,
                           slug,
                           title,
                           issues,
                           brief,
                           repo,
                           baseRef,
                           branch,
                           critical,
                           priority,
                           after,
                           createdBy,
                           createdAt,
                           state);
    }
}
