package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// What the T3 script ITs share: the repo's script paths, executable stubs, a copy of `/bin/bash` named `claude` (the
/// session ancestor), and a process runner that keeps stdout and stderr apart and starts from an empty environment.
final class MacScripts {
    static final Path MAC = Path.of("..", "ops", "mac", "temper-mac").toAbsolutePath().normalize();
    static final Path SUBMIT = Path.of("..", "ops", "mac", "bb-submit").toAbsolutePath().normalize();
    static final Path PLIST = Path.of("..", "ops", "mac", "com.pragmatica.temper-mac.plist").toAbsolutePath().normalize();
    static final Path BB_TEMPER = Path.of("..", "ops", "bb", "bb-temper").toAbsolutePath().normalize();

    /// `started` is the JVM's own reading of the process's start (an independent clock), null when it was not readable.
    record Result(int rc, String out, String err, Instant started, long pid) {}

    private MacScripts() {}

    static Path executable(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    /// A copy of `/bin/bash` named `claude`: its `comm` is `claude`, as a Claude Code session's is.
    static Path claude(Path dir) throws IOException {
        var copy = dir.resolve("claude");
        Files.createDirectories(dir);
        Files.copy(Path.of("/bin/bash"), copy, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rwxr-xr-x"));
        return copy;
    }

    /// `env` over an empty environment: only PATH and what the caller names reach the script.
    static Map<String, String> env(Map<String, String> extra) {
        var env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin:/bin");
        env.putAll(extra);
        return env;
    }

    static Result run(List<String> command, Map<String, String> env, Path dir) throws IOException, InterruptedException {
        var out = Files.createTempFile(dir, "out", ".txt");
        var err = Files.createTempFile(dir, "err", ".txt");
        var builder = builder(command, env, dir);
        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        var process = builder.start();
        var started = process.info().startInstant().orElse(null);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("timed out: " + command);
        }
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err), started, process.pid());
    }

    /// A background process (a stand-in session that outlives the call), output discarded.
    static Process background(List<String> command, Map<String, String> env, Path dir) throws IOException {
        var builder = builder(command, env, dir);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private static ProcessBuilder builder(List<String> command, Map<String, String> env, Path dir) {
        var builder = HermeticProcess.builder(new ArrayList<>(command));
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.directory(dir.toFile());
        return builder;
    }
}
