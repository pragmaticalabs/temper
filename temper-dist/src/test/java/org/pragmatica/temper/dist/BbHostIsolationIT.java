package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// Guard (issue #56, 2026-11-18): an IT that runs a real `bb-build`/`bb-queue` must not read the build host's memory, processes or
/// reactor cap. cloudbb-3 had two hand-started TLC JVMs at -Xmx8g; the memory gate's off-queue headroom closed, no ticket was granted
/// and BbRequireDockerIT failed 7 of 9 on "the build finished". The shared harness (`BbFixtures.run` / `BbFixtures.isolated`) supplies a
/// generous fixed meminfo, an empty process list and a high cap file unless the test passes its own.
///
/// The guard reads the bytecode of every Bb*IT: only `BbFixtures.process`, `processClean` and `run` may start a child, so a call of
/// `HermeticProcess.builder` (any overload) or `prepared`, or any `ProcessBuilder.environment()`, is a bypass. The fixture package
/// holds one method per bypass form and [#theGuard_catchesEveryBypassForm] proves each is flagged.
///
/// Probes: drop a default from [BbFixtures] -> [#everyLaunch_getsIsolatedHostInputs] red; make `run` pass `env` as is ->
/// [#aRealMemoryGate_isOpen_whileAnOffQueueJvmHoldsAHugeUnclaimedHeap] red where an off-queue -Xmx64g JVM exists; call `HermeticProcess.builder` in a real Bb*IT -> [#noBbIT_launchesWithoutTheSharedIsolation] red;
/// drop a predicate branch of the guard -> [#theGuard_catchesEveryBypassForm] red.
class BbHostIsolationIT {
    @TempDir
    Path dir;

    @Test
    void everyLaunch_getsIsolatedHostInputs() throws Exception {
        var out = BbFixtures.run(List.of("sh",
                                         "-c",
                                         "echo \"$BB_TEST_MEMINFO\"; echo \"$BB_TEST_PS\"; echo \"$BB_TEST_MAX_REACTORS_FILE\"; echo \"$BB_TEST_MEM_FLOOR_FILE\""),
                                 Map.of(),
                                 null,
                                 null)
                            .stdout()
                            .lines()
                            .toList();

        assertThat(out).hasSize(4).allSatisfy(v -> assertThat(v).isNotBlank()
                                                             .doesNotStartWith("/proc"));
        assertThat(Files.readAllLines(Path.of(out.get(0)))).contains("MemAvailable:   67108864 kB");
        assertThat(Files.readString(Path.of(out.get(1)))).as("an empty process list").isEmpty();
        assertThat(Files.readString(Path.of(out.get(2))).strip()).isEqualTo("64");
        assertThat(Path.of(out.get(3))).as("no floor file: the default floor").doesNotExist();
    }

    @Test
    void aTestsOwnValue_winsOverTheDefault() throws Exception {
        var out = BbFixtures.run(List.of("sh", "-c", "echo \"$BB_TEST_PS|$BB_TEST_MEMINFO\""),
                                 Map.of("BB_TEST_PS", "", "BB_TEST_MEMINFO", "/x"),
                                 null,
                                 null)
                            .stdout()
                            .strip();

        assertThat(out).isEqualTo("|/x");
    }

    @Test
    void aRealMemoryGate_isOpen_whileAnOffQueueJvmHoldsAHugeUnclaimedHeap() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-queue-lib.sh uses pgrep and /proc");
        var java = ProcessHandle.current().info().command().orElseThrow();
        var sleeper = Files.writeString(dir.resolve("Sleeper.java"),
                                        "class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(600_000); } }\n");
        var launch = BbFixtures.run(List.of("bash",
                                            "-c",
                                            "\"$0\" -Xmx64g -XX:+UseSerialGC \"$1\" > /dev/null 2>&1 < /dev/null & echo $!",
                                            java,
                                            sleeper.toString()),
                                    Map.of("PATH", System.getenv("PATH")),
                                    dir,
                                    null);
        var pid = Long.parseLong(launch.stdout().strip());

        try {
            var env = Map.of("PATH",
                             System.getenv("PATH"),
                             "HOME",
                             dir.toString(),
                             "USER",
                             System.getProperty("user.name"),
                             "BB_TEST_QUEUE_ROOT",
                             dir.resolve("q").toString(),
                             "BB_TEST_REACTOR_PATTERN",
                             "[n]o-such-maven-reactor");
            var lib = BbFixtures.OPS_BB.resolve("bb-queue-lib.sh").toString();
            var deadline = System.currentTimeMillis() + 30_000;
            var visible = false;
            // control: the same JVM IS seen by the real ps when a test asks for it, so the isolated run below proves the harness, not blindness
            var real = new java.util.HashMap<>(env);

            real.put("BB_TEST_PS", "");
            while (!visible && System.currentTimeMillis() < deadline) {
                visible = BbFixtures.run(List.of("bash", "-c", ". \"$1\" && offqueue_jvms", "bash", lib),
                                         real,
                                         dir,
                                         null)
                                    .stdout()
                                    .lines()
                                    .anyMatch(l -> l.startsWith(pid + " "));
                if (!visible) {
                    Thread.sleep(200);
                }
            }

            assertThat(visible).as("control: the real ps sees the -Xmx64g JVM").isTrue();
            var held = BbFixtures.run(List.of("bash", "-c", ". \"$1\" && mem_held", "bash", lib), env, dir, null);

            assertThat(held.rc()).as(held.stderr()).isZero();
            assertThat(held.stdout()).as("the isolated gate ignores the host's JVMs and memory").isEmpty();
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static final String HERMETIC = "org.pragmatica.temper.testsupport.HermeticProcess";
    private static final String DIST = "org.pragmatica.temper.dist";

    /// "Class.method" of every call or reference, in a Bb*IT class of `classes`, that starts a child or edits a child's environment by
    /// any route but `BbFixtures.process`/`processClean`/`run`: a method of [#HERMETIC] (`builder` in all overloads, `prepared`) or
    /// `ProcessBuilder.environment()` (`put`, `putAll`, `clear`, in a lambda too). Read from the bytecode, so no source layout hides one.
    static List<String> launchesWithoutTheSharedIsolation(JavaClasses classes) {
        return classes.stream()
                      .filter(c -> c.getName()
                                    .matches(".*\\.Bb\\w*IT(\\$.*)?"))
                      .flatMap(c -> c.getAccessesFromSelf()
                                     .stream())
                      .filter(a -> HERMETIC.equals(a.getTargetOwner().getName()) || ("java.lang.ProcessBuilder".equals(a.getTargetOwner()
                                                                                                                        .getName()) && a.getName()
                                                                                                                                        .equals("environment")))
                      .map(a -> a.getOrigin()
                                 .getOwner()
                                 .getSimpleName()
                               + "." + a.getOrigin()
                                        .getName()
                               + " -> " + a.getTargetOwner()
                                           .getSimpleName()
                               + "." + a.getName())
                      .distinct()
                      .sorted()
                      .toList();
    }

    @Test
    void noBbIT_launchesWithoutTheSharedIsolation() {
        var real = new ClassFileImporter().importPackages(DIST)
                                          .that(DescribedPredicate.describe("directly in " + DIST,
                                                                            (JavaClass c) -> c.getPackageName()
                                                                                              .equals(DIST)));

        assertThat(real.stream().filter(c -> c.getSimpleName()
                                              .matches("Bb\\w*IT")).count()).as("the scan sees the Bb*ITs")
                  .isGreaterThan(15);
        assertThat(launchesWithoutTheSharedIsolation(real)).as("Bb*IT starts a child only through BbFixtures.process, processClean or run")
                  .isEmpty();
    }

    @Test
    void theGuard_catchesEveryBypassForm() {
        var fixture = new ClassFileImporter().importPackages(DIST + ".fixture");

        assertThat(launchesWithoutTheSharedIsolation(fixture)).containsExactly("BbBypassFormsIT.builderList -> HermeticProcess.builder",
                                                                               "BbBypassFormsIT.builderListEnv -> HermeticProcess.builder",
                                                                               "BbBypassFormsIT.builderVarargs -> HermeticProcess.builder",
                                                                               "BbBypassFormsIT.environmentPut -> ProcessBuilder.environment",
                                                                               "BbBypassFormsIT.environmentPutAll -> ProcessBuilder.environment",
                                                                               "BbBypassFormsIT.insideALambda -> ProcessBuilder.environment",
                                                                               "BbBypassFormsIT.prepared -> HermeticProcess.prepared");
    }
}
