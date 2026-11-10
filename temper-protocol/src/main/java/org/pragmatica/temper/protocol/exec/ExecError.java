package org.pragmatica.temper.protocol.exec;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;


/// Process execution failures.
public sealed interface ExecError extends Cause {
    enum General implements ExecError {
        EMPTY_ARGV("A command needs at least the executable");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// The process could not be started (missing executable, bad directory); the JDK's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record StartFailed(String executable, Cause origin, String message) implements ExecError, Cause.Wrapped {
        public static final Fn2<StartFailed, String, Cause> FACTORY = Causes.forTwoValues("Failed to start %s",
                                                                                          StartFailed::new);
    }

    /// Reading the process output or waiting for it failed.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Io(String executable, Cause origin, String message) implements ExecError, Cause.Wrapped {
        public static final Fn2<Io, String, Cause> FACTORY = Causes.forTwoValues("I/O failed while running %s", Io::new);
    }

    /// The process exited but its output could not be collected: the pipes stayed open past the grace period
    /// (typically a background child inherited them; the streams are then closed), or reading them failed.
    /// The origin says which.
    @SuppressWarnings("JBCT-CAUSE-04")
    record OutputNotCollected(String executable, Cause origin, String message) implements ExecError, Cause.Wrapped {
        public static final Fn2<OutputNotCollected, String, Cause> FACTORY = Causes.forTwoValues("Output of %s could not be collected after exit",
                                                                                                 OutputNotCollected::new);
    }

    /// The process outlived its timeout and was destroyed (with its descendants).
    record TimedOut(String executable, TimeSpan timeout, String message) implements ExecError {
        public static final Fn2<TimedOut, String, TimeSpan> FACTORY = Causes.forTwoValues("%s timed out after %s",
                                                                                          TimedOut::new);
    }

    /// The process exited with a non-zero code (see `Completed.requireZero`).
    /// `stderrTail` is data only: it is not formatted into the message, which reaches logs and clients
    /// (git stderr can echo credential URLs).
    @SuppressWarnings("JBCT-CAUSE-04")
    record NonZero(String executable, int rc, String stderrTail, String message) implements ExecError {
        public static final Fn3<NonZero, String, Integer, String> FACTORY = Causes.forThreeValues("%s exited %d",
                                                                                                  NonZero::new);
    }
}
