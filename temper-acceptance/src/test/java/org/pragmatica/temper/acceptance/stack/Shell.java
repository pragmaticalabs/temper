package org.pragmatica.temper.acceptance.stack;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// Small blocking process helpers for the acceptance tests.
public final class Shell {
    private Shell() {}

    public record Run(int exit, String out) {}

    /// Runs `argv` (stderr merged into stdout) for at most `seconds`.
    public static Run run(long seconds, String... argv) throws IOException, InterruptedException {
        var process = HermeticProcess.builder(List.of(argv)).redirectErrorStream(true).start();
        process.getOutputStream().close();
        byte[] bytes;
        try (var in = process.getInputStream()) {
            bytes = in.readAllBytes();
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(String.join(" ", argv) + " timed out");
        }
        return new Run(process.exitValue(), new String(bytes, StandardCharsets.UTF_8).strip());
    }

    /// Runs `argv` and answers its output; a non-zero exit fails the test.
    public static String ok(String... argv) throws IOException, InterruptedException {
        var run = run(600, argv);
        if (run.exit() != 0) {
            throw new AssertionError(String.join(" ", argv) + " exited " + run.exit() + ": " + run.out());
        }
        return run.out();
    }
}
