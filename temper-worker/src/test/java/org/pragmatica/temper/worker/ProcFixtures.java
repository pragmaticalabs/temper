package org.pragmatica.temper.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// A fake `/proc` tree for the bb-hold holder identity (pid, start ticks, cmdline).
public final class ProcFixtures {
    private ProcFixtures() {}

    /// A `/proc/<pid>/stat` line whose command name holds spaces and parentheses, with `ticks` in field 22.
    public static String stat(long pid, long ticks) {
        return pid + " (bash (x) y) S 1 " + pid + " " + pid + " 0 -1 4194560 100 0 0 0 1 2 0 0 20 0 1 0 " + ticks + " 1000 25 18446744073709551615\n";
    }

    /// bb-hold's cmdline after its re-exec, NUL-separated.
    public static byte[] cmdline(String container) {
        return String.join("\0",
                           "/usr/bin/bash",
                           "/home/builder/tools/bb-hold",
                           "__hold",
                           "temper",
                           "5",
                           container,
                           "bb-build",
                           "__run",
                           "1760-4242",
                           "--",
                           "docker",
                           "run",
                           "-d")
                     .getBytes(StandardCharsets.UTF_8);
    }

    /// `<proc>/<pid>/{stat,cmdline}`.
    public static void process(Path proc, long pid, long ticks, byte[] cmdline) throws IOException {
        var directory = Files.createDirectories(proc.resolve(Long.toString(pid)));

        Files.writeString(directory.resolve("stat"), stat(pid, ticks));
        Files.write(directory.resolve("cmdline"), cmdline);
    }

    /// A live bb-hold holder of `container` at `<proc>/<pid>`.
    public static void holder(Path proc, long pid, long ticks, String container) throws IOException {
        process(proc, pid, ticks, cmdline(container));
    }
}
