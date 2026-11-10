package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// m4-design §2.2, §12.2 `SshdHostKeyIT`: the real OpenSSH client against a real `sshd` whose key is not the pinned one.
/// - its stderr carries the lines [SshDiagnosis] reads (the texts `SshDiagnosisTest` uses, checked here verbatim);
/// - a host link to it is LOST at once (`host-key`), with `hostLost` at 10 min, so only the diagnosis can explain it.
///
/// Probe P8: [SshDiagnosis#hostKeyFailure] returns false → the link rows go red (no LOST within the wait).
class SshdHostKeyIT {
    private Sshd sshd;
    private LinkHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        sshd = new Sshd();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.link.stop();
        }
        if (sshd != null) {
            sshd.close();
        }
    }

    /// The server was recreated (K2) while `known_hosts` pins its old key (K1).
    @Test
    void realClient_changedKey_classifiedAndLostAtOnce() throws Exception {
        var k1 = sshd.dir.resolve("k1");
        Sshd.keygen(k1);
        sshd.pin(k1.resolveSibling("k1.pub"));
        var stderr = sshd.once(SshCommand.run(Sshd.HOST, LinkHarness.VERSION));

        assertThat(stderr).contains(SshDiagnosisTest.CHANGED).contains(SshDiagnosisTest.FAILED);
        assertThat(SshDiagnosis.hostKeyFailureIn(stderr)).isTrue();
        lostAtOnce();
    }

    /// `known_hosts` has no entry for the address: ssh cannot authenticate the box either.
    @Test
    void realClient_unknownKey_classifiedAndLostAtOnce() throws Exception {
        var stderr = sshd.once(SshCommand.run(Sshd.HOST, LinkHarness.VERSION));

        assertThat(stderr).contains(SshDiagnosisTest.FAILED);
        assertThat(SshDiagnosis.hostKeyFailureIn(stderr)).isTrue();
        lostAtOnce();
    }

    private void lostAtOnce() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofMinutes(10));
        harness.through = sshd.runner();
        var start = Instant.now();
        harness.link.start();

        waitFor(() -> harness.link.lost().isPresent(), "LOST (host-key)");
        assertThat(harness.link.lost().unwrap().reason()).isEqualTo("host-key");
        assertThat(Duration.between(start, harness.link.lost().unwrap().at())).isLessThan(Duration.ofSeconds(10));
    }
}
