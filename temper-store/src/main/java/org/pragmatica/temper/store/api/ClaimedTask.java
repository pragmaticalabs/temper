package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;


/// A claim's answer (m3-design §6.1): the leased row and its detail, both read in the claim's transaction, with the
/// lane's latest checkpoint and what the task's report showed BEFORE this claim (the predecessor's last self-report).
public record ClaimedTask(TaskRow row, TaskDetail detail, Option<Checkpoint> checkpoint, TaskReport predecessor) {
    public static ClaimedTask claimedTask(TaskRow row, TaskDetail detail) {
        return new ClaimedTask(row, detail, Option.none(), TaskReport.NONE);
    }

    public static ClaimedTask claimedTask(TaskRow row,
                                          TaskDetail detail,
                                          Option<Checkpoint> checkpoint,
                                          TaskReport predecessor) {
        return new ClaimedTask(row, detail, checkpoint, predecessor);
    }
}
