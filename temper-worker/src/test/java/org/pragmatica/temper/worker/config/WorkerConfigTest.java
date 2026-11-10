package org.pragmatica.temper.worker.config;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.worker.WorkerError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

class WorkerConfigTest {
    @Test
    void toolEnv_copiesOnlyPathHomeAndDockerHost() {
        var environment = Map.of("PATH", "/usr/bin", "HOME", "/home/builder", "HCLOUD_TOKEN", "secret", "LANG", "C", "DOCKER_HOST", "unix:///x");

        assertThat(WorkerConfig.toolEnv(environment)).isEqualTo(Map.of("PATH", "/usr/bin", "HOME", "/home/builder", "DOCKER_HOST", "unix:///x"));
        assertThat(WorkerConfig.toolEnv(Map.of("HCLOUD_TOKEN", "secret"))).isEmpty();
    }

    /// F1: the acceptance host tier's own home replaces `~/.temper`; unset or blank, `~/.temper` stays.
    @Test
    void temperHome_isTemperWorkerHome_whenSet_elseHomeDotTemper() {
        var home = Path.of("/home/builder");

        assertThat(ok(WorkerConfig.temperHome(Map.of("TEMPER_WORKER_HOME", "/home/builder/temper-acceptance/.temper"), home))).isEqualTo(Path.of("/home/builder/temper-acceptance/.temper"));
        assertThat(ok(WorkerConfig.temperHome(Map.of("TEMPER_WORKER_HOME", " "), home))).isEqualTo(Path.of("/home/builder/.temper"));
        assertThat(ok(WorkerConfig.temperHome(Map.of(), home))).isEqualTo(Path.of("/home/builder/.temper"));
    }

    /// A8: a relative override would root the worker at its working directory; it is refused, as by `worker-launch`.
    @Test
    void temperHome_refusesARelativeTemperWorkerHome() {
        assertThat(WorkerConfig.temperHome(Map.of("TEMPER_WORKER_HOME", "rel/.temper"), Path.of("/home/builder")))
                  .isEqualTo(WorkerError.General.RELATIVE_WORKER_HOME.result());
    }

    /// A1: the configuration the worker builds from its environment roots every host path at the override, so the
    /// acceptance worker never touches the live mirror, jobs, seed or lock. Fails if the environment is not passed on.
    @Test
    void workerConfig_rootsMirrorJobsSeedAndLock_atTemperWorkerHome() {
        var home = Path.of("/home/builder");
        var override = Path.of("/home/builder/temper-acceptance/.temper");
        var environment = Map.of("HOME", home.toString(), "PATH", "/usr/bin", "TEMPER_WORKER_HOME", override.toString());
        var facts = new WorkerConfig.HostFacts("0.1.0", "bigboy", 1000, 1000, 999, ok(WorkerConfig.bbQueue(environment, home)));
        var paths = ok(WorkerConfig.workerConfig(environment, home, facts)).paths();

        assertThat(paths.mirror()).isEqualTo(override.resolve("mirror.git"));
        assertThat(paths.jobsDir()).isEqualTo(override.resolve("jobs"));
        assertThat(paths.seed()).isEqualTo(override.resolve("seed-m2"));
        assertThat(paths.lockFile()).isEqualTo(override.resolve("worker.lock"));
        assertThat(ok(WorkerConfig.workerConfig(Map.of("HOME", home.toString()), home, facts)).paths()
                                                                                       .mirror()).isEqualTo(home.resolve(".temper/mirror.git"));
    }

    @Test
    void versionOf_readsTheFilteredVersionLine() {
        assertThat(WorkerConfig.versionOf("# c\nversion=0.1.0-SNAPSHOT\n")).isEqualTo(Option.some("0.1.0-SNAPSHOT"));
        assertThat(WorkerConfig.versionOf("version=\n")).isEqualTo(Option.none());
        assertThat(WorkerConfig.versionOf("version=${project.version}\n")).as("unfiltered").isEqualTo(Option.none());
    }

    /// D-6: the worker's version is the build version of this build's `version=` and `judge=` lines, derived
    /// independently here (surefire passes both properties), so a derivation that drops the judge goes red.
    @Test
    void bundledVersionResource_isFiltered_andDerivesThisBuildsVersion() throws Exception {
        try (var in = WorkerConfig.class.getResourceAsStream(WorkerConfig.VERSION_RESOURCE)) {
            assertThat(in).isNotNull();
            var text = new String(in.readAllBytes());
            var judge = System.getProperty("temper.judge");
            var suffix = judge.equals("unjudged")
                         ? "unjudged"
                         : judge.substring(0, 12) + (judge.endsWith("-dirty") ? "-dirty" : "");

            assertThat(text).doesNotContain("${");
            assertThat(WorkerConfig.derived(text)
                                   .unwrap()).isEqualTo(System.getProperty("project.version") + "+" + suffix);
            assertThat(WorkerConfig.bundledVersion()
                                   .unwrap()).as("the version the worker says in Hello")
                                             .isEqualTo(System.getProperty("project.version") + "+" + suffix);
        }
    }

    @Test
    void derived_isTheBuildVersion_orFailsOnAMissingLineOrABadJudge() {
        var commit = "0123456789abcdef0123456789abcdef01234567";

        assertThat(WorkerConfig.derived("version=0.1.0-SNAPSHOT\njudge=" + commit + "\n")
                               .unwrap()).isEqualTo("0.1.0-SNAPSHOT+0123456789ab");
        assertThat(WorkerConfig.derived("version=0.1.0-SNAPSHOT\njudge=" + commit + "-dirty\n")
                               .unwrap()).isEqualTo("0.1.0-SNAPSHOT+0123456789ab-dirty");
        assertThat(WorkerConfig.derived("version=0.1.0-SNAPSHOT\njudge=unjudged\n")
                               .unwrap()).isEqualTo("0.1.0-SNAPSHOT+unjudged");
        assertThat(WorkerConfig.derived("version=0.1.0-SNAPSHOT\n")
                               .isFailure()).as("no judge line").isTrue();
        assertThat(WorkerConfig.derived("version=0.1.0-SNAPSHOT\njudge=${temper.judge}\n")
                               .isFailure()).as("unfiltered judge").isTrue();
        assertThat(WorkerConfig.derived("version=0.1.0-SNAPSHOT\njudge=pre-judge\n")
                               .isFailure()).as("pre-judge").isTrue();
        assertThat(WorkerConfig.derived("version=0.1 0;rm\njudge=unjudged\n")
                               .isFailure()).as("outside the alphabet").isTrue();
    }

    @Test
    void paths_layOutTheHostTree() {
        var paths = WorkerPaths.workerPaths(java.nio.file.Path.of("/h/.temper"));
        var ref = org.pragmatica.temper.worker.Fixtures.ref(12, "main");

        assertThat(paths.stateFile(ref, UnitFile.MARKER)).hasToString("/h/.temper/jobs/12/units/main/.temper/marker");
        assertThat(paths.workspaceRepo(ref.job())).hasToString("/h/.temper/jobs/12/ws/.m2-local");
        assertThat(paths.threadDumpsDir(ref)).hasToString("/h/.temper/jobs/12/units/main/threaddumps");
        assertThat(paths.runnerDir("0.1.0")).hasToString("/h/.temper/runner/0.1.0");
    }
}
