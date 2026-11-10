package org.pragmatica.temper.domain.plan;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;


/// What a recipe decides about its unit in [Planner]; the rules (tests, timeout, port group) derive the rest.
record UnitShape(UnitName name,
                 UnitKind kind,
                 List<List<String>> steps,
                 MemoryMb memory,
                 Option<ManifestSpec> manifest) {
    static UnitShape unitShape(UnitName name,
                               UnitKind kind,
                               List<List<String>> steps,
                               MemoryMb memory,
                               Option<ManifestSpec> manifest) {
        return new UnitShape(name, kind, steps, memory, manifest);
    }
}
