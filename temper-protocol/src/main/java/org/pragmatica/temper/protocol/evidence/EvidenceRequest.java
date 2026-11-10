package org.pragmatica.temper.protocol.evidence;

import java.util.List;

import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.UnitRef;


/// An evidence transfer (m1-design §3.2, maintainer ruling M1-2): the controller opens a second ssh session whose
/// forced command, `worker-launch`, runs `evidence <job> <unit> small|full` and writes the tarball to stdout.
/// This record is the one place the verb's argument vector is built. A [UnitRef] built through the validating
/// factories (`JobId.jobId`, `UnitName.unitName`) yields arguments of the shapes `worker-launch` accepts
/// (`<job>` `^[1-9][0-9]{0,18}$`, `<unit>` `^[a-z0-9][a-z0-9-]{0,31}$`), but the public record constructors skip
/// that validation, so nothing here guarantees the shapes. The guard is `worker-launch` itself (WP4, m1-design
/// §3.2): it validates the argument vector and refuses anything else with exit 64.
public record EvidenceRequest(UnitRef ref, EvidenceKind kind) {
    /// The `worker-launch` verb.
    public static final String VERB = "evidence";

    public static EvidenceRequest evidenceRequest(UnitRef ref, EvidenceKind kind) {
        return new EvidenceRequest(ref, kind);
    }

    /// `evidence <job> <unit> <kind>`: the arguments of the ssh command after the host.
    public List<String> verbArgs() {
        return List.of(VERB,
                       String.valueOf(ref.job().value()),
                       ref.unit().value(),
                       kind.wire());
    }
}
