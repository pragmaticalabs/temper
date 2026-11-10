package org.pragmatica.temper.cli;

import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.cli.config.CliVersion;

import static org.pragmatica.lang.Option.option;


/// Everything the CLI reads from or writes to its process surroundings, so an invocation can run in-process
/// in tests: environment variables, the home directory (for `~/.config/temper`), time, pauses, output, and its own
/// build version (D-6, [CliVersion#bundled]; a test plays a CLI of another build through it, never a user).
/// `monotonic` is a nanosecond counter that never steps (`System.nanoTime`): `events` measures its 110 s heartbeat
/// clock and `--timeout` on it, so a wall-clock step cannot stretch or cut them.
public record CliContext(Fn1<Option<String>, String> environment,
                         Path home,
                         InstantSource clock,
                         Pacer pacer,
                         Console console,
                         Result<String> version,
                         LongSupplier monotonic) {
    /// With this jar's own build version.
    public static CliContext cliContext(Fn1<Option<String>, String> environment,
                                        Path home,
                                        InstantSource clock,
                                        Pacer pacer,
                                        Console console) {
        return cliContext(environment, home, clock, pacer, console, CliVersion.bundled());
    }

    public static CliContext cliContext(Fn1<Option<String>, String> environment,
                                        Path home,
                                        InstantSource clock,
                                        Pacer pacer,
                                        Console console,
                                        Result<String> version) {
        return cliContext(environment, home, clock, pacer, console, version, System::nanoTime);
    }

    public static CliContext cliContext(Fn1<Option<String>, String> environment,
                                        Path home,
                                        InstantSource clock,
                                        Pacer pacer,
                                        Console console,
                                        Result<String> version,
                                        LongSupplier monotonic) {
        return new CliContext(environment, home, clock, pacer, console, version, monotonic);
    }

    /// [#monotonic] as an [InstantSource]: its instants mean nothing on their own (they count from an arbitrary
    /// origin); only the durations between them do.
    public InstantSource monotonicClock() {
        return () -> Instant.EPOCH.plusNanos(monotonic.getAsLong());
    }

    public static CliContext cliContext() {
        return cliContext(name -> option(System.getenv(name)),
                          userHome(),
                          InstantSource.system(),
                          Pacer.pacer(),
                          Console.console());
    }

    /// `$HOME` as the shell sets it (so `HOME=/tmp/x temper …` works as for any CLI), else the JVM's view.
    private static Path userHome() {
        return option(System.getenv("HOME")).filter(Verify.Is::notBlank)
                     .map(Path::of)
                     .or(() -> Path.of(System.getProperty("user.home")));
    }

    /// A non-blank environment variable.
    public Option<String> env(String name) {
        return environment.apply(name)
                          .filter(Verify.Is::notBlank);
    }
}
