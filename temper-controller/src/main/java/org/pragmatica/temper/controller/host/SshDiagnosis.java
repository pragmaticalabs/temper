package org.pragmatica.temper.controller.host;

import java.util.List;


/// Reads ssh's stderr for a host-key failure (m4-design §2.2, F6). Pure. ssh prints one of [#MARKS] and exits 255
/// before the remote command when the host's key is not the one in the configured `knownHosts`: a changed key
/// (`REMOTE HOST IDENTIFICATION HAS CHANGED`) or a missing entry (`Host key verification failed`). Either means the
/// controller cannot authenticate the box, so the host is LOST at once (ruling R-M4-16, Q-M4-2). The texts are OpenSSH's,
/// pinned against a real client by `SshdHostKeyIT`.
public sealed interface SshDiagnosis {
    List<String> MARKS = List.of("Host key verification failed", "REMOTE HOST IDENTIFICATION HAS CHANGED");

    /// One stderr line of a link session.
    static boolean hostKeyFailure(String line) {
        return MARKS.stream().anyMatch(line::contains);
    }

    /// A captured stderr (the `install` session of [WorkerDeploy]), line by line.
    static boolean hostKeyFailureIn(String stderr) {
        return stderr.lines()
                     .anyMatch(SshDiagnosis::hostKeyFailure);
    }

    record unused() implements SshDiagnosis {}
}
