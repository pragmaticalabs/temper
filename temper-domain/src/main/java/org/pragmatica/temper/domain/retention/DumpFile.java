package org.pragmatica.temper.domain.retention;

import java.time.Instant;


/// One verified `pg_dump` file in the evidence store (`pgdump/temper-<yyyymmddThhmmssZ>.dump`): only a dump whose
/// `pg_restore --list` passed is renamed into place (m1-design §3.4), so a `.partial` file is never one.
public record DumpFile(String name, Instant takenAt) {
    public static DumpFile dumpFile(String name, Instant takenAt) {
        return new DumpFile(name, takenAt);
    }
}
