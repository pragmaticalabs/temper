package org.pragmatica.temper.domain.refusal;

/// Codes of the refusals temper implements in code, with their wire form (spec §2.5, m0-design §3.4).
/// Data-driven refusals (`refusals` table rows) carry their code as data; R-1553 and R-FAILSAFE are the
/// seeded rows and are listed here only so callers can name them.
public enum RefusalCode {
    R_1553("R-1553"),
    R_FAILSAFE("R-FAILSAFE"),
    R_ENV("R-ENV"),
    R_M2("R-M2"),
    R_SHELL("R-SHELL"),
    /// An `Mvn` step with a phase, goal or profile off the build-only allowlist (m4-design §5.3, rulings R-M4-14, Q-M4-11).
    R_EFFECT("R-EFFECT"),
    R_DISK("R-DISK"),
    SOURCE("source"),
    PORT_GROUP("port-group"),
    UNSUPPORTED("unsupported"),
    /// A SHA contained only in fork pull-request heads (spec §13, R-M2-7).
    FORK_PR("fork-pr"),
    /// A pull request's origin could not be established (GitHub API down, rate-limited, unreadable); a resubmit may
    /// succeed (R-M2-7, fail closed).
    PR_ORIGIN_UNVERIFIED("pr-origin-unverified"),
    /// A rerun request whose parent fails a precondition (m2-design §5.1).
    RERUN("rerun");
    private final String wire;
    RefusalCode(String wire) {
        this.wire = wire;
    }
    /// The code as it appears in `REFUSED(<code>)`, the 422 body and `jobs.refusal_code`.
    public String wire() {
        return wire;
    }
}
