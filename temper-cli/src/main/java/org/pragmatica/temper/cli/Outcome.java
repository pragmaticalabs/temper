package org.pragmatica.temper.cli;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// What a finished command prints on stdout and how the process exits.
public record Outcome(Option<String> output, ExitCode exit) {
    public static Outcome outcome(String output, ExitCode exit) {
        return new Outcome(some(output), exit);
    }

    /// A command that already printed everything it had to say (`watch`).
    public static Outcome outcome(ExitCode exit) {
        return new Outcome(none(), exit);
    }
}
