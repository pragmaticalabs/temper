package org.pragmatica.temper.worker.config;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.StreamOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.message.BuildVersion;
import org.pragmatica.temper.worker.WorkerError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// The worker's runtime configuration. There is no config file: the worker runs under a forced-command ssh key
/// (`worker-launch run <ver>`), which cannot pass an environment, so everything is derived from the host.
///
/// - `uid`/`gid`: owner of `$HOME` (`unix:uid`/`unix:gid`), the `--user` of every unit container.
/// - `dockerGid`: group of the docker socket (`unix:gid`), the `--group-add` of every unit container.
/// - `toolEnv`: the ONLY environment docker and git calls see (`inheritEnv = false`): `PATH`, `HOME`, and
///   `DOCKER_HOST` when the worker's own environment sets it (m0-design §2.6).
/// - `procRoot`/`cgroupRoot`: `/proc` and `/sys/fs/cgroup`, replaceable by fixtures in tests.
/// - `dockerRoot`: the docker root directory for R-DISK and facts; `none()` asks `docker info` once.
/// - `diskFloorBytes`: the R-DISK floor (spec §2.5 default 50 GiB).
/// - `bbHoldPath`: bb-hold, the port-group holder (`$HOME/tools/bb-hold`, m1-design §1.2).
/// - `runFloorBytes`: the in-run disk floor (10 GiB, maintainer ruling M1-10): below it on the docker root or the
///   workspace filesystem, every running unit is marked `VOID(disk)` and left running.
/// - `bbQueue`: the read-only `~/tools/bb-queue 0` listing for the bb-* ticket facts (Q-10, m0-design §12.7), with
///   only `PATH`, `HOME` and `USER` (bb-queue runs `pgrep -u "$USER"` under `set -u`). The same executable with
///   `reap` releases a dead holder's ticket ([org.pragmatica.temper.worker.unit.BbQueueReap], maintainer ruling A11): the
///   worker requires that subcommand on hosts with port-group tickets, and degrades with a warning without it.
public record WorkerConfig(WorkerPaths paths,
                           String workerVersion,
                           String host,
                           int uid,
                           int gid,
                           int dockerGid,
                           Path dockerSocket,
                           long diskFloorBytes,
                           Map<String, String> toolEnv,
                           Path procRoot,
                           Path cgroupRoot,
                           Option<Path> dockerRoot,
                           Command bbQueue,
                           Path bbHoldPath,
                           long runFloorBytes,
                           WorkerTimings timings) {
    public static final Path DOCKER_SOCKET = Path.of("/var/run/docker.sock");
    public static final long DEFAULT_DISK_FLOOR_BYTES = 50L * 1024 * 1024 * 1024;
    public static final long DEFAULT_RUN_FLOOR_BYTES = 10L * 1024 * 1024 * 1024;
    public static final String BB_QUEUE = "tools/bb-queue";
    public static final String BB_HOLD = "tools/bb-hold";
    public static final TimeSpan BB_QUEUE_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    public static final int BB_QUEUE_OUTPUT_LIMIT = 1024 * 1024;
    public static final String VERSION_RESOURCE = "/" + BuildVersion.WORKER_RESOURCE;

    public WorkerConfig {
        toolEnv = Map.copyOf(toolEnv);
    }

    /// The variable that replaces `~/.temper` (set by `ops/worker-launch`, F1: the acceptance host tier's own home).
    public static final String TEMPER_WORKER_HOME = "TEMPER_WORKER_HOME";

    /// Production configuration from the worker's own environment (`HOME`, `PATH`, optional `DOCKER_HOST` and
    /// `TEMPER_WORKER_HOME`).
    public static Result<WorkerConfig> workerConfig(Map<String, String> environment) {
        return home(environment).flatMap(home -> workerConfig(environment, home));
    }

    private static Result<WorkerConfig> workerConfig(Map<String, String> environment, Path home) {
        return Result.all(bundledVersion(),
                          hostName(),
                          intAttribute(home, "unix:uid"),
                          intAttribute(home, "unix:gid"),
                          intAttribute(DOCKER_SOCKET, "unix:gid"),
                          bbQueue(environment, home))
                     .map(HostFacts::new)
                     .flatMap(facts -> workerConfig(environment, home, facts));
    }

    /// What the worker reads from the host before it can configure itself; kept apart so the derivation from the
    /// environment (above all the `TEMPER_WORKER_HOME` root, A1) is testable without a docker socket.
    record HostFacts(String version, String host, int uid, int gid, int dockerGid, Command bbQueue) {}

    /// The configuration from the environment and the host facts: every worker path (mirror, jobs, seed,
    /// `worker.lock`, run copies) is rooted at [#temperHome(Map, Path)].
    static Result<WorkerConfig> workerConfig(Map<String, String> environment, Path home, HostFacts facts) {
        return temperHome(environment, home).map(WorkerPaths::workerPaths)
                         .map(paths -> workerConfig(environment, home, facts, paths));
    }

    private static WorkerConfig workerConfig(Map<String, String> environment,
                                             Path home,
                                             HostFacts facts,
                                             WorkerPaths paths) {
        return new WorkerConfig(paths,
                                facts.version(),
                                facts.host(),
                                facts.uid(),
                                facts.gid(),
                                facts.dockerGid(),
                                DOCKER_SOCKET,
                                DEFAULT_DISK_FLOOR_BYTES,
                                toolEnv(environment),
                                Path.of("/proc"),
                                Path.of("/sys/fs/cgroup"),
                                none(),
                                facts.bbQueue(),
                                home.resolve(BB_HOLD),
                                DEFAULT_RUN_FLOOR_BYTES,
                                WorkerTimings.workerTimings());
    }

    /// `TEMPER_WORKER_HOME` when set and not blank, else `~/.temper`. `HOME` stays the user's: `~/tools` and the
    /// tools' own state are the host's, shared with the live worker. A relative value is refused, as `worker-launch`
    /// and `seed-host.sh` refuse it (A8): it would root the worker at whatever directory it was started in.
    static Result<Path> temperHome(Map<String, String> environment, Path home) {
        return option(environment.get(TEMPER_WORKER_HOME)).filter(value -> !value.isBlank())
                     .map(Path::of)
                     .map(WorkerConfig::absoluteHome)
                     .or(() -> Result.success(home.resolve(".temper")));
    }

    private static Result<Path> absoluteHome(Path temperHome) {
        return Result.success(temperHome).filter(WorkerError.General.RELATIVE_WORKER_HOME, Path::isAbsolute);
    }

    /// `~/tools/bb-queue 0`: running and waiting tickets, no finished list.
    public static Result<Command> bbQueue(Map<String, String> environment, Path home) {
        return Command.command(List.of(home.resolve(BB_QUEUE).toString(),
                                       "0"),
                               bbQueueEnv(environment),
                               BB_QUEUE_TIMEOUT)
                      .map(command -> command.withOutputLimit(BB_QUEUE_OUTPUT_LIMIT));
    }

    private static Map<String, String> bbQueueEnv(Map<String, String> environment) {
        return Stream.of("PATH", "HOME", "USER")
                     .filter(environment::containsKey)
                     .collect(Collectors.toUnmodifiableMap(key -> key, environment::get));
    }

    /// `PATH` and `HOME` copied explicitly, plus `DOCKER_HOST` if set; nothing else reaches docker or git.
    public static Map<String, String> toolEnv(Map<String, String> environment) {
        return Stream.of("PATH", "HOME", "DOCKER_HOST")
                     .filter(environment::containsKey)
                     .collect(Collectors.toUnmodifiableMap(key -> key, environment::get));
    }

    private static Result<Path> home(Map<String, String> environment) {
        return option(environment.get("HOME")).filter(home -> !home.isBlank())
                     .map(Path::of)
                     .toResult(WorkerError.General.NO_HOME);
    }

    /// This jar's build version, from its filtered version resource.
    static Result<String> bundledVersion() {
        return StreamOps.readResource(WorkerConfig.class, VERSION_RESOURCE)
                        .mapError(origin -> WorkerError.Io.FACTORY.apply("read the worker version", origin))
                        .flatMap(WorkerConfig::derived);
    }

    /// The build version (D-6, `BuildVersion`: `<project.version>+<judge12>`) of the filtered version resource's
    /// `version=` and `judge=` lines, the same derivation as the controller's `ControllerVersion`, so the two jars of one
    /// build agree and a worker of another build is `Incompatible`.
    static Result<String> derived(String properties) {
        return Result.all(versionOf(properties).toResult(WorkerError.General.NO_VERSION),
                          judgeOf(properties).toResult(WorkerError.General.NO_JUDGE))
                     .flatMap(WorkerConfig::buildVersion);
    }

    private static Result<String> buildVersion(String projectVersion, String judge) {
        return BuildVersion.buildVersion(projectVersion, judge).mapError(WorkerError.BadVersion.FACTORY);
    }

    /// The `version=` line of the filtered version resource; an unfiltered `${...}` placeholder is no version (the same
    /// rule as the controller's `ControllerVersion`).
    static Option<String> versionOf(String properties) {
        return BuildVersion.property(properties, "version");
    }

    /// The `judge=` line of the filtered version resource; an unfiltered `${...}` placeholder is no judge.
    static Option<String> judgeOf(String properties) {
        return BuildVersion.property(properties, "judge");
    }

    private static Result<String> hostName() {
        return Result.lift(thrown -> WorkerError.Io.FACTORY.apply("read the host name", Causes.fromThrowable(thrown)),
                           () -> InetAddress.getLocalHost().getHostName());
    }

    private static Result<Integer> intAttribute(Path path, String attribute) {
        return Result.lift(thrown -> WorkerError.Io.FACTORY.apply("read " + attribute + " of " + path,
                                                                  Causes.fromThrowable(thrown)),
                           () -> (Integer) Files.getAttribute(path, attribute));
    }
}
