package org.pragmatica.temper.controller.evidence;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.store.api.StoredEvidence;


/// Pulls one tarball of a unit from its host into the evidence store (m1-design §3.2): the file is published only
/// complete (verified, forced, renamed atomically), so a path in a [StoredEvidence] always names a whole tarball.
@FunctionalInterface
public interface EvidencePuller {
    /// `unitAttempt` names the attempt the tarball belongs to; the store keeps each attempt's under its own directory
    /// (m4-design §4.6), while the host's `evidence` verb is unchanged (only the current attempt's directory is there).
    Promise<StoredEvidence> pull(ControllerConfig.Host host, UnitRef ref, int unitAttempt, EvidenceKind kind);
}
