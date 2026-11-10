package org.pragmatica.temper.worker.gc;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.unit.HolderProbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The orphan network sweep over a scripted docker: which networks it removes, and that nothing it meets fails it.
class NetworkSweepTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_000_000);
    private static final long OLD = NOW.minus(Duration.ofMinutes(11)).getEpochSecond();
    private static final long YOUNG = NOW.minus(Duration.ofMinutes(9)).getEpochSecond();
    private static final List<String> LIST = List.of("docker", "network", "ls", "--filter", "label=temper.job", "-q");
    private static final List<String> INSPECT = List.of("docker", "network", "inspect", "-f");

    private final FakeRunner runner = new FakeRunner();
    private HolderStatus holder = HolderStatus.DEAD;
    private String holderOf = "";

    private NetworkSweep sweep() {
        HolderProbe holders = new HolderProbe() {
            @Override
            public Result<Option<HolderStatus>> status(UnitRef ref) {
                return Result.success(ref.unit().value().equals(holderOf) ? Option.some(holder) : Option.none());
            }

            @Override
            public Result<Boolean> terminate(UnitRef ref) {
                return Result.success(false);
            }
        };
        InstantSource clock = () -> NOW;

        return NetworkSweep.networkSweep(DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()), holders, clock, NetworkSweep.MIN_AGE);
    }

    private void networks(String rows) {
        runner.on(LIST, 0, "n1\nn2\nn3\n", "");
        runner.on(INSPECT, 0, rows, "");
    }

    private List<String> removed() {
        return runner.calls().stream()
                     .filter(argv -> argv.size() == 4 && argv.get(1).equals("network") && argv.get(2).equals("rm"))
                     .map(argv -> argv.get(3))
                     .toList();
    }

    @Test
    void sweep_removesEmptyNetworksOlderThanTheGuard_only() {
        networks(OLD + "|0|7|main|temper-7-main\n" + YOUNG + "|0|8|main|temper-8-main\n" + OLD + "|2|9|main|temper-9-main\n");

        ok(sweep().sweep().await());

        assertThat(removed()).containsExactly("temper-7-main");
    }

    @Test
    void sweep_keepsTheNetworkOfAWaitingHoldUnit_andRemovesOneWhoseHolderIsDead() {
        networks(OLD + "|0|7|queued|temper-7-queued\n" + OLD + "|0|8|main|temper-8-main\n");
        holder = HolderStatus.WAITING;
        holderOf = "queued";

        ok(sweep().sweep().await());

        assertThat(removed()).containsExactly("temper-8-main");

        holder = HolderStatus.DEAD;
        ok(sweep().sweep().await());

        assertThat(removed()).contains("temper-7-queued");
    }

    @Test
    void sweep_removesTheRest_whenOneRemovalFails_andStillSucceeds() {
        networks(OLD + "|0|7|main|temper-7-main\n" + OLD + "|0|8|main|temper-8-main\n");
        runner.on(List.of("docker", "network", "rm", "temper-7-main"), 1, "", "Error response from daemon: network has active endpoints");

        ok(sweep().sweep().await());

        assertThat(removed()).containsExactlyInAnyOrder("temper-7-main", "temper-8-main");
    }

    @Test
    void sweep_succeeds_whenDockerCannotListNetworks_andRemovesNothing() {
        runner.failing(LIST, WorkerError.General.NO_IPV4_GATEWAY);

        ok(sweep().sweep().await());

        assertThat(removed()).isEmpty();
    }

    @Test
    void sweep_asksDockerNothingMore_whenNoNetworkIsLabelled() {
        runner.on(LIST, 0, "", "");

        ok(sweep().sweep().await());

        assertThat(runner.calls()).noneMatch(argv -> argv.size() > 2 && argv.get(2).equals("inspect"));
    }

    @Test
    void sweep_usesTheRowsOfTheNetworksThatRemain_whenOneVanishedBetweenListAndInspect() {
        networks(OLD + "|0|7|main|temper-7-main\n");
        runner.on(INSPECT, 1, OLD + "|0|7|main|temper-7-main\n", "Error response from daemon: No such network: n3");

        ok(sweep().sweep().await());

        assertThat(removed()).containsExactly("temper-7-main");
    }

    @Test
    void sweep_removesNothing_whenInspectFailsForAnyOtherReason() {
        networks(OLD + "|0|7|main|temper-7-main\n");
        runner.on(INSPECT, 1, OLD + "|0|7|main|temper-7-main\n", "Cannot connect to the Docker daemon");

        ok(sweep().sweep().await());

        assertThat(removed()).isEmpty();
    }
}
