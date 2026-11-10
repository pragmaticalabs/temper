package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.plan.Planner;
import org.pragmatica.temper.domain.refusal.ContainerEnv;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.Fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// Pins the unit `docker run` argv (spec §4.2, m0-design §7.4 step 8, §12.4): A1's R-ENV/R-M2/R-SHELL rows on the
/// worker side, and the no-fixed-host-ports rule.
class DockerRunArgsTest {
    private static final Path WS = Path.of("/home/builder/.temper/jobs/4711/ws");

    private static UnitLaunch launch(List<List<String>> steps) {
        var request = ok(StartRequest.startRequest(Fixtures.startUnit(4711, "main", steps, Duration.ofMinutes(60))));

        return UnitLaunch.unitLaunch(request,
                                     WS,
                                     WS.resolve(".home"),
                                     1001,
                                     1002,
                                     998,
                                     Path.of("/var/run/docker.sock"),
                                     UnitNetwork.unitNetwork("temper-4711-main", "192.0.2.1"));
    }

    /// A Gate unit's launch: on the host network ([UnitNetwork#HOST]), as `UnitStarter` prepares it.
    private static UnitLaunch gateLaunch(List<List<String>> steps) {
        var bridged = launch(steps);

        return UnitLaunch.unitLaunch(bridged.request(),
                                     WS,
                                     WS.resolve(".home"),
                                     1001,
                                     1002,
                                     998,
                                     Path.of("/var/run/docker.sock"),
                                     UnitNetwork.HOST);
    }

    private static List<String> args() {
        return DockerRunArgs.build(launch(List.of(List.of("mvn", "-o", "verify", "-pl", "core", Planner.FORCED_REPO_LOCAL))));
    }

    @Test
    void build_producesThePinnedArgv() {
        assertThat(args()).containsExactly("run",
                                           "-d",
                                           "--name",
                                           "temper-4711-main",
                                           "--label",
                                           "temper.job=4711",
                                           "--label",
                                           "temper.unit=main",
                                           "--label",
                                           "temper.version=0.1.0",
                                           "--label",
                                           "temper.instance=temper",
                                           "--label",
                                           "temper.attempt=1",
                                           "--network",
                                           "temper-4711-main",
                                           "--user",
                                           "1001:1002",
                                           "--group-add",
                                           "998",
                                           "-v",
                                           "/var/run/docker.sock:/var/run/docker.sock",
                                           "-v",
                                           WS + ":" + WS,
                                           "-w",
                                           WS.toString(),
                                           "-e",
                                           "HOME=" + WS.resolve(".home"),
                                           "-e",
                                           "JAVA_HOME=/opt/java/openjdk",
                                           "-e",
                                           "MAVEN_OPTS=-Xmx2g",
                                           "-e",
                                           "PATH=" + DockerRunArgs.IMAGE_PATH,
                                           "-e",
                                           "TESTCONTAINERS_HOST_OVERRIDE=192.0.2.1",
                                           "-e",
                                           "TZ=UTC",
                                           "--memory",
                                           "6144m",
                                           "--cpu-shares",
                                           "1024",
                                           "temper/runner:0.1.0",
                                           "java",
                                           "-cp",
                                           "/opt/temper",
                                           "RunSteps",
                                           "6",
                                           "mvn",
                                           "-o",
                                           "verify",
                                           "-pl",
                                           "core",
                                           "-Dmaven.repo.local=" + WS + "/.m2-local");
    }

    /// What `--user` and `--memory` mean, independent of the golden argv: the unit runs as the workspace owner
    /// (never root, never the image default) and under the memory limit the controller sent.
    @Test
    void build_runsAsTheWorkspaceOwner_underTheUnitsMemoryLimit() {
        var request = ok(StartRequest.startRequest(new Message.StartUnit(9,
                                                                         "it",
                                                                         1,
                                                                         "MVN",
                                                                         List.of(List.of("mvn", "-v")),
                                                                         Duration.ofMinutes(5),
                                                                         3_000,
                                                                         512,
                                                                         "v1",
                                                                         none(),
                                                                         none(),
                                                                         none(),
                                                                         none(), "temper")));
        var args = DockerRunArgs.build(UnitLaunch.unitLaunch(request,
                                                             WS,
                                                             WS.resolve(".home"),
                                                             4242,
                                                             5353,
                                                             998,
                                                             Path.of("/var/run/docker.sock"),
                                                             UnitNetwork.unitNetwork("temper-9-it", "198.51.100.1")));

        assertThat(only(args, "--user")).isEqualTo("4242:5353");
        assertThat(only(args, "--memory")).isEqualTo(request.memory()
                                                            .value() + "m")
                                          .isEqualTo("3000m");
        assertThat(args.subList(0, args.indexOf(DockerRunArgs.image("v1")))).noneMatch(arg -> arg.startsWith("--user=") || arg.startsWith("-u") || arg.startsWith("--memory=")
                                          || arg.startsWith("-m"));
    }

    /// The value after `flag`, which must occur exactly once (before the image, so docker reads it as an option).
    private static String only(List<String> args, String flag) {
        var image = args.indexOf(DockerRunArgs.image("v1"));

        assertThat(args.stream()
                       .filter(flag::equals)
                       .count()).as(flag)
                                .isEqualTo(1);
        assertThat(args.indexOf(flag)).isLessThan(image);
        return args.get(args.indexOf(flag) + 1);
    }

    @Test
    void build_neverPublishesPortsOrUsesTheHostNetwork() {
        var args = args();

        assertThat(args).doesNotContain("-p", "-P", "--publish", "--publish-all", "--privileged", "--net", "host");
        assertThat(args).noneMatch(arg -> arg.startsWith("--publish") || arg.startsWith("-p=") || arg.startsWith("--net=")
                                          || arg.startsWith("--network=") || arg.equals("--network=host"));
        assertThat(args.get(args.indexOf("--network") + 1)).isEqualTo("temper-4711-main");
        assertThat(args.stream()
                       .filter(arg -> arg.equals("--network"))
                       .count()).isEqualTo(1);
    }

    /// A Gate unit (m1-design §14): `--network host` exactly once, still no published port, and Testcontainers told
    /// `localhost`, the `getHost()` of bb's host build; every other argument as for a bridged unit.
    @Test
    void build_runsAGateUnitOnTheHostNetwork_withLocalhostAsTheTestcontainersHost() {
        var steps = List.of(List.of("mvn", "-o", "verify", "-pl", "core", Planner.FORCED_REPO_LOCAL));
        var args = DockerRunArgs.build(gateLaunch(steps));
        var bridged = new ArrayList<>(DockerRunArgs.build(launch(steps)));

        assertThat(args.get(args.indexOf("--network") + 1)).isEqualTo("host");
        assertThat(args.stream()
                       .filter(arg -> arg.equals("--network"))
                       .count()).isEqualTo(1);
        assertThat(args).contains("TESTCONTAINERS_HOST_OVERRIDE=localhost")
                        .doesNotContain("-p", "-P", "--publish", "--publish-all", "--privileged");
        bridged.set(bridged.indexOf("--network") + 1, "host");
        bridged.set(bridged.indexOf("TESTCONTAINERS_HOST_OVERRIDE=192.0.2.1"), "TESTCONTAINERS_HOST_OVERRIDE=localhost");
        assertThat(args).isEqualTo(bridged);
    }

    @Test
    void probe_ofAGateUnit_runsOnTheHostNetwork() {
        var argv = DockerRunArgs.probe(gateLaunch(List.of(List.of("mvn", "-v"))), List.of("mvn", "-v"));

        assertThat(argv.get(argv.indexOf("--network") + 1)).isEqualTo("host");
        assertThat(argv).contains("TESTCONTAINERS_HOST_OVERRIDE=localhost");
    }

    @Test
    void build_hasNoShellAndNoMavenRepositoryMount() {
        var args = args();
        var mounts = IntStream.range(0, args.size() - 1)
                              .filter(index -> args.get(index)
                                                   .equals("-v"))
                              .mapToObj(index -> args.get(index + 1))
                              .toList();

        assertThat(args).doesNotContain("sh", "bash", "/bin/sh", "/bin/bash", "-c");
        assertThat(mounts).containsExactly("/var/run/docker.sock:/var/run/docker.sock", WS + ":" + WS);
        assertThat(mounts).noneMatch(mount -> mount.contains(".m2:") || mount.contains("/.m2/") || mount.endsWith("/.m2"));
    }

    @Test
    void build_passesOnlyAllowListedEnvironment_whateverTheWorkerEnvironmentHolds() {
        var args = args();
        var keys = new ArrayList<String>();

        for (var index = 0; index < args.size() - 1; index++) {
            if (args.get(index)
                    .equals("-e")) {
                keys.add(args.get(index + 1)
                             .split("=", 2)[0]);
            }
        }

        assertThat(keys).isSubsetOf(ContainerEnv.ALLOWED);
        assertThat(keys).doesNotContain("HCLOUD_TOKEN", "GITHUB_TOKEN", "LANG", "USER");
        assertThat(String.join(" ", args)).doesNotContain("TOKEN");
        // The argv is built from the launch values only: nothing from this JVM's environment can appear.
        System.getenv()
              .entrySet()
              .stream()
              .filter(entry -> !ContainerEnv.ALLOWED.contains(entry.getKey()))
              .forEach(entry -> assertThat(keys).doesNotContain(entry.getKey()));
    }

    @Test
    void stepArgs_countPrefixesEachStep_andSubstitutesOnlyTheTrailingForcedRepoToken() {
        var steps = List.of(List.of("mvn", "-Dfoo=${ws}/x", Planner.FORCED_REPO_LOCAL),
                            List.of("mvn", "a b", "c;d", "$(rm -rf /)", Planner.FORCED_REPO_LOCAL));

        assertThat(DockerRunArgs.stepArgs(steps, "/w")).containsExactly("3",
                                                                       "mvn",
                                                                       "-Dfoo=${ws}/x",
                                                                       "-Dmaven.repo.local=/w/.m2-local",
                                                                       "5",
                                                                       "mvn",
                                                                       "a b",
                                                                       "c;d",
                                                                       "$(rm -rf /)",
                                                                       "-Dmaven.repo.local=/w/.m2-local");
    }

    @Test
    void stepArgs_leavesAStepWithoutTheForcedTokenUnchanged() {
        assertThat(DockerRunArgs.stepArgs(List.of(List.of("mvn", "-v")), "/w")).containsExactly("2", "mvn", "-v");
    }

    @Test
    void environment_omitsOptionalValuesWhenAbsent() {
        var request = ok(StartRequest.startRequest(new org.pragmatica.temper.protocol.message.Message.StartUnit(1,
                                                                                                                 "main",
                                                                                                                 1,
                                                                                                                 "MVN",
                                                                                                                 List.of(List.of("mvn", "-v")),
                                                                                                                 Duration.ofMinutes(5),
                                                                                                                 1024,
                                                                                                                 512,
                                                                                                                 "v1",
                                                                                                                 none(),
                                                                                                                 some("mirror.local/"),
                                                                                                                 none(),
                                                                                                                 none(), "temper")));
        var environment = DockerRunArgs.environment(UnitLaunch.unitLaunch(request,
                                                                          WS,
                                                                          WS.resolve(".home"),
                                                                          1,
                                                                          1,
                                                                          1,
                                                                          Path.of("/var/run/docker.sock"),
                                                                          UnitNetwork.unitNetwork("temper-1-main", "198.51.100.1")));

        assertThat(environment.keySet()).isEqualTo(Set.of("JAVA_HOME",
                                                          "PATH",
                                                          "HOME",
                                                          "TZ",
                                                          "TESTCONTAINERS_HOST_OVERRIDE",
                                                          "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX"));
    }

    /// The effective-POM probe (m1-design §2.3): `--rm`, its own name, `temper.job` and `temper.aux` but never
    /// `temper.unit`, no docker socket, the unit's network, user, env and image; `${ws}` substituted only in the
    /// planner's two constant tokens.
    @Test
    void probe_isAnAuxiliaryRmContainer_withOnlyThePlannerTokensSubstituted() {
        var argv = DockerRunArgs.probe(launch(List.of(List.of("mvn", "-v"))),
                                       List.of("mvn",
                                               "-B",
                                               ManifestSpec.EFFECTIVE_POM_GOAL,
                                               "-Doutput=${ws}/.home/.temper/effective-pom-main.xml",
                                               "-Dfoo=${ws}",
                                               Planner.FORCED_REPO_LOCAL));

        assertThat(argv.subList(0, 11)).containsExactly("run",
                                                       "--rm",
                                                       "--name",
                                                       "temper-4711-main-pom",
                                                       "--label",
                                                       "temper.job=4711",
                                                       "--label",
                                                       "temper.aux=pom",
                                                       "--label",
                                                       "temper.attempt=1",
                                                       "--network");
        assertThat(argv).contains("temper-4711-main", "1001:1002", WS + ":" + WS, "temper/runner:0.1.0")
                        .doesNotContain("temper.unit=main", "-d", "--group-add", "/var/run/docker.sock:/var/run/docker.sock");
        assertThat(argv.subList(argv.indexOf("RunSteps") + 1, argv.size())).containsExactly("6",
                                                                                         "mvn",
                                                                                         "-B",
                                                                                         ManifestSpec.EFFECTIVE_POM_GOAL,
                                                                                         "-Doutput=" + WS + "/.home/.temper/effective-pom-main.xml",
                                                                                         "-Dfoo=${ws}",
                                                                                         "-Dmaven.repo.local=" + WS + "/.m2-local");
    }
}
