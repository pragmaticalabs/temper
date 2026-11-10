package org.pragmatica.temper.worker.docker;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.ContainerStatus;

import static org.assertj.core.api.Assertions.assertThat;

class DockerOutputTest {
    private static final String ID_A = "a".repeat(64);
    private static final String ID_B = "0123456789abcdef".repeat(4);

    @Test
    void containerStatus_mapsDockerStatesForReattach() {
        assertThat(DockerOutput.containerStatus("running\n")).isEqualTo(ContainerStatus.RUNNING);
        assertThat(DockerOutput.containerStatus("paused")).isEqualTo(ContainerStatus.RUNNING);
        assertThat(DockerOutput.containerStatus("exited")).isEqualTo(ContainerStatus.EXITED);
        assertThat(DockerOutput.containerStatus("dead")).isEqualTo(ContainerStatus.EXITED);
        assertThat(DockerOutput.containerStatus("created")).isEqualTo(ContainerStatus.ABSENT);
    }

    @Test
    void ipv4Gateway_skipsAnIpv6GatewayListedFirst() {
        assertThat(DockerOutput.ipv4Gateway("fd00:dead:beef::1 192.0.2.1 \n")).isEqualTo(Option.some("192.0.2.1"));
        assertThat(DockerOutput.ipv4Gateway("fd00::1 ")).isEqualTo(Option.none());
        assertThat(DockerOutput.ipv4Gateway("")).isEqualTo(Option.none());
    }

    @Test
    void containerIds_keepsOnlyFullIds() {
        assertThat(DockerOutput.containerIds(ID_A + "\n" + ID_B + "\nabc\n\n")).containsExactlyInAnyOrder(ID_A, ID_B);
    }

    @Test
    void testcontainers_parsesIdAndSession_withAndWithoutLabel() {
        assertThat(DockerOutput.testcontainers(ID_A + " 3f1c-session\n" + ID_B + " \n\n")).containsExactly(TcContainer.tcContainer(ID_A, "3f1c-session"),
                                                                                                            TcContainer.tcContainer(ID_B, ""));
    }

    @Test
    void exitCode_readsTheFirstIntegerLine() {
        assertThat(DockerOutput.exitCode("137\n")).isEqualTo(Option.some(137));
        assertThat(DockerOutput.exitCode("garbage")).isEqualTo(Option.none());
        assertThat(DockerOutput.exitCode("99999999999")).isEqualTo(Option.none());
    }

    @Test
    void noSuchObject_recognisesDockerAbsenceMessages() {
        assertThat(DockerOutput.noSuchObject("Error: No such object: temper-1-main")).isTrue();
        assertThat(DockerOutput.noSuchObject("Error response from daemon: No such container: x")).isTrue();
        assertThat(DockerOutput.noSuchObject("Error response from daemon: network temper-1-main not found")).isTrue();
        assertThat(DockerOutput.noSuchObject("Error response from daemon: No such image: temper/runner:x")).isTrue();
        assertThat(DockerOutput.noSuchObject("Cannot connect to the Docker daemon")).isFalse();
        assertThat(DockerOutput.noSuchObject("exec: \"jcmd\": executable file not found in $PATH")).isFalse();
    }

    private static final Instant CUTOFF = Instant.ofEpochSecond(10_000);

    @Test
    void orphanNetworks_keepsOnlyEmptyNetworksCreatedBeforeTheCutoff() {
        var rows = "9999|0|7|main|temper-7-main\n"
                   + "10000|0|8|main|temper-8-main\n"
                   + "9000|1|9|main|temper-9-main\n"
                   + "9000|0||main|temper-nolabel-main\n";

        assertThat(DockerOutput.orphanNetworks(rows, CUTOFF)).containsExactly(OrphanNetwork.orphanNetwork("temper-7-main", "7", "main"),
                                                                             OrphanNetwork.orphanNetwork("temper-nolabel-main", "", "main"));
    }

    @Test
    void orphanNetworks_dropsALineItCannotParse_neverGuessing() {
        assertThat(DockerOutput.orphanNetworks("garbage\n\nx|0|7|main|n\n9000|y|7|main|n\n9000|0|7|main\n", CUTOFF)).isEmpty();
    }

    @Test
    void networkIds_splitsOnWhitespace() {
        assertThat(DockerOutput.networkIds("a1b2\nc3d4 \n\n")).containsExactly("a1b2", "c3d4");
        assertThat(DockerOutput.networkIds("")).isEmpty();
    }
}
