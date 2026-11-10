package org.pragmatica.kiln.service.scan;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Why a `kiln-scan` verb gave no listing (kiln-f1-design §3.4): each is a failed promise, never an empty listing.
public sealed interface ScanTransportError extends Cause {
    /// The script could not be started, fed, read or waited for (a timeout among them); the origin says which.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Failed(String verb, Cause origin, String message) implements ScanTransportError, Cause.Wrapped {
        public static final Fn2<Failed, String, Cause> FACTORY = Causes.forTwoValues("kiln-scan %s could not be run",
                                                                                     Failed::new);
    }

    /// The script exited non-zero (64 is a refused request). `stderrTail` is data only, as in `ExecError.NonZero`.
    @SuppressWarnings("JBCT-CAUSE-04")
    record NonZero(String verb, int rc, String stderrTail, String message) implements ScanTransportError {
        public static final Fn3<NonZero, String, Integer, String> FACTORY = Causes.forThreeValues("kiln-scan %s exited %d",
                                                                                                  NonZero::new);
    }

    /// The script exited 0 and printed nothing: not even a trailer, so not an empty listing.
    record NoLines(String verb, String message) implements ScanTransportError {
        public static final Fn1<NoLines, String> FACTORY = Causes.forOneValue("kiln-scan %s answered nothing",
                                                                              NoLines::new);
    }

    /// A stream of the process passed the cap ([ScanExec#MAX_BYTES]): the process was killed and nothing is read.
    @SuppressWarnings("JBCT-CAUSE-04")
    record TooLarge(String verb, int maxBytes, String message) implements ScanTransportError {
        public static final Fn2<TooLarge, String, Integer> FACTORY = Causes.forTwoValues("kiln-scan %s answered over %d bytes",
                                                                                         TooLarge::new);
    }

    /// A request word kiln-scan's single-space split cannot carry (whitespace inside it); the request is never sent.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Uncarried(String verb, String word, String message) implements ScanTransportError {
        public static final Fn2<Uncarried, String, String> FACTORY = Causes.forTwoValues("kiln-scan %s cannot carry the word '%s'",
                                                                                         Uncarried::new);
    }

    /// A `reach` or `size` answer that does not read as its verb's contract: it answers nothing.
    @SuppressWarnings("JBCT-CAUSE-04")
    record BadAnswer(String verb, String detail, String message) implements ScanTransportError {
        public static final Fn2<BadAnswer, String, String> FACTORY = Causes.forTwoValues("kiln-scan %s answered badly: %s",
                                                                                         BadAnswer::new);
    }
}
