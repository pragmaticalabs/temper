package org.pragmatica.temper.protocol.message;

/// A unit container's state as the worker sees it (`docker inspect -f {{.State.Status}}`, mapped).
public enum ContainerStatus {
    RUNNING,
    EXITED,
    ABSENT
}
