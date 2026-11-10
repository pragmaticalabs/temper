package org.pragmatica.temper.domain.manifest;

/// The furthest test-bearing lifecycle phase a unit's main step reaches (m1-design §2.2). `TEST` for a
/// `test` build; `INTEGRATION_TEST` for `verify` and `install`, which also run every phase up to `verify`.
public enum Reach {
    TEST,
    INTEGRATION_TEST
}
