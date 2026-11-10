package org.pragmatica.temper.protocol.frame;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// Failures of the length-prefixed frame stream. Each one means the stream can no longer be trusted, so the
/// link is dropped and re-established.
public sealed interface FrameError extends Cause {
    enum General implements FrameError {
        TRUNCATED("End of stream inside a frame");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// A length prefix outside `1..MAX_FRAME`.
    record BadLength(Long length, String message) implements FrameError {
        public static final Fn1<BadLength, Long> FACTORY = Causes.forOneValue("Frame length %d is outside 1..16 MiB",
                                                                              BadLength::new);
    }

    /// The underlying stream failed.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Io(Cause origin, String message) implements FrameError, Cause.Wrapped {
        public static final Fn1<Io, Cause> FACTORY = Causes.forOneValue("Frame stream I/O failed", Io::new);
    }
}
