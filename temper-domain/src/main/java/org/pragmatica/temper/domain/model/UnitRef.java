package org.pragmatica.temper.domain.model;

/// A unit addressed by its job; also names the unit's docker container and network.
public record UnitRef(JobId job, UnitName unit) {
    public static UnitRef unitRef(JobId job, UnitName unit) {
        return new UnitRef(job, unit);
    }

    /// Docker container name `temper-<job>-<unit>`.
    public String containerName() {
        return "temper-" + job.value() + "-" + unit.value();
    }

    /// Per-unit docker network name; identical to the container name by design (m0-design §3.1).
    public String networkName() {
        return containerName();
    }
}
