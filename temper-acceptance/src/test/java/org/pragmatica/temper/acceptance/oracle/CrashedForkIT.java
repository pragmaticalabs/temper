package org.pragmatica.temper.acceptance.oracle;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.temper.acceptance.stack.AcceptanceStack;
import org.pragmatica.temper.acceptance.stack.DockerAvailable;
import org.pragmatica.temper.acceptance.trust.Evidence;
import org.pragmatica.temper.acceptance.trust.Processes;
import org.pragmatica.temper.acceptance.trust.T2Check;
import org.pragmatica.temper.acceptance.trust.TemperSide;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// T2 (m1-design §8.2, §9.4), docker tier, through the real controller and worker: the fixture's `crash` overlay
/// adds `CrashedForkProbeTest`, whose one test calls `System.exit(3)`, to `includes`, the first module of the
/// reactor. The Gate must end `FAILED(missing-classes)` naming the probe: rc ≠ 0, no `<testcase>` for it, and the
/// log shows the fork-crash line. These are the live T2's assertions ([T2Check], shared with `T2Harness`).
///
/// **Control:** the parent commit's Gate (`main`) is not `missing-classes`.
///
/// **Mutation probe** (README.md): `Finishing` passes `none()` for the manifest → the verdict is `FAILED(rc)` and this
/// class goes red.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrashedForkIT {
    private static final String PROBE = "fx.includes.CrashedForkProbeTest";
    private AcceptanceStack stack;
    private TemperSide crashed;
    private TemperSide parent;

    @BeforeAll
    void gates() throws Exception {
        stack = AcceptanceStack.docker();
        parent = stack.side(stack.gate("main"));
        crashed = stack.side(stack.gate("crash"));
    }

    @Test
    void crashedFork_isFailedMissingClasses_namingTheProbe() {
        var check = T2Check.t2Check(crashed, PROBE);

        assertThat(crashed.result().verdict()).as(crashed.result().toString()).isEqualTo("FAILED");
        assertThat(crashed.result().reason().or("")).isEqualTo("missing-classes");
        assertThat(check.failedMissingClasses()).isTrue();
        assertThat(check.probeMissing()).as("manifest.missing " + crashed.manifest().missing()).isTrue();
        assertThat(check.noProbeRows()).isTrue();
        assertThat(check.rcNonZero()).as(crashed.result().units().toString()).isTrue();
        assertThat(check.forkCrashLogged()).as("log tail:\n" + crashed.logTail()).isTrue();
        assertThat(check.passed()).isTrue();
    }

    @Test
    void crashedFork_reactorStopsAtTheCrashedModule() {
        assertThat(crashed.manifest().notReached()).as("the reactor stopped in includes").contains("groups", "nested", "disabled");
    }

    @Test
    void control_theParentGateIsNotMissingClasses() {
        assertThat(T2Check.controlHolds(parent.result())).as(parent.result().toString()).isTrue();
        assertThat(parent.result().verdict()).isEqualTo("PASSED");
    }

    /// The judge reads `log.tail` and the facts from the unit's small tarball, now under its attempt's directory
    /// (m4-design §4.6). A miss degrades silently to the log route and "unavailable" facts, so it is asserted here.
    @Test
    void evidence_ofTheParentGate_isFoundUnderItsAttempt_withItsFacts() {
        var tarball = Evidence.firstTarball(parent.result());
        assertThat(tarball.map(path -> path.getParent()
                                           .getFileName()
                                           .toString())).as(parent.result()
                                                                  .evidence()
                                                                  .toString())
                                                        .isEqualTo(some("a1"));
        var evidence = Evidence.evidence(Processes.processes(ProcessRunner.processRunner()));
        var path = tarball.or(Path.of("absent"));
        assertThat(evidence.member(path, Evidence.FACTS_START).await().or("")).as("facts-start").isNotBlank();
        assertThat(evidence.member(path, Evidence.FACTS_END).await().or("")).as("facts-end").isNotBlank();
        assertThat(evidence.member(path, Evidence.LOG_TAIL).await().or("")).as("log.tail").isNotBlank();
    }
}
