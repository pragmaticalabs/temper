package org.pragmatica.temper.protocol.exec;

import java.nio.charset.StandardCharsets;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// A process that ran to completion: exit code and captured output. `truncated` means a stream exceeded
/// `Command.outputLimit` and only its first bytes were kept.
///
/// The output arrays are exposed as captured, not copied (they can be tens of MiB); callers must not mutate
/// them. Record `equals`/`hashCode` therefore compare the arrays by identity.
public record Completed(String executable, int rc, byte[] stdout, byte[] stderr, boolean truncated) {
    /// Bytes of stderr kept in [ExecError.NonZero].
    public static final int STDERR_TAIL_BYTES = 4096;

    public static Completed completed(String executable, int rc, byte[] stdout, byte[] stderr, boolean truncated) {
        return new Completed(executable, rc, stdout, stderr, truncated);
    }

    public String stdoutText() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    public String stderrText() {
        return new String(stderr, StandardCharsets.UTF_8);
    }

    /// Success only for rc 0; otherwise [ExecError.NonZero], which carries the stderr tail as data (not in its
    /// message, because git stderr can echo credential URLs).
    public Result<Completed> requireZero() {
        return rc == 0
               ? success(this)
               : ExecError.NonZero.FACTORY.apply(executable, rc, stderrTail()).result();
    }

    private String stderrTail() {
        var from = Math.max(0, stderr.length - STDERR_TAIL_BYTES);

        return new String(stderr, from, stderr.length - from, StandardCharsets.UTF_8);
    }
}
