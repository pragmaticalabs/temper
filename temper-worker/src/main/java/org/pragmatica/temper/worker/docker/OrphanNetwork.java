package org.pragmatica.temper.worker.docker;

/// A temper network (label `temper.job`) that held no container when it was listed, with its `temper.job` and
/// `temper.unit` labels (the unit is empty when the network carries none).
public record OrphanNetwork(String name, String job, String unit) {
    public static OrphanNetwork orphanNetwork(String name, String job, String unit) {
        return new OrphanNetwork(name, job, unit);
    }
}
