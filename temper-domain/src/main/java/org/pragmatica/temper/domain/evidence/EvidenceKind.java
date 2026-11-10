package org.pragmatica.temper.domain.evidence;

import java.util.Arrays;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// The two evidence tarballs of a unit (m1-design §3.1): `small` is `.temper/*`, the report XML, thread dumps,
/// the manifest and the log tail, kept 90 days; `full` is the whole unit directory, kept 14 days. The wire form
/// is the `worker-launch evidence` verb's argument and the `evidence.kind` column.
public enum EvidenceKind {
    SMALL("small"),
    FULL("full");
    private final String wire;
    EvidenceKind(String wire) {
        this.wire = wire;
    }
    public String wire() {
        return wire;
    }
    public static Result<EvidenceKind> evidenceKind(String wire) {
        return Option.from(Arrays.stream(values()).filter(kind -> kind.wire.equals(wire)).findFirst()).toResult(invalid("evidence kind").apply(wire));
    }
}
