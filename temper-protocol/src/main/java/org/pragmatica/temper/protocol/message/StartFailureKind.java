package org.pragmatica.temper.protocol.message;

/// How a `StartUnit` that did not start ends the unit: `VOID` (R-DISK), `ERROR` (infrastructure before mvn
/// ran), or `CANCELLED` (a `CancelUnit` raced ahead of the start).
public enum StartFailureKind {
    VOID,
    ERROR,
    CANCELLED
}
