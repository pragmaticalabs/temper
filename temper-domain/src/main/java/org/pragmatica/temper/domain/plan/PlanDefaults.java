package org.pragmatica.temper.domain.plan;

import java.util.List;

import org.pragmatica.temper.domain.model.MemoryMb;


/// Controller configuration the planner needs: the unit memory limit, the Gate unit's memory limit
/// (Q-M1-13: 16 GiB until the sizing report exists) and the port-group module list (`[units] memoryMb`,
/// `[units] gateMemoryMb`, `[portGroup] modules`).
public record PlanDefaults(MemoryMb memory, MemoryMb gateMemory, List<String> portGroupModules) {
    public PlanDefaults {
        portGroupModules = List.copyOf(portGroupModules);
    }

    public static PlanDefaults planDefaults(MemoryMb memory, MemoryMb gateMemory, List<String> portGroupModules) {
        return new PlanDefaults(memory, gateMemory, portGroupModules);
    }
}
