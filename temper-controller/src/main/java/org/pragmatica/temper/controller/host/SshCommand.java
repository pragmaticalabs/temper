package org.pragmatica.temper.controller.host;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.protocol.evidence.EvidenceRequest;


/// The ssh argv of a host link (m0-design §6.4) and of an evidence pull (m1-design §3.2). Pure. The forced command on
/// the host (`worker-launch`) reads `$SSH_ORIGINAL_COMMAND`, which is exactly `run <ver>`, `install <ver> <sha256>`
/// or `evidence <job> <unit> small|full`; `<ver>` is validated by the config loader against the same alphabet
/// `worker-launch` accepts, and the evidence arguments come from [EvidenceRequest].
///
/// `ConnectTimeout=15` (m4-design F7, a marked proposal): a blackholed address ends each connect, banner exchange
/// included, after 15 s instead of the OS TCP timeout or the link's `Hello` timeout.
public sealed interface SshCommand {
    static List<String> run(ControllerConfig.Host host, String version) {
        return ssh(host, List.of("run", version));
    }

    static List<String> install(ControllerConfig.Host host, String version, String sha256) {
        return ssh(host, List.of("install", version, sha256));
    }

    /// A second, read-only session: `worker-launch` writes the tarball to stdout (maintainer ruling M1-2).
    static List<String> evidence(ControllerConfig.Host host, EvidenceRequest request) {
        return ssh(host, request.verbArgs());
    }

    /// A bb host's ticket listing over the host's own key: the read-only `tickets` verb of
    /// `worker-launch`.
    static List<String> tickets(ControllerConfig.Host host) {
        return ssh(host, List.of("tickets"));
    }

    /// A bb host that is not a `[[hosts]]` host (`[fleet] bbHosts`, `<host>=<user>@<address>`), read over the kiln scan
    /// key: the `tickets` verb of `kiln-scan`.
    static List<String> ticketsScan(ControllerConfig.BbLink link, Path scanKey, Path knownHosts) {
        return List.of("ssh",
                       "-i",
                       scanKey.toString(),
                       "-o",
                       "BatchMode=yes",
                       "-o",
                       "ConnectTimeout=15",
                       "-o",
                       "UserKnownHostsFile=" + knownHosts,
                       "-o",
                       "StrictHostKeyChecking=yes",
                       link.user() + "@" + link.address(),
                       "tickets");
    }

    private static List<String> ssh(ControllerConfig.Host host, List<String> remote) {
        return Stream.concat(Stream.of("ssh",
                                       "-T",
                                       "-o",
                                       "BatchMode=yes",
                                       "-o",
                                       "ConnectTimeout=15",
                                       "-o",
                                       "ServerAliveInterval=15",
                                       "-o",
                                       "ServerAliveCountMax=3",
                                       "-o",
                                       "StrictHostKeyChecking=yes",
                                       "-o",
                                       "UserKnownHostsFile=" + host.knownHosts(),
                                       "-o",
                                       "IdentitiesOnly=yes",
                                       "-i",
                                       host.key().toString(),
                                       host.user() + "@" + host.address()),
                             remote.stream())
                     .toList();
    }

    record unused() implements SshCommand {}
}
