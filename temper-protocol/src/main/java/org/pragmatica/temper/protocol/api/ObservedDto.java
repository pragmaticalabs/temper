package org.pragmatica.temper.protocol.api;

import java.time.Instant;


/// The parent-observed size of a lane's agent (`POST tasks/{id}/observe`): context in thousands of tokens and turns
/// (distinct message ids), measured from the agent's transcript by the parent, never self-reported.
public record ObservedDto(int ctxK, int turns, Instant at) {}
