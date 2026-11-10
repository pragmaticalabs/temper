package org.pragmatica.kiln.core.scan;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Why a `kiln-scan` line does not decode, or a whole scan cannot vouch for its rows (kiln-f1-design §3.2). Any of them
/// makes the scan [ScanOutcome.Incomplete] (spec §5.2), so it never makes a checkout read as gone.
public sealed interface ScanError extends Cause {
    /// Trailer failures of a whole scan.
    enum General implements ScanError {
        NO_TRAILER("No trailer as the last line"),
        EARLY_TRAILER("A trailer before the last line");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// The line is not JSON.
    @SuppressWarnings("JBCT-CAUSE-04")
    record NotJson(Cause origin, String message) implements ScanError, Cause.Wrapped {
        public static final Fn1<NotJson, Cause> FACTORY = Causes.forOneValue("Scan line is not JSON", NotJson::new);
    }

    /// A required field is absent, null or of the wrong type.
    record BadField(String field, String message) implements ScanError {
        public static final Fn1<BadField, String> FACTORY = Causes.forOneValue("Scan line field missing or invalid: %s",
                                                                               BadField::new);
    }

    /// Line `line` (1-based) of a scan does not decode. The origin's text is part of the message on purpose: the
    /// message becomes the [ScanOutcome.Incomplete] reason a source shows, and that string is the only diagnosis of
    /// the scan that survives into it.
    record BadLine(int line, Cause origin, String message) implements ScanError, Cause.Wrapped {
        public static final Fn2<BadLine, Integer, Cause> FACTORY = (line, origin) -> new BadLine(line,
                                                                                                 origin,
                                                                                                 "Scan line " + line
                                                                                                + ": " + origin.message());
    }

    /// The trailer's `count` differs from the number of checkout and stub lines.
    record Miscount(int count, long listed, String message) implements ScanError {
        public static final Fn2<Miscount, Integer, Long> FACTORY = Causes.forTwoValues("Trailer count %d, %d checkout and stub lines",
                                                                                       Miscount::new);
    }

    /// The trailer's `nonGit` differs from the number of non-git lines.
    record NonGitMismatch(int nonGit, long listed, String message) implements ScanError {
        public static final Fn2<NonGitMismatch, Integer, Long> FACTORY = Causes.forTwoValues("Trailer nonGit %d, %d non-git lines",
                                                                                             NonGitMismatch::new);
    }

    /// Two checkout, stub or non-git lines name the same path: the count would vouch for a checkout the scan did not
    /// list, and a directory cannot be both a checkout and non-git.
    record DuplicatePath(CheckoutPath path, String message) implements ScanError {
        public static final Fn1<DuplicatePath, CheckoutPath> FACTORY = Causes.forOneValue("Scan lists path %s twice",
                                                                                          DuplicatePath::new);
    }

    /// A line that is neither a trailer nor of a known `kind`.
    record UnknownKind(String kind, String message) implements ScanError {
        public static final Fn1<UnknownKind, String> FACTORY = Causes.forOneValue("Scan line of unknown kind: '%s'",
                                                                                  UnknownKind::new);
    }

    /// The first failure inside `cause`. [org.pragmatica.lang.Result#all] gathers every failed field of a line into a
    /// composite; a line is reported by its first, so the reason a scan shows stays one line.
    static Cause first(Cause cause) {
        return cause.stream()
                    .findFirst()
                    .filter(inner -> inner != cause)
                    .map(ScanError::first)
                    .orElse(cause);
    }
}
