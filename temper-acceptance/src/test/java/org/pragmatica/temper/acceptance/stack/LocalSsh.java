package org.pragmatica.temper.acceptance.stack;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

/// The controller's process runner with ssh replaced by local processes, so the real controller and the real worker
/// meet over real pipes (integration finding F2: the first worker → controller crossing):
///
/// - `run <ver>`: a [LocalWorker] JVM, stdin/stdout the frame stream as under `ssh -T`;
/// - `install <ver> <sha256>`: drains the jar and exits 0 (the worker is already this build);
/// - `evidence <job> <unit> small|full`: the REAL `ops/worker-launch` with `SSH_ORIGINAL_COMMAND` set, the way
///   sshd's forced command runs it, its stdout redirected exactly as the controller asked.
///
/// Every other command (git, docker for pg_dump) runs as given. The pids of the `run` sessions are kept so a test
/// can kill one, as a dropped ssh connection would.
///
/// **Blackhole** (m4-design §12.3, [#blackhole]): the host stops answering. Its `run` sessions are killed (the
/// worker JVM dies with its pipes; its unit containers keep running, as on a host the network lost), and every ssh to
/// it until [#restore] exits 255 with ssh's connect-timeout line, as `ConnectTimeout=15` ends a real one, so the link
/// and the deploy fail as `link-down`, never as a host-key failure. Deterministic: no network is involved.
public final class LocalSsh implements ProcessRunner {
    /// ssh's own line for a connect that timed out; it matches neither host-key pattern of `SshDiagnosis`.
    static final String TIMED_OUT = "ssh: connect to host %s port 22: Operation timed out";

    private final ProcessRunner real = ProcessRunner.processRunner();
    private final String host;
    private final Path workerHome;
    private final String version;
    private final int dockerGid;
    private final Path workerLaunch;
    private volatile boolean blackholed;
    public final List<Spawned> sessions = new CopyOnWriteArrayList<>();

    public LocalSsh(Path workerHome, String version, int dockerGid, Path workerLaunch) {
        this("local", workerHome, version, dockerGid, workerLaunch);
    }

    public LocalSsh(String host, Path workerHome, String version, int dockerGid, Path workerLaunch) {
        this.host = host;
        this.workerHome = workerHome;
        this.version = version;
        this.dockerGid = dockerGid;
        this.workerLaunch = workerLaunch;
    }

    /// The host goes dark: every `run` session is killed and every ssh to the host fails until [#restore].
    public void blackhole() {
        blackholed = true;
        sessions.forEach(Spawned::kill);
    }

    /// The host answers again; the link's next reconnect starts a fresh worker.
    public void restore() {
        blackholed = false;
    }

    public Path workerHome() {
        return workerHome;
    }

    @Override
    public Promise<Completed> run(Command command) {
        return real.run(command);
    }

    @Override
    public Result<Spawned> spawn(Command command) {
        var remote = remoteArgs(command.argv());

        return remote.map(args -> local(command, args))
                     .or(() -> real.spawn(command));
    }

    private Result<Spawned> local(Command command, List<String> remote) {
        if (blackholed) {
            return real.spawn(timedOut(command));
        }
        return switch (remote.getFirst()) {
            case "run" -> real.spawn(worker(command)).onSuccess(sessions::add);
            case "install" -> real.spawn(drain(command));
            default -> real.spawn(workerLaunch(command, remote));
        };
    }

    /// The remote command of an ssh argv: everything after the `user@address` element; none for a non-ssh argv.
    static Option<List<String>> remoteArgs(List<String> argv) {
        if (!argv.getFirst().equals("ssh")) {
            return Option.none();
        }
        for (var index = 1; index < argv.size(); index++) {
            if (argv.get(index).contains("@") && !argv.get(index - 1).equals("-o") && !argv.get(index - 1).equals("-i")) {
                return Option.some(argv.subList(index + 1, argv.size()));
            }
        }
        return Option.none();
    }

    /// The `address` of an ssh argv's `user@address` element; none for a non-ssh argv.
    static Option<String> destination(List<String> argv) {
        return remoteArgs(argv).map(remote -> argv.get(argv.size() - remote.size() - 1))
                               .map(target -> target.substring(target.indexOf('@') + 1));
    }

    private Command timedOut(Command ssh) {
        return new Command(List.of("sh", "-c", "sleep 1; echo \"$0\" >&2; exit 255", TIMED_OUT.formatted(host)),
                           Option.none(), Map.of(), true, ssh.timeout(), ssh.outputLimit(), Option.none(), Option.none());
    }

    private Command worker(Command ssh) {
        return new Command(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                   "-cp",
                                   System.getProperty("java.class.path"),
                                   "-Dacc.home=" + workerHome,
                                   "-Dacc.version=" + version,
                                   "-Dacc.dockerGid=" + dockerGid,
                                   "-Dacc.host=" + host,
                                   LocalWorker.class.getName()),
                           Option.none(),
                           Map.of(),
                           true,
                           ssh.timeout(),
                           ssh.outputLimit(),
                           Option.none(),
                           Option.none());
    }

    private static Command drain(Command ssh) {
        return new Command(List.of("sh", "-c", "cat > /dev/null"), Option.none(), Map.of(), true, ssh.timeout(), ssh.outputLimit(),
                           Option.none(), Option.none());
    }

    private Command workerLaunch(Command ssh, List<String> remote) {
        return new Command(List.of("bash", workerLaunch.toString()),
                           Option.none(),
                           Map.of("HOME", workerHome.toString(),
                                  "PATH", System.getenv("PATH"),
                                  "SSH_ORIGINAL_COMMAND", String.join(" ", remote)),
                           false,
                           ssh.timeout(),
                           ssh.outputLimit(),
                           ssh.stdoutFile(),
                           ssh.stderrFile());
    }

}
