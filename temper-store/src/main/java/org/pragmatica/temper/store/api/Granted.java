package org.pragmatica.temper.store.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitRef;


/// A unit just granted to a host: everything `PrepareJob` and `StartUnit` need. `needsPortGroup` and `priority`
/// build `StartUnit.portGroup` (bb-hold's `<prio>` is the job priority, Q-M1-12); `manifest` is the unit's
/// manifest spec as planned (`StartUnit.manifest`, m1-design §2.2). `grantedAt` is the grant's time as the store wrote
/// it: it names this attempt of the unit (a requeued unit is granted again at another time). `attempt` is its number (m4-design §4.1):
/// every grant increments `units.attempt`, so a write scoped to it is never applied to a later attempt
/// ([UnitStore#startUnknown], [UnitStore#complete]).
public record Granted(UnitRef ref,
                      long unitId,
                      UnitKind kind,
                      List<List<String>> steps,
                      Duration timeout,
                      int memoryMb,
                      Sha sha,
                      boolean needsPortGroup,
                      int priority,
                      Option<ManifestSpec> manifest,
                      Instant grantedAt,
                      int attempt) {
    public Granted {
        steps = steps.stream().map(List::copyOf).toList();
    }

    public static Granted granted(UnitRef ref,
                                  long unitId,
                                  UnitKind kind,
                                  List<List<String>> steps,
                                  Duration timeout,
                                  int memoryMb,
                                  Sha sha,
                                  boolean needsPortGroup,
                                  int priority,
                                  Option<ManifestSpec> manifest,
                                  Instant grantedAt,
                                  int attempt) {
        return new Granted(ref,
                           unitId,
                           kind,
                           steps,
                           timeout,
                           memoryMb,
                           sha,
                           needsPortGroup,
                           priority,
                           manifest,
                           grantedAt,
                           attempt);
    }
}
