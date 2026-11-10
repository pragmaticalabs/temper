package org.pragmatica.temper.cli;

import org.pragmatica.lang.Contract;


/// `temper` entry point (fat jar main class; `ops/temper` wraps it).
public sealed interface Main {
    /// The JDK's entry-point signature; the exit code is the whole contract of the process (spec §8.1).
    @Contract
    static void main(String[] args) {
        System.exit(Cli.run(args, CliContext.cliContext()));
    }

    record unused() implements Main {}
}
