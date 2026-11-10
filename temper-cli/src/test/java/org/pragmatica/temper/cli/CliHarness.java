package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.InstantSource;
import java.util.function.LongSupplier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.config.CliVersion;
import org.pragmatica.temper.cli.merge.MergePorts;

/// Runs whole CLI invocations in-process: a temporary HOME with `~/.config/temper/token`, an environment
/// pointing `TEMPER_URL` at the fake controller, fake time, and captured stdout/stderr.
final class CliHarness {
    static final String TOKEN = "s3cret-token";

    record Run(int exit, List<String> out, String err) {}

    private final Path home;
    private final Map<String, String> environment = new HashMap<>();
    final FakeTime time = new FakeTime();
    private Result<String> version = CliVersion.bundled();
    private MergePorts ports = MergePorts.production();

    private CliHarness(Path home) {
        this.home = home;
    }

    /// HOME with a 0600 token file and `TEMPER_OWNER=tester`; no controller URL yet.
    static CliHarness withToken(Path home, String token) throws IOException {
        var dir = Files.createDirectories(home.resolve(".config/temper"));
        var file = Files.writeString(dir.resolve("token"), token + "\n");

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

        return new CliHarness(home).env("TEMPER_OWNER", "tester");
    }

    static CliHarness against(Path home, FakeController controller) throws IOException {
        return withToken(home, TOKEN).env("TEMPER_URL", controller.url());
    }

    /// Plays a CLI of another build (D-6): its own build version, instead of this jar's.
    CliHarness version(String buildVersion) {
        version = Result.success(buildVersion);
        return this;
    }

    /// The T2 verbs' git base, runner, gh port and budget.
    CliHarness ports(MergePorts mergePorts) {
        ports = mergePorts;
        return this;
    }

    CliHarness env(String name, String value) {
        environment.put(name, value);
        return this;
    }

    Path home() {
        return home;
    }

    Run run(String... args) {
        return run(CapturedOutput.capturing(), args);
    }

    /// A run whose clock is `clock` (pauses still go to the fake time).
    Run runAt(InstantSource clock, String... args) {
        return run(CapturedOutput.capturing(), clock, args);
    }

    Run run(CapturedOutput out, String... args) {
        return run(out, time, args);
    }

    /// A run timed on the wall: the clock and the monotonic counter `events` measures its deadlines on are real (pauses
    /// still go to the fake time), for a bound that real network calls take part in.
    Run runOnTheWall(String... args) {
        return run(CapturedOutput.capturing(), InstantSource.system(), System::nanoTime, args);
    }

    private Run run(CapturedOutput out, InstantSource clock, String... args) {
        return run(out, clock, time::nanos, args);
    }

    private Run run(CapturedOutput out, InstantSource clock, LongSupplier monotonic, String... args) {
        var err = CapturedOutput.capturing();
        var context = CliContext.cliContext(name -> Option.option(environment.get(name)),
                                            home,
                                            clock,
                                            time,
                                            Console.console(out.printStream(), err.printStream()),
                                            version,
                                            monotonic);
        var exit = Cli.run(args, context, ports);

        return new Run(exit, out.lines(), err.text());
    }
}
