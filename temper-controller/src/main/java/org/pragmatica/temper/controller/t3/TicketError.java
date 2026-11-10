package org.pragmatica.temper.controller.t3;

import org.pragmatica.lang.Cause;


/// Why a collection of one host failed. The message IS the `bb_collect.last_error` text.
public sealed interface TicketError extends Cause {
    /// ssh itself failed (exit 255): `ssh exit <n>: <first stderr line>`.
    record SshExit(int rc, String line, String message) implements TicketError {
        public static SshExit sshExit(int rc, String line) {
            return new SshExit(rc, line, "ssh exit " + rc + ": " + line);
        }
    }

    /// The command ran and exited non-zero (for example 69, `bb-tickets not installed`): `exit <n>`.
    record Exit(int rc, String message) implements TicketError {
        public static Exit exit(int rc) {
            return new Exit(rc, "exit " + rc);
        }
    }

    /// No complete reply within the read bound.
    enum Timeout implements TicketError {
        TIMEOUT("timeout");
        private final String message;
        Timeout(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// More than 8 MiB of stdout.
    enum Oversize implements TicketError {
        OVERSIZE("oversize");
        private final String message;
        Oversize(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// No trailer, or a trailer whose `count` is not the number of ticket lines.
    enum Truncated implements TicketError {
        TRUNCATED("truncated");
        private final String message;
        Truncated(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// Line `line` (1-based) is not a valid ticket line or trailer.
    record Malformed(int line, String message) implements TicketError {
        public static Malformed malformed(int line) {
            return new Malformed(line, "malformed line " + line);
        }
    }

    /// The command could not be started: counted as an exit, since nothing ran (127, the shell's "not found").
    record Unstartable(String reason, String message) implements TicketError {
        public static Unstartable unstartable(String reason) {
            return new Unstartable(reason, "exit 127: " + reason);
        }
    }
}
