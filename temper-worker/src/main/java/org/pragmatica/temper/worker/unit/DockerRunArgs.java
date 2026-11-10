package org.pragmatica.temper.worker.unit;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.plan.Planner;
import org.pragmatica.temper.domain.refusal.ContainerEnv;
import org.pragmatica.temper.domain.refusal.EnvInputs;
import org.pragmatica.temper.worker.docker.DockerCli;


/// The `docker run` argv of a unit (spec §4.2, m0-design §7.4 step 8). Pure and pinned by `DockerRunArgsTest`.
///
/// ```
/// run -d --name temper-<job>-<unit>
///   --label temper.job=<job> --label temper.unit=<unit> --label temper.version=<ver>
///   --label temper.instance=<instance> --label temper.attempt=<attempt>
///   --network temper-<job>-<unit>            (a Gate unit: --network host, UnitNetwork)
///   --user <uid>:<gid> --group-add <dockerGid>
///   -v <socket>:/var/run/docker.sock  -v <ws>:<ws>  -w <ws>
///   -e <allow-listed env, sorted>
///   --memory <memoryMb>m --cpu-shares <cpuShares>
///   temper/runner:<ver>
///   java -cp /opt/temper RunSteps <n1> <step1 argv…> <n2> <step2 argv…>
/// ```
///
/// What it never emits, by construction (no input can produce them): `-p`, `--publish`, `-P`,
/// `--publish-all`, `--network host` for any unit but a Gate (port isolation, m0-design §12.4; a Gate unit holds
/// the port group instead, m1-design §14); `--privileged`; a shell (`sh`, `bash`, `-c`, R-SHELL); a `.m2` mount
/// (R-M2); an environment key outside [ContainerEnv#ALLOWED] (R-ENV: the environment is built from [EnvInputs],
/// never from the worker's own environment).
///
/// Steps are count-prefixed, so a step's argv elements travel as separate docker argv elements with no separator
/// or escaping. `${ws}` is substituted only in the planner's own trailing `-Dmaven.repo.local` token.
public sealed interface DockerRunArgs {
    /// The image's `ENV PATH` (runner `Dockerfile`); passed explicitly because the env is an allow-list.
    String IMAGE_PATH = "/opt/maven/bin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    String IMAGE_JAVA_HOME = "/opt/java/openjdk";
    String IMAGE_REPOSITORY = "temper/runner";
    String CONTAINER_SOCKET = "/var/run/docker.sock";
    List<String> LAUNCHER = List.of("java", "-cp", "/opt/temper", "RunSteps");
    String AUX_LABEL = "temper.aux=pom";

    /// `temper.attempt=<attempt>` (m4-design §7): on the unit's container and on its effective-POM probe.
    static String attemptLabel(StartRequest request) {
        return DockerCli.TEMPER_ATTEMPT_LABEL + "=" + request.attempt();
    }

    static List<String> build(UnitLaunch launch) {
        var request = launch.request();
        var ref = request.ref();
        var workspace = launch.workspace().toString();

        return Stream.of(List.of("run",
                                 "-d",
                                 "--name",
                                 ref.containerName(),
                                 "--label",
                                 DockerCli.TEMPER_JOB_LABEL + "=" + ref.job(),
                                 "--label",
                                 DockerCli.TEMPER_UNIT_LABEL + "=" + ref.unit(),
                                 "--label",
                                 "temper.version=" + request.runnerVersion(),
                                 "--label",
                                 DockerCli.TEMPER_INSTANCE_LABEL + "=" + request.instance(),
                                 "--label",
                                 attemptLabel(request),
                                 "--network",
                                 launch.network().name(),
                                 "--user",
                                 launch.uid() + ":" + launch.gid(),
                                 "--group-add",
                                 Integer.toString(launch.dockerGid()),
                                 "-v",
                                 launch.dockerSocket() + ":" + CONTAINER_SOCKET,
                                 "-v",
                                 workspace + ":" + workspace,
                                 "-w",
                                 workspace),
                         envArgs(launch),
                         List.of("--memory",
                                 request.memory().value() + "m",
                                 "--cpu-shares",
                                 Integer.toString(request.cpuShares()),
                                 image(request.runnerVersion())),
                         LAUNCHER,
                         stepArgs(request.steps(),
                                  workspace))
                     .flatMap(List::stream)
                     .toList();
    }

    /// The effective-POM probe (m1-design §2.3): `docker run --rm`, in the foreground, named
    /// `temper-<job>-<unit>-pom` and labelled `temper.job=<job>` and `temper.aux=pom` but NOT `temper.unit` (so the
    /// M0 scan and reconcile never take it for a unit, while the A7 cap count still sees it as temper's, F13). Same
    /// user, environment allow-list, workspace mount, network (the host network for a Gate unit), memory and image as
    /// the unit; no docker socket (the probe starts no containers). The one step is the planner's `pomArgv`, with
    /// `${ws}` substituted in its two constant tokens only ([#substitutedPomArgv]).
    ///
    /// ```
    /// run --rm --name temper-<job>-<unit>-pom --label temper.job=<job> --label temper.aux=pom
    ///   --label temper.attempt=<attempt>
    ///   --network temper-<job>-<unit> --user <uid>:<gid> -v <ws>:<ws> -w <ws> -e <env…>
    ///   --memory <memoryMb>m temper/runner:<ver> java -cp /opt/temper RunSteps <n> <pomArgv…>
    /// ```
    static List<String> probe(UnitLaunch launch, List<String> pomArgv) {
        var request = launch.request();
        var ref = request.ref();
        var workspace = launch.workspace().toString();

        return Stream.of(List.of("run",
                                 "--rm",
                                 "--name",
                                 probeName(ref),
                                 "--label",
                                 DockerCli.TEMPER_JOB_LABEL + "=" + ref.job(),
                                 "--label",
                                 AUX_LABEL,
                                 "--label",
                                 attemptLabel(request),
                                 "--network",
                                 launch.network().name(),
                                 "--user",
                                 launch.uid() + ":" + launch.gid(),
                                 "-v",
                                 workspace + ":" + workspace,
                                 "-w",
                                 workspace),
                         envArgs(launch),
                         List.of("--memory",
                                 request.memory().value() + "m",
                                 image(request.runnerVersion())),
                         LAUNCHER,
                         List.of(Integer.toString(pomArgv.size())),
                         substitutedPomArgv(pomArgv,
                                            ref.unit(),
                                            workspace))
                     .flatMap(List::stream)
                     .toList();
    }

    /// `temper-<job>-<unit>-pom`.
    static String probeName(UnitRef ref) {
        return ref.containerName() + "-pom";
    }

    /// `${ws}` substituted in exactly the planner's two constant tokens, `FORCED_REPO_LOCAL` and the unit's
    /// `effectivePomOutput`; every other argument travels unchanged (it is never interpolated).
    static List<String> substitutedPomArgv(List<String> pomArgv, UnitName unit, String workspace) {
        var output = Planner.effectivePomOutput(unit);

        return pomArgv.stream()
                      .map(arg -> substitutedToken(arg, output, workspace))
                      .toList();
    }

    private static String substitutedToken(String arg, String output, String workspace) {
        return arg.equals(Planner.FORCED_REPO_LOCAL) || arg.equals(output)
               ? arg.replace(Planner.WS_PLACEHOLDER, workspace)
               : arg;
    }

    /// `temper/runner:<version>`.
    static String image(String runnerVersion) {
        return IMAGE_REPOSITORY + ":" + runnerVersion;
    }

    /// The container environment: exactly [ContainerEnv#build] of the launch's own values, as sorted `-e K=V`.
    static List<String> envArgs(UnitLaunch launch) {
        return environment(launch).entrySet()
                          .stream()
                          .sorted(Map.Entry.comparingByKey())
                          .flatMap(DockerRunArgs::envPair)
                          .toList();
    }

    private static Stream<String> envPair(Map.Entry<String, String> variable) {
        return Stream.of("-e",
                         variable.getKey() + "=" + variable.getValue());
    }

    static Map<String, String> environment(UnitLaunch launch) {
        return ContainerEnv.build(EnvInputs.envInputs(IMAGE_JAVA_HOME,
                                                      IMAGE_PATH,
                                                      launch.home().toString(),
                                                      launch.network().hostOverride(),
                                                      launch.mavenOpts(),
                                                      launch.tcHubPrefix()));
    }

    /// `<n1> <step1…> <n2> <step2…>`, with `${ws}` substituted in each step's trailing forced-repo token only.
    static List<String> stepArgs(List<List<String>> steps, String workspace) {
        return steps.stream()
                    .map(step -> substituted(step, workspace))
                    .flatMap(step -> Stream.concat(Stream.of(Integer.toString(step.size())),
                                                   step.stream()))
                    .toList();
    }

    private static List<String> substituted(List<String> step, String workspace) {
        return step.getLast()
                   .equals(Planner.FORCED_REPO_LOCAL)
               ? Stream.concat(step.subList(0,
                                            step.size() - 1).stream(),
                               Stream.of(Planner.FORCED_REPO_LOCAL.replace(Planner.WS_PLACEHOLDER, workspace)))
                       .toList()
               : step;
    }

    record unused() implements DockerRunArgs {}
}
