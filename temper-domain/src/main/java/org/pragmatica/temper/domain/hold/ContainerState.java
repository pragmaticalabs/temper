package org.pragmatica.temper.domain.hold;

/// A unit's container as the worker observes it: in one hold-monitoring round (m1-design §1.4), and in the `Hello`
/// disk scan reconcile reads (m0-design §7.2).
public enum ContainerState {
    RUNNING,
    EXITED,
    ABSENT
}
