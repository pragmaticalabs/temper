package org.pragmatica.temper.worker.config;

/// The files of a unit's `.temper/` directory (spec §4.1 plus the m0-design §7.1 additions). Every one is
/// written to `<name>.tmp` and moved into place atomically.
public enum UnitFile {
    /// The steps as JSON (`List<List<String>>`).
    ARGS("args"),
    /// The unit's wall-clock bound, ISO-8601; rewritten by `Reattach` with the controller's authoritative value.
    TIMEOUT("timeout"),
    /// The unit's attempt (protocol v3, m4-design §4.2): `StartUnit.attempt` as text, written before [#MARKER], so any
    /// unit with a marker has an attempt; a unit directory with a marker and none was written before M4 (attempt 1).
    ATTEMPT("attempt"),
    /// Epoch nanoseconds as text, written before `docker run`; the report filter reads this TEXT (spec §4.4).
    MARKER("marker"),
    /// Epoch nanoseconds when `docker run` returned; the timeout deadline counts from here.
    START("start"),
    /// The container id.
    CONTAINER("container"),
    /// The runner image id.
    IMAGE("image"),
    /// The unit's `--network`: the per-unit network name, or `host` for a Gate unit.
    NETWORK("network"),
    /// The container's exit code. Written FIRST at exit: it is the re-attach discriminator.
    RC("rc"),
    /// Epoch nanoseconds when the exit was recorded.
    END("end"),
    /// `timeout` or `cancel`: why the worker killed the container. The first writer wins.
    KILLED("killed"),
    /// Host facts at unit start (JSON).
    FACTS_START("facts-start.json"),
    /// Unit end facts plus Testcontainers leftovers (JSON).
    FACTS_END("facts-end.json"),
    /// Testcontainers session ids present when the unit started, one per line (reap attribution).
    TC_SESSIONS_START("tc-sessions-start"),
    /// The `UnitExited` message as sent (JSON). Its presence means the exit was fully processed, so a later
    /// `Collect` resends instead of recomputing.
    EXITED("exited.json"),
    /// A hold unit's bb-hold identity, `<pid> <startTicks>` (`/proc/<pid>/stat` field 22), written right after the
    /// spawn (m1-design §1.2). Its presence, or that of [#BBHOLD_ERR], makes the unit a hold unit.
    BBHOLD_PID("bbhold.pid"),
    /// bb-hold's stdout: argv's own output, the container id of `docker run -d`.
    BBHOLD_OUT("bbhold.out"),
    /// bb-hold's stderr: every bb-hold message, `granted <ticket>` included (maintainer ruling M1-14).
    BBHOLD_ERR("bbhold.err"),
    /// The ticket of the `granted` line, written once the grant is seen.
    BBHOLD_TICKET("bbhold.ticket"),
    /// Why the worker voided the unit while it ran: `port-group-lost` or `disk` (`VoidReason.wire()`). The first
    /// writer wins.
    VOID("void"),
    /// The unit's `ManifestSpec` (JSON wire form), kept so a later worker can compute the manifest at exit.
    MANIFEST_SPEC("manifest-spec.json"),
    /// The lowest host `MemAvailable` (kB) sampled on the heartbeat while the unit ran (m1-design §4.5).
    MEM_AVAILABLE_MIN("mem-available-min-kb");
    private final String fileName;
    UnitFile(String fileName) {
        this.fileName = fileName;
    }
    public String fileName() {
        return fileName;
    }
}
