package org.pragmatica.temper.protocol.exec;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// Process execution, which rc4 does not provide (m0-design §2.6). Lives in temper-protocol because both the
/// controller (ssh, git) and the worker (git, docker) need it.
public interface ProcessRunner {
    /// Runs to completion on a virtual thread, capturing stdout and stderr; stdin is closed at once. On
    /// timeout the process and its descendants are destroyed and the promise fails with
    /// [ExecError.TimedOut]. A non-zero exit is a successful [Completed]; see `Completed.requireZero`.
    Promise<Completed> run(Command command);
    /// Starts a long-lived process and returns at once. The command's timeout is not applied.
    Result<Spawned> spawn(Command command);

    /// The `ProcessBuilder`-backed runner.
    static ProcessRunner processRunner() {
        return JdkProcessRunner.jdkProcessRunner();
    }
}
