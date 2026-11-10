package org.pragmatica.temper.domain.model;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.ManifestSpec;


/// The planner's output for one unit; immutable. `steps` are complete argv arrays (no shell, R-SHELL),
/// each ending with the planner's forced `-Dmaven.repo.local` token (see `Planner.FORCED_REPO_LOCAL`).
/// `manifest` is what the worker needs to compute the unit's expected test classes (m1-design §2.2); it is
/// absent for a unit without coverage (an `Mvn` recipe, Q-M1-8). `sideEffects` is the recipe's (m4-design §5.1),
/// written to `units.side_effects`, where a host-loss settle reads it.
public record UnitSpec(UnitName name,
                       UnitKind kind,
                       List<List<String>> steps,
                       boolean requestsTests,
                       Duration timeout,
                       MemoryMb memory,
                       boolean needsPortGroup,
                       Option<ManifestSpec> manifest,
                       SideEffects sideEffects) {
    public UnitSpec {
        steps = steps.stream().map(List::copyOf).toList();
    }

    public static UnitSpec unitSpec(UnitName name,
                                    UnitKind kind,
                                    List<List<String>> steps,
                                    boolean requestsTests,
                                    Duration timeout,
                                    MemoryMb memory,
                                    boolean needsPortGroup,
                                    Option<ManifestSpec> manifest,
                                    SideEffects sideEffects) {
        return new UnitSpec(name, kind, steps, requestsTests, timeout, memory, needsPortGroup, manifest, sideEffects);
    }

    /// Every argv the unit runs: its steps, then the effective-POM step when it has a manifest. R-M2 and
    /// R-SHELL judge all of them (m1-design §2.2).
    public List<List<String>> argvs() {
        return Stream.concat(steps.stream(),
                             manifest.map(ManifestSpec::pomArgv).stream())
                     .toList();
    }
}
