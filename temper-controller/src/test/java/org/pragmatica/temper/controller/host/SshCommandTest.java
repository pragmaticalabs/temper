package org.pragmatica.temper.controller.host;

import java.nio.file.Path;

import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.store.api.HostName;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// m4-design F7: every ssh session of a host (link, install, evidence) bounds its connect with `ConnectTimeout=15`.
class SshCommandTest {
    private static final ControllerConfig.Host HOST = ControllerConfig.Host.host(HostName.hostName("cloudbb-3").unwrap(),
                                                                                 "203.0.113.164",
                                                                                 "builder",
                                                                                 Path.of("/k"),
                                                                                 Path.of("/kh"),
                                                                                 3,
                                                                                 18432,
                                                                                 false);

    @Test
    void run_boundsConnect_connectTimeout15() {
        var argv = SshCommand.run(HOST, "0.1.0");

        assertThat(argv).containsSubsequence("-o", "ConnectTimeout=15");
        assertThat(argv.indexOf("ConnectTimeout=15")).isLessThan(argv.indexOf("builder@203.0.113.164"));
    }

    @Test
    void install_boundsConnect_connectTimeout15() {
        assertThat(SshCommand.install(HOST, "0.1.0", "ab")).containsSubsequence("-o", "ConnectTimeout=15");
    }

    /// The host's own key and known_hosts, the verb last.
    @Test
    void tickets_isTheHostsOwnSessionWithTheTicketsVerb() {
        var argv = SshCommand.tickets(HOST);

        assertThat(argv).containsSubsequence("-o", "ConnectTimeout=15");
        assertThat(argv).containsSubsequence("-o", "UserKnownHostsFile=/kh");
        assertThat(argv).containsSubsequence("-i", "/k", "builder@203.0.113.164", "tickets");
        assertThat(argv.getLast()).isEqualTo("tickets");
    }

    /// Exactly the documented argv for a `bbHosts` entry.
    @Test
    void ticketsScan_isTheScanKeyWithBatchModeAndStrictHostKeys() {
        var argv = SshCommand.ticketsScan(ControllerConfig.BbLink.bbLink("builder", "192.0.2.72"),
                                          Path.of("/scan"),
                                          Path.of("/kh"));

        assertThat(argv).containsExactly("ssh",
                                         "-i",
                                         "/scan",
                                         "-o",
                                         "BatchMode=yes",
                                         "-o",
                                         "ConnectTimeout=15",
                                         "-o",
                                         "UserKnownHostsFile=/kh",
                                         "-o",
                                         "StrictHostKeyChecking=yes",
                                         "builder@192.0.2.72",
                                         "tickets");
    }
}
