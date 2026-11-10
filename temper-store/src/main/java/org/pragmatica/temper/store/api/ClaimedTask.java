package org.pragmatica.temper.store.api;

/// A claim's answer (m3-design §6.1): the leased row and its detail, both read in the claim's transaction.
public record ClaimedTask(TaskRow row, TaskDetail detail) {
    public static ClaimedTask claimedTask(TaskRow row, TaskDetail detail) {
        return new ClaimedTask(row, detail);
    }
}
