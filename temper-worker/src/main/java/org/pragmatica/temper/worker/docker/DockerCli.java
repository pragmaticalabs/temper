package org.pragmatica.temper.worker.docker;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.WorkerTimings;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The docker CLI, one method per call the worker makes. Every call is an argv array run through
/// [ProcessRunner] with `inheritEnv = false` and only `env` (PATH, HOME, optional DOCKER_HOST): no shell, no
/// host environment. Failures are [WorkerError.Docker] with the exec failure as origin; inspect-style calls
/// return `none()` when docker says the object does not exist, and fail only when docker itself fails.
public record DockerCli(ProcessRunner runner, Map<String, String> env, WorkerTimings timings) {
    /// Bytes of `docker logs --tail` output kept for a `LogTail` reply (the reply itself is cut to 1 MiB).
    public static final int TAIL_CAPTURE_BYTES = 4 * 1024 * 1024;
    /// Bytes of `docker logs` stderr appended to the unit log at exit (RunSteps merges mvn stderr into stdout,
    /// so stderr holds only the launcher's own lines).
    public static final int LOG_STDERR_BYTES = 1024 * 1024;
    public static final String TEMPER_UNIT_LABEL = "temper.unit";
    /// Every temper container carries it: unit containers and auxiliary ones (the effective-POM probe, F13).
    public static final String TEMPER_JOB_LABEL = "temper.job";
    public static final String TC_LABEL = "org.testcontainers=true";
    /// The controller instance a unit container belongs to (F2); reconcile ignores another instance's containers.
    public static final String TEMPER_INSTANCE_LABEL = "temper.instance";
    /// The unit attempt a container runs (protocol v3, m4-design §4.4); a start adopts only a container of its own attempt.
    public static final String TEMPER_ATTEMPT_LABEL = "temper.attempt";
    /// sha256 of the runner build context, so a jar with a changed Dockerfile or launcher rebuilds its image.
    public static final String RUNNER_HASH_LABEL = "temper.runner.hash";

    /// One `network inspect` row, parsed by [DockerOutput#orphanNetworks].
    private static final String NETWORK_ROW = "{{.Created.Unix}}|{{len .Containers}}|{{index .Labels \"" + TEMPER_JOB_LABEL
                                            + "\"}}|{{index .Labels \"" + TEMPER_UNIT_LABEL
                                            + "\"}}|{{.Name}}";

    public DockerCli {
        env = Map.copyOf(env);
    }

    public static DockerCli dockerCli(ProcessRunner runner, Map<String, String> env, WorkerTimings timings) {
        return new DockerCli(runner, env, timings);
    }

    // ---- containers ----------------------------------------------------------------------------------------
    /// `docker inspect --type container -f {{.State.Status}} <name>`, mapped; a missing container is `ABSENT`, also when
    /// another object has its name (a waiting port-group unit has its network but no container yet).
    public Promise<ContainerStatus> status(String container) {
        return inspect("inspect", containerInspect("{{.State.Status}}", container)).map(status -> status.map(DockerOutput::containerStatus)
                                                                                                        .or(ContainerStatus.ABSENT));
    }

    /// The container's `temper.instance` label; none when the container is gone or carries none (`<no value>`).
    public Promise<Option<String>> instanceLabel(String container) {
        return inspect("inspect",
                       containerInspect("{{index .Config.Labels \"" + TEMPER_INSTANCE_LABEL + "\"}}", container)).map(DockerCli::labelValue);
    }

    private static Option<String> labelValue(Option<String> label) {
        return label.map(String::strip)
                    .filter(value -> !value.isEmpty() && !value.equals("<no value>"));
    }

    /// The container's `temper.attempt` label (protocol v3, m4-design §4.4); none when the container is gone, carries
    /// none (a container started before M4), or carries one that is not a number.
    public Promise<Option<Integer>> attemptLabel(String container) {
        return inspect("inspect",
                       containerInspect("{{index .Config.Labels \"" + TEMPER_ATTEMPT_LABEL + "\"}}", container)).map(DockerCli::labelValue)
                      .map(DockerCli::attemptOf);
    }

    private static Option<Integer> attemptOf(Option<String> label) {
        return label.flatMap(DockerCli::parsedAttempt);
    }

    private static Option<Integer> parsedAttempt(String value) {
        return Number.parseInt(value).option();
    }

    /// The full id of a container, if it exists.
    public Promise<Option<String>> containerId(String container) {
        return inspect("inspect", containerInspect("{{.Id}}", container)).map(id -> id.map(String::trim));
    }

    /// `{{.State.ExitCode}}` of an exited container (the rc when no `docker wait` saw the exit).
    public Promise<Integer> exitCode(String container) {
        return output("inspect", timings.dockerCall(), containerInspect("{{.State.ExitCode}}", container)).flatMap(text -> exitCodeOf("inspect",
                                                                                                                                      text));
    }

    /// `docker run` with the argv from `DockerRunArgs` (which starts with `run`); returns the container id.
    public Promise<String> run(List<String> runArgs) {
        return output("run", timings.dockerCall(), runArgs).map(String::trim);
    }

    /// `docker run` of a foreground auxiliary container (`run --rm …`, m1-design §2.3), bounded by `timeout`; a
    /// non-zero exit is a successful [Completed] for the caller to judge.
    public Promise<Completed> runAux(List<String> runArgs, TimeSpan timeout) {
        return call("run", timeout, runArgs);
    }

    /// `docker wait <c>`: blocks until the container exits and returns its rc.
    public Promise<Integer> waitFor(String container, TimeSpan timeout) {
        return output("wait", timeout, List.of("wait", container)).flatMap(text -> exitCodeOf("wait", text));
    }

    /// `docker exec <c> <argv…>`; a non-zero exit is a successful [Completed] for the caller to judge.
    public Promise<Completed> exec(String container, List<String> argv, TimeSpan timeout) {
        return call("exec",
                    timeout,
                    Stream.concat(Stream.of("exec", container), argv.stream()).toList());
    }

    /// `docker kill <c>`. Idempotent: a container that is already stopped or gone is not a failure.
    public Promise<Unit> kill(String container) {
        return call("kill", timings.dockerCall(), List.of("kill", container)).flatMap(completed -> tolerating("kill",
                                                                                                              completed,
                                                                                                              DockerCli::stoppedOrGone));
    }

    /// `docker rm -f <c>`. Idempotent: a missing container is not a failure.
    public Promise<Unit> remove(String container) {
        return call("rm", timings.dockerCall(), List.of("rm", "-f", container)).flatMap(completed -> tolerating("rm",
                                                                                                                completed,
                                                                                                                DockerOutput::noSuchObject));
    }

    /// The last `lines` lines of the container's output (stdout, then stderr).
    public Promise<Completed> logsTail(String container, int lines) {
        return command(timings.dockerCall(),
                       List.of("logs",
                               "--tail",
                               Integer.toString(lines),
                               container)).map(command -> command.withOutputLimit(TAIL_CAPTURE_BYTES))
                      .async()
                      .flatMap(runner::run)
                      .mapError(origin -> WorkerError.Docker.FACTORY.apply("logs", origin));
    }

    /// Streams the container's whole output into `file` (stdout, then at most [#LOG_STDERR_BYTES] of stderr), so
    /// no log is ever held in memory. Bounded by `collectCall`; on failure the `docker logs` process is killed.
    public Promise<Unit> logsTo(String container, Path file) {
        return command(timings.collectCall(),
                       List.of("logs", container)).flatMap(runner::spawn)
                      .async()
                      .mapError(origin -> WorkerError.Docker.FACTORY.apply("logs", origin))
                      .flatMap(spawned -> streamed(spawned, file));
    }

    // ---- images --------------------------------------------------------------------------------------------
    /// `docker image inspect -f {{.Id}} <ref>`, if the image exists.
    public Promise<Option<String>> imageId(String image) {
        return inspect("image inspect", List.of("image", "inspect", "-f", "{{.Id}}", image)).map(id -> id.map(String::trim));
    }

    /// `docker build -t <tag> --label temper.runner=<version> --label temper.runner.hash=<hash> <dir>`.
    public Promise<Unit> build(String tag, String version, String contentHash, Path directory) {
        return output("build",
                      timings.imageBuild(),
                      List.of("build",
                              "-t",
                              tag,
                              "--label",
                              "temper.runner=" + version,
                              "--label",
                              RUNNER_HASH_LABEL + "=" + contentHash,
                              directory.toString())).mapToUnit();
    }

    /// The image's `temper.runner.hash` label, if the image exists and carries one.
    public Promise<Option<String>> imageContentHash(String image) {
        return inspect("image inspect",
                       List.of("image", "inspect", "-f", "{{index .Config.Labels \"" + RUNNER_HASH_LABEL + "\"}}", image)).map(DockerCli::sha256Label);
    }

    /// A label value that is a sha256 hex (an image without the label prints `<no value>`).
    private static Option<String> sha256Label(Option<String> label) {
        return label.map(String::trim)
                    .filter(DockerOutput.FULL_ID.asMatchPredicate());
    }

    // ---- networks ------------------------------------------------------------------------------------------
    public Promise<Boolean> networkExists(String network) {
        return inspect("network inspect", List.of("network", "inspect", "-f", "{{.Name}}", network)).map(Option::isPresent);
    }

    /// `docker network create --label temper.job=<job> --label temper.unit=<unit> <name>`.
    public Promise<Unit> networkCreate(String network, String job, String unit) {
        return output("network create",
                      timings.dockerCall(),
                      List.of("network",
                              "create",
                              "--label",
                              TEMPER_JOB_LABEL + "=" + job,
                              "--label",
                              TEMPER_UNIT_LABEL + "=" + unit,
                              network)).mapToUnit();
    }

    /// The network's IPv4 gateway: the `TESTCONTAINERS_HOST_OVERRIDE` of the unit (spec §4.2).
    public Promise<String> networkGateway(String network) {
        return output("network inspect",
                      timings.dockerCall(),
                      List.of("network", "inspect", "-f", "{{range .IPAM.Config}}{{.Gateway}} {{end}}", network)).flatMap(text -> DockerOutput.ipv4Gateway(text).async(WorkerError.General.NO_IPV4_GATEWAY));
    }

    /// `docker network rm <name>`. Idempotent: a missing network is not a failure.
    public Promise<Unit> networkRemove(String network) {
        return call("network rm", timings.dockerCall(), List.of("network", "rm", network)).flatMap(completed -> tolerating("network rm",
                                                                                                                           completed,
                                                                                                                           DockerOutput::noSuchObject));
    }

    /// Labelled temper networks (`temper.job`, never a name prefix: a network another tool made is not temper's) that
    /// hold no container and were created before `createdBefore`: `docker network ls --filter label=temper.job -q`,
    /// then one `docker network inspect` for the rows. A network that vanishes between the two calls is skipped.
    public Promise<List<OrphanNetwork>> networkOrphans(Instant createdBefore) {
        return output("network ls",
                      timings.dockerCall(),
                      List.of("network", "ls", "--filter", "label=" + TEMPER_JOB_LABEL, "-q")).map(DockerOutput::networkIds)
                     .flatMap(ids -> networkRows(ids, createdBefore));
    }

    private Promise<List<OrphanNetwork>> networkRows(List<String> ids, Instant createdBefore) {
        return ids.isEmpty()
               ? Promise.success(List.<OrphanNetwork> of())
               : call("network inspect",
                      timings.dockerCall(),
                      networkRowsArgs(ids)).flatMap(DockerCli::rowsOrGone)
                     .map(text -> DockerOutput.orphanNetworks(text, createdBefore));
    }

    private static List<String> networkRowsArgs(List<String> ids) {
        return Stream.concat(Stream.of("network", "inspect", "-f", NETWORK_ROW),
                             ids.stream())
                     .toList();
    }

    /// The rows of the networks that exist: a vanished one fails the call with "no such network" after docker has
    /// printed the others.
    private static Promise<String> rowsOrGone(Completed completed) {
        return completed.rc() == 0 || DockerOutput.noSuchObject(completed.stderrText())
               ? Promise.success(completed.stdoutText())
               : zero("network inspect", completed).map(Completed::stdoutText);
    }

    // ---- host-wide queries ---------------------------------------------------------------------------------
    /// `docker info -f {{.DockerRootDir}}`.
    public Promise<Path> rootDir() {
        return output("info",
                      timings.dockerCall(),
                      List.of("info", "-f", "{{.DockerRootDir}}")).map(String::trim)
                     .map(Path::of);
    }

    /// Full ids of the running temper containers (label `temper.job`): units and their auxiliary containers, so
    /// the effective-POM probe's Maven is never counted as a foreign reactor (F13).
    public Promise<Set<String>> temperContainerIds() {
        return output("ps",
                      timings.dockerCall(),
                      List.of("ps", "-q", "--no-trunc", "--filter", "label=" + TEMPER_JOB_LABEL)).map(DockerOutput::containerIds);
    }

    /// Full ids of every container of `job`, running or not (`ps -a`, label `temper.job=<job>`): units, auxiliary
    /// containers and anything else a unit labelled so (host checkout GC, m1-design §4.3).
    public Promise<Set<String>> idsByJobLabel(String job) {
        return output("ps",
                      timings.dockerCall(),
                      List.of("ps", "-a", "-q", "--no-trunc", "--filter", "label=" + TEMPER_JOB_LABEL + "=" + job)).map(DockerOutput::containerIds);
    }

    /// Every Testcontainers container, running or not, with its session id.
    public Promise<List<TcContainer>> testcontainers() {
        return output("ps",
                      timings.dockerCall(),
                      List.of("ps",
                              "-a",
                              "--no-trunc",
                              "--filter",
                              "label=" + TC_LABEL,
                              "--format",
                              "{{.ID}} {{.Label \"org.testcontainers.sessionId\"}}")).map(DockerOutput::testcontainers);
    }

    // ---- plumbing ------------------------------------------------------------------------------------------
    private Result<Command> command(TimeSpan timeout, List<String> args) {
        return Command.command(Stream.concat(Stream.of("docker"), args.stream()).toList(),
                               env,
                               timeout);
    }

    /// Runs one call; a non-zero exit is still a success here.
    private Promise<Completed> call(String operation, TimeSpan timeout, List<String> args) {
        return command(timeout, args).async()
                      .flatMap(runner::run)
                      .mapError(origin -> WorkerError.Docker.FACTORY.apply(operation, origin));
    }

    /// Runs one call that must exit 0 and returns its stdout.
    private Promise<String> output(String operation, TimeSpan timeout, List<String> args) {
        return call(operation, timeout, args).flatMap(completed -> zero(operation, completed))
                   .map(Completed::stdoutText);
    }

    /// `--type container`: a unit's network carries the container's name, and an untyped inspect of a name with no
    /// container answers for the network, whose template then fails (cloudbb-3, 2026-10-28).
    private static List<String> containerInspect(String format, String container) {
        return List.of("inspect", "--type", "container", "-f", format, container);
    }

    private Promise<Option<String>> inspect(String operation, List<String> args) {
        return call(operation, timings.dockerCall(), args).flatMap(completed -> presentOrAbsent(operation, completed));
    }

    private static Promise<Completed> zero(String operation, Completed completed) {
        return completed.requireZero()
                        .mapError(origin -> WorkerError.Docker.FACTORY.apply(operation, origin))
                        .async();
    }

    private static Promise<Option<String>> presentOrAbsent(String operation, Completed completed) {
        if (completed.rc() == 0) {
            return Promise.success(some(completed.stdoutText()));
        }

        return DockerOutput.noSuchObject(completed.stderrText())
               ? Promise.success(none())
               : zero(operation, completed).map(_ -> none());
    }

    private static Promise<Unit> tolerating(String operation, Completed completed, Predicate<String> harmless) {
        return completed.rc() == 0 || harmless.test(completed.stderrText())
               ? Promise.success(unit())
               : zero(operation, completed).mapToUnit();
    }

    private static boolean stoppedOrGone(String stderr) {
        return DockerOutput.noSuchObject(stderr) || stderr.contains("is not running");
    }

    private static Promise<Integer> exitCodeOf(String operation, String text) {
        return DockerOutput.exitCode(text).async(WorkerError.UnexpectedOutput.FACTORY.apply(operation, text.trim()));
    }

    private Promise<Unit> streamed(Spawned spawned, Path file) {
        // The kill may run late: it is a best-effort signal to an abandoned process, whose outcome nothing reads.
        return Promise.all(Promise.lift(ioError(file),
                                        () -> Files.copy(spawned.stdout(),
                                                         file,
                                                         StandardCopyOption.REPLACE_EXISTING)),
                           drained(spawned.stderr(),
                                   file),
                           spawned.exit())
                      .flatMap((_, stderr, rc) -> appendedIfZero(file, stderr, rc))
                      .timeout(timings.collectCall())
                      .onFailure(_ -> spawned.kill());
    }

    /// Reads `stream` to its end (so the process never blocks on a full pipe), keeping the first bytes.
    private static Promise<byte[]> drained(InputStream stream, Path file) {
        return Promise.lift(ioError(file),
                            () -> stream.readNBytes(LOG_STDERR_BYTES))
                      .ensureWith(_ -> Promise.lift(ioError(file),
                                                    () -> stream.transferTo(OutputStream.nullOutputStream())));
    }

    /// A failed `docker logs` has written only its own error text: fail, so the caller does not publish it as the
    /// unit log; the stderr it printed is the origin.
    private static Promise<Unit> appendedIfZero(Path file, byte[] stderr, int rc) {
        return rc == 0
               ? appended(file, stderr)
               : WorkerError.Docker.FACTORY.apply("logs",
                                                  ExecError.NonZero.FACTORY.apply("docker",
                                                                                  rc,
                                                                                  new String(stderr,
                                                                                             StandardCharsets.UTF_8)))
                                           .promise();
    }

    private static Promise<Unit> appended(Path file, byte[] bytes) {
        return Promise.lift(ioError(file),
                            () -> Files.write(file, bytes, StandardOpenOption.APPEND))
                      .mapToUnit();
    }

    private static Fn1<WorkerError.Io, Throwable> ioError(Path file) {
        return thrown -> WorkerError.Io.FACTORY.apply("write the unit log " + file, Causes.fromThrowable(thrown));
    }
}
