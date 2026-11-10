package org.pragmatica.kiln.service.scan;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.kiln.service.config.KilnConfig;


/// The ssh argv of one `kiln-scan` verb (kiln-f1-design §1.2, copied from temper's `controller/host/SshCommand`). Pure,
/// and an argv, never a shell string: ssh joins the remote words with single spaces into the request, and the host's
/// forced command (`command="exec ~/tools/kiln-scan"`) receives it as `SSH_ORIGINAL_COMMAND`, which `kiln-scan`
/// validates word by word (§3.2). No shell expands a `~` or `*` of a root on either side.
///
/// `ConnectTimeout=15` ends a connect to a blackholed address after 15 s; `ServerAliveInterval=15` with
/// `ServerAliveCountMax=3` ends a session whose host went silent after 45 s. Both end in ssh's own exit 255.
public sealed interface SshCommand {
    static List<String> scan(KilnConfig.Host host, List<String> words) {
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
                             words.stream())
                     .toList();
    }

    record unused() implements SshCommand {}
}
