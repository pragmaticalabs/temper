package org.pragmatica.temper.worker.docker;

/// A container labelled `org.testcontainers=true`, with its `org.testcontainers.sessionId` label (empty if none).
public record TcContainer(String id, String sessionId) {
    public static TcContainer tcContainer(String id, String sessionId) {
        return new TcContainer(id, sessionId);
    }

    /// `id/session` for evidence and logs.
    public String describe() {
        return id + "/" + sessionId;
    }
}
