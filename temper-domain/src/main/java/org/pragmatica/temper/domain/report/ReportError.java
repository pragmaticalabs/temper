package org.pragmatica.temper.domain.report;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Report parsing failures. Any of them makes the unit `ERROR(report)` (spec §5.3).
public sealed interface ReportError extends Cause {
    /// The XML is malformed, not UTF-8/XML, or carries an unparseable number; the parser's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Malformed(String fileName, Cause origin, String message) implements ReportError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<Malformed, String, Cause> FACTORY = Causes.forTwoValues("Malformed report %s",
                                                                                        Malformed::new);
    }
}
