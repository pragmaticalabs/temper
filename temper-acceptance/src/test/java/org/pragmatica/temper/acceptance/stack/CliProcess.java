package org.pragmatica.temper.acceptance.stack;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/// One `temper` CLI process (the shaded jar, as the acceptance stack runs it) whose output is read while it runs:
/// `events --follow` never exits by itself, and a test acts on what an `events --until` has printed so far. Only this
/// process is ever destroyed (its own handle; no pattern kill).
final class CliProcess implements AutoCloseable {
    private final Process process;
    private final Instant launched;
    private final List<String> out = new CopyOnWriteArrayList<>();
    private final List<String> err = new CopyOnWriteArrayList<>();
    private final Thread outReader;
    private final Thread errReader;

    private CliProcess(Process process, Instant launched) {
        this.process = process;
        this.launched = launched;
        this.outReader = Thread.ofPlatform().daemon().start(() -> drain(process.getInputStream(), out));
        this.errReader = Thread.ofPlatform().daemon().start(() -> drain(process.getErrorStream(), err));
    }

    /// `java -jar <temper.cli.jar> <args>` with `HOME` = `cliHome` (its token file) and `TEMPER_URL` = `url`.
    static CliProcess launch(Path cliHome, String url, String... args) throws IOException {
        var argv = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                           "-jar",
                                           System.getProperty("temper.cli.jar")));
        argv.addAll(List.of(args));
        var builder = HermeticProcess.builder(argv);
        builder.environment().put("HOME", cliHome.toString());
        builder.environment().put("TEMPER_URL", url);
        builder.environment().put("TEMPER_OWNER", "acceptance");
        var launched = Instant.now();
        return new CliProcess(builder.start(), launched);
    }

    Instant launched() {
        return launched;
    }

    List<String> out() {
        return List.copyOf(out);
    }

    List<String> err() {
        return List.copyOf(err);
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /// Waits until some stdout line satisfies `wanted`; fails after `bound` with what was printed.
    void awaitOut(Predicate<List<String>> wanted, Duration bound, String what) throws InterruptedException {
        awaitLines(out, wanted, bound, what);
    }

    void awaitErr(Predicate<List<String>> wanted, Duration bound, String what) throws InterruptedException {
        awaitLines(err, wanted, bound, what);
    }

    private void awaitLines(List<String> lines, Predicate<List<String>> wanted, Duration bound, String what)
        throws InterruptedException {
        var deadline = Instant.now().plus(bound);
        while (!wanted.test(List.copyOf(lines))) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("CLI: no " + what + " within " + bound + "; alive=" + process.isAlive()
                                         + "\nstdout=" + out + "\nstderr=" + err);
            }
            Thread.sleep(100);
        }
    }

    /// The exit code once the process ended within `bound` (its output fully read); fails, killing it, otherwise.
    int exit(Duration bound) throws InterruptedException {
        if (!process.waitFor(bound.toMillis(), TimeUnit.MILLISECONDS)) {
            close();
            throw new AssertionError("CLI still running after " + bound + "\nstdout=" + out + "\nstderr=" + err);
        }
        outReader.join(Duration.ofSeconds(10));
        errReader.join(Duration.ofSeconds(10));
        return process.exitValue();
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
            outReader.join(Duration.ofSeconds(10));
            errReader.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void drain(InputStream stream, List<String> lines) {
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.lines().forEach(lines::add);
        } catch (IOException ignored) {
            // the process was destroyed: what was read stays
        }
    }
}
