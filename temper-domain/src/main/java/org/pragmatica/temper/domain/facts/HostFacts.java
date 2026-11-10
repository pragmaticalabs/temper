package org.pragmatica.temper.domain.facts;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;


/// Contention facts recorded at unit start and end (spec §4.5). `bbRunning`/`bbWaiting` are the bb-* tickets
/// `bb-queue` lists as running and waiting, absent when its output could not be read (Q-10, m0-design §12.7);
/// `notes` say why a fact is absent. `foreignReactors` excludes processes inside temper containers (spec §6.1).
public record HostFacts(double load1,
                        double load5,
                        int cores,
                        int foreignReactors,
                        int temperUnitsRunning,
                        Option<Integer> bbRunning,
                        Option<Integer> bbWaiting,
                        long memAvailableKb,
                        long freeDockerRootBytes,
                        long freeWorkspaceBytes,
                        Instant at,
                        List<String> notes) {
    public HostFacts {
        notes = List.copyOf(notes);
    }

    public static HostFacts hostFacts(double load1,
                                      double load5,
                                      int cores,
                                      int foreignReactors,
                                      int temperUnitsRunning,
                                      Option<Integer> bbRunning,
                                      Option<Integer> bbWaiting,
                                      long memAvailableKb,
                                      long freeDockerRootBytes,
                                      long freeWorkspaceBytes,
                                      Instant at,
                                      List<String> notes) {
        return new HostFacts(load1,
                             load5,
                             cores,
                             foreignReactors,
                             temperUnitsRunning,
                             bbRunning,
                             bbWaiting,
                             memAvailableKb,
                             freeDockerRootBytes,
                             freeWorkspaceBytes,
                             at,
                             notes);
    }
}
