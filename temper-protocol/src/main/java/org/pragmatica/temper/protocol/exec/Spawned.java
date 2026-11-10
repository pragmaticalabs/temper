package org.pragmatica.temper.protocol.exec;

import java.io.InputStream;
import java.io.OutputStream;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// A long-lived process (the ssh link, `docker wait`). The caller owns all three streams and must drain
/// `stderr` (and `stdout`), or the process blocks once a pipe buffer fills.
public interface Spawned {
    InputStream stdout();
    OutputStream stdin();
    InputStream stderr();
    /// Completes with the exit code when the process exits.
    Promise<Integer> exit();
    /// Destroys the process and its descendants forcibly. Idempotent.
    Unit kill();
    long pid();
}
