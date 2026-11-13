package org.pragmatica.temper.protocol.api;

/// Body of `POST tasks/{id}/observe` (full scope only: the agent's lease token is refused).
public record ObserveCall(int ctxK, int turns) {}
