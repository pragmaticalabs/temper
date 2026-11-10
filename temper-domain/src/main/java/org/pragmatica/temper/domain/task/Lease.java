package org.pragmatica.temper.domain.task;

/// A lease-token call's credential (m3-design §6.4): the task, the epoch the caller claims to hold, and the sha256 of
/// the token it presented. The store accepts it only where `(id, LEASED, epoch, hash)` all match the row.
public record Lease(TaskId task, long epoch, String tokenHash) {
    public static Lease lease(TaskId task, long epoch, String tokenHash) {
        return new Lease(task, epoch, tokenHash);
    }

    /// Never prints the hash.
    @Override
    public String toString() {
        return "Lease[task=" + task + ", epoch=" + epoch + "]";
    }
}
