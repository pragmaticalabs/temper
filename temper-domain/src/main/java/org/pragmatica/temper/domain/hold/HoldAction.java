package org.pragmatica.temper.domain.hold;

/// What one monitoring round of a held unit decides (m1-design §1.4). `VOID_PORT_GROUP_LOST`: record
/// `.temper/void = port-group-lost`, then kill the container; the normal exit path reports `VOID(port-group-lost)`.
public enum HoldAction {
    NONE,
    VOID_PORT_GROUP_LOST
}
