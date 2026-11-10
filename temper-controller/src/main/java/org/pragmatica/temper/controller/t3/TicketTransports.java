package org.pragmatica.temper.controller.t3;

import java.util.Map;

import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.host.SshCommand;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// The ssh transports of the ticket collection, one per collected host.
public final class TicketTransports {
    private TicketTransports() {}

    /// A `[[hosts]]` host: the `tickets` verb of its `worker-launch`, over the host's own key and known_hosts.
    public static TicketTransport hostLink(ProcessRunner runner, ControllerConfig.Host host) {
        return CommandTransport.commandTransport(runner,
                                                 _ -> CommandTransport.inheriting(SshCommand.tickets(host), Map.of()),
                                                 true);
    }

    /// A `[fleet] bbHosts` host in the `=` form: the `tickets` verb of `kiln-scan`, over the scan key.
    public static TicketTransport scanKey(ProcessRunner runner,
                                          ControllerConfig.BbLink link,
                                          ControllerConfig.Fleet fleet) {
        return CommandTransport.commandTransport(runner,
                                                 _ -> CommandTransport.inheriting(SshCommand.ticketsScan(link,
                                                                                                         fleet.bbScanKey()
                                                                                                              .unwrap(),
                                                                                                         fleet.bbKnownHosts()
                                                                                                              .unwrap()),
                                                                                  Map.of()),
                                                 true);
    }
}
