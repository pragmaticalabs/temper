package org.pragmatica.temper.domain.manifest;

/// Where a compiled class is declared, from its own `InnerClasses` entry. JUnit runs a top-level or static
/// member class as a test class, an inner (non-static member) class only when it is `@Nested`, and never a
/// local or anonymous class.
public enum Nesting {
    TOP_LEVEL,
    STATIC_MEMBER,
    INNER,
    LOCAL
}
