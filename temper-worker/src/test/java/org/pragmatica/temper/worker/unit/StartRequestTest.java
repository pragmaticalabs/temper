package org.pragmatica.temper.worker.unit;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.PortGroupHoldDto;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.WorkerError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.failure;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// Every StartUnit value that reaches a docker argv or a host path is parsed first.
class StartRequestTest {
    private static Message.StartUnit start(String unit, List<List<String>> steps, Duration timeout, int cpuShares, String version) {
        return new Message.StartUnit(7, unit, 1, "MVN", steps, timeout, 2048, cpuShares, version, Option.none(), Option.none(), Option.none(),
                                     Option.none(), "temper");
    }

    private static final List<List<String>> STEPS = List.of(List.of("mvn", "-v"));

    @Test
    void startRequest_parsesAValidStart() {
        var request = ok(StartRequest.startRequest(start("main", STEPS, Duration.ofMinutes(5), 1024, "0.1.0-SNAPSHOT")));

        assertThat(request.ref()
                          .containerName()).isEqualTo("temper-7-main");
        assertThat(request.memory()
                          .value()).isEqualTo(2048);
        assertThat(request.steps()).isEqualTo(STEPS);
    }

    @Test
    void startRequest_refusesATraversingOrNonTagRunnerVersion() {
        assertThat(failure(StartRequest.startRequest(start("main", STEPS, Duration.ofMinutes(5), 1024, "../../etc")))).satisfies(StartRequestTest::badRequest);
        assertThat(failure(StartRequest.startRequest(start("main", STEPS, Duration.ofMinutes(5), 1024, "1.0+build")))).satisfies(StartRequestTest::badRequest);
        assertThat(failure(StartRequest.startRequest(start("main", STEPS, Duration.ofMinutes(5), 1024, "")))).satisfies(StartRequestTest::badRequest);
    }

    /// Protocol v3: the attempt is the store's grant count, at least 1.
    @Test
    void startRequest_carriesThePositiveAttempt_andRefusesZeroOrLess() {
        assertThat(ok(StartRequest.startRequest(Fixtures.startUnit(7, "main", 3, STEPS, Duration.ofMinutes(5)))).attempt()).isEqualTo(3);
        assertThat(failure(StartRequest.startRequest(Fixtures.startUnit(7, "main", 0, STEPS, Duration.ofMinutes(5))))).satisfies(StartRequestTest::badRequest);
        assertThat(failure(StartRequest.startRequest(Fixtures.startUnit(7, "main", -1, STEPS, Duration.ofMinutes(5))))).satisfies(StartRequestTest::badRequest);
    }

    @Test
    void startRequest_refusesABadUnitName() {
        assertThat(failure(StartRequest.startRequest(start("../x", STEPS, Duration.ofMinutes(5), 1024, "v1")))).satisfies(StartRequestTest::badRequest);
        assertThat(failure(StartRequest.startRequest(start("Main", STEPS, Duration.ofMinutes(5), 1024, "v1")))).satisfies(StartRequestTest::badRequest);
    }

    @Test
    void startRequest_refusesEmptyStepsOrArguments() {
        assertThat(failure(StartRequest.startRequest(start("main", List.of(), Duration.ofMinutes(5), 1024, "v1")))).satisfies(StartRequestTest::badRequest);
        assertThat(failure(StartRequest.startRequest(start("main", List.of(List.of()), Duration.ofMinutes(5), 1024, "v1")))).satisfies(StartRequestTest::badRequest);
        assertThat(failure(StartRequest.startRequest(start("main", List.of(List.of("mvn", "")), Duration.ofMinutes(5), 1024, "v1")))).satisfies(StartRequestTest::badRequest);
    }

    @Test
    void startRequest_refusesNonPositiveTimeoutAndOutOfRangeShares() {
        assertThat(failure(StartRequest.startRequest(start("main", STEPS, Duration.ZERO, 1024, "v1")))).satisfies(StartRequestTest::badRequest);
        assertThat(failure(StartRequest.startRequest(start("main", STEPS, null, 1024, "v1")))).satisfies(StartRequestTest::badRequest);
        assertThat(failure(StartRequest.startRequest(start("main", STEPS, Duration.ofMinutes(1), 1, "v1")))).satisfies(StartRequestTest::badRequest);
        assertThat(failure(StartRequest.startRequest(start("main", STEPS, Duration.ofMinutes(1), 262_145, "v1")))).satisfies(StartRequestTest::badRequest);
    }

    private static void badRequest(org.pragmatica.lang.Cause cause) {
        assertThat(WorkerError.code(cause)).isEqualTo("bad-request");
    }

    @Test
    void fixtureRequest_isValid() {
        assertThat(Fixtures.request(1, "main")
                           .runnerVersion()).isEqualTo("0.1.0");
    }

    private static Message.StartUnit held(Option<PortGroupHoldDto> hold, Option<ManifestSpecDto> manifest) {
        return new Message.StartUnit(7, "gate", 1, "GATE_UNSHARDED", STEPS, Duration.ofHours(3), 2048, 1024, "0.1.0", Option.none(), Option.none(),
                                     hold, manifest, "temper");
    }

    /// Only the Gate kind runs on the host network (m1-design §14).
    @Test
    void gate_isTrueForTheGateKindOnly() {
        assertThat(ok(StartRequest.startRequest(held(Option.none(), Option.none()))).gate()).isTrue();
        assertThat(ok(StartRequest.startRequest(start("main", STEPS, Duration.ofMinutes(1), 1024, "v1"))).gate()).isFalse();
        assertThat(Fixtures.request(1, "main")
                           .gate()).isFalse();
    }

    /// protocol v2: `portGroup` and `manifest` are parsed; a bb-hold owner outside bb-hold's own alphabet fails the
    /// request instead of reaching a bb-hold argv.
    @Test
    void startRequest_parsesThePortGroupAndTheManifest_andRefusesABadOwner() {
        var spec = new ManifestSpecDto(List.of("mvn", "-B"), "integration-test", java.util.Map.of("skipITs", "false"), Option.none(), Option.some("*IT"));
        var request = ok(StartRequest.startRequest(held(Option.some(new PortGroupHoldDto("temper", 5)), Option.some(spec))));

        assertThat(request.portGroup()).isEqualTo(Option.some(new HoldRequest("temper", 5)));
        assertThat(request.manifest()
                          .map(ManifestSpecDto::manifestSpecDto)).isEqualTo(Option.some(spec));
        assertThat(ok(StartRequest.startRequest(held(Option.none(), Option.none()))).portGroup()).isEqualTo(Option.none());
        for (var owner : List.of("", "temper owner", "temper;id", "../x")) {
            assertThat(WorkerError.code(failure(StartRequest.startRequest(held(Option.some(new PortGroupHoldDto(owner, 5)), Option.none())))))
                .as(owner)
                .isEqualTo("bad-request");
        }
        assertThat(WorkerError.code(failure(StartRequest.startRequest(held(Option.none(),
                                                                           Option.some(new ManifestSpecDto(List.of(), "test", java.util.Map.of(),
                                                                                                           Option.none(), Option.none())))))))
            .isEqualTo("bad-request");
    }
}
