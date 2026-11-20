package org.pragmatica.temper.store.api;

/// One maven launcher the host's queue did not start (`bb-tickets` trailer `offq`, as stored in `bb_snapshot.offq`).
/// `startS` is the process start on the host clock (epoch seconds), `owner` the checkout prefix or `unowned`, `cwd` the
/// full working directory. `key` is the identity an alarm mark carries as its subject: a pid alone could be reused.
public record BbOffQueue(long pid, long startS, String owner, String cwd) {
    public static BbOffQueue bbOffQueue(long pid, long startS, String owner, String cwd) {
        return new BbOffQueue(pid, startS, owner, cwd);
    }

    public String key() {
        return pid + ":" + startS;
    }
}
