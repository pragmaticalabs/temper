package org.pragmatica.temper.controller.evidence;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Why an evidence pull, a `result.json` write or a `pg_dump` did not produce its file (m1-design §3). None of them
/// changes a verdict: a missing small tarball makes the result record say `evidence: {stored: false}`, a missing
/// `result.json` is written by the next sweep, and a failed dump is a `maintenance_runs` row with `ok = false`.
public sealed interface EvidenceError extends Cause {
    /// `worker-launch evidence` exited non-zero: 64 refused the arguments or the path, 66 found no unit directory.
    /// Both are answers about the request, so retrying cannot change them. The puller logs ssh's stderr; it is not
    /// part of the message, which a client may be shown.
    record Refused(String target, int rc, String message) implements EvidenceError {
        public static final Fn2<Refused, String, Integer> FACTORY = Causes.forTwoValues("Pull of %s exited %d",
                                                                                        Refused::new);

        @Override
        public boolean isTerminal() {
            return rc == REFUSED || rc == MISSING;
        }
    }

    /// The tarball grew past the configured cap and the transfer was killed; a retry would hit the same cap.
    record CapExceeded(String target, long cap, String message) implements EvidenceError, Cause.Terminal {
        public static final Fn2<CapExceeded, String, Long> FACTORY = Causes.forTwoValues("Pull of %s exceeded %d bytes",
                                                                                         CapExceeded::new);
    }

    /// The transferred bytes do not start with the gzip magic (`1f 8b`).
    record NotGzip(String target, String message) implements EvidenceError {
        public static final Fn1<NotGzip, String> FACTORY = Causes.forOneValue("Pull of %s did not produce a gzip stream",
                                                                              NotGzip::new);
    }

    /// The transfer did not end in time, or the ssh process could not be started or waited for.
    @SuppressWarnings("JBCT-CAUSE-04")
    record TransferFailed(String target, Cause origin, String message) implements EvidenceError, Cause.Wrapped {
        public static final Fn2<TransferFailed, String, Cause> FACTORY = Causes.forTwoValues("Transfer of %s failed",
                                                                                             TransferFailed::new);
    }

    /// A local file operation failed (create, force, hash, rename, read).
    @SuppressWarnings("JBCT-CAUSE-04")
    record Io(String operation, Cause origin, String message) implements EvidenceError, Cause.Wrapped {
        public static final Fn2<Io, String, Cause> FACTORY = Causes.forTwoValues("Evidence file operation failed: %s",
                                                                                 Io::new);
    }

    /// A `pg_dump` step failed: the dump itself, or its `pg_restore --list` verification.
    @SuppressWarnings("JBCT-CAUSE-04")
    record DumpFailed(String step, Cause origin, String message) implements EvidenceError, Cause.Wrapped {
        public static final Fn2<DumpFailed, String, Cause> FACTORY = Causes.forTwoValues("pg_dump step %s failed",
                                                                                         DumpFailed::new);
    }

    /// `pg_restore --list` read the dump but found no table of contents entry.
    record EmptyDump(String file, String message) implements EvidenceError {
        public static final Fn1<EmptyDump, String> FACTORY = Causes.forOneValue("pg_dump %s has an empty table of contents",
                                                                                EmptyDump::new);
    }

    /// `worker-launch` exit codes (m1-design §3.2).
    int REFUSED = 64;
    int MISSING = 66;
}
