package org.pragmatica.temper.cli.tickets;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.command.CliCommand;
import org.pragmatica.temper.cli.transport.HttpTransport;
import org.pragmatica.temper.domain.exit.ExitCode;


/// `temper tickets [--host H] [--done N] [--json]`: one READ call, then the listing. Exit 0 when
/// the Mac source is fresh and every collected host is `ok`; 4 when the Mac source is stale or a host is UNREACHABLE,
/// with everything still printed (NOT-COLLECTED is configuration and RETIRED is a decision; neither sets it). `--json` prints the reply verbatim.
/// The failures are the transport's and [TicketsApi]'s: 64 usage or an unknown host, 69 unavailable or unreadable, 77
/// auth, 13 version skew.
public sealed interface TicketsVerb {
    static Promise<Outcome> tickets(CliCommand.Tickets command, HttpTransport http, JsonMapper json) {
        return TicketsApi.ticketsApi(http, json)
                         .tickets(command.host(),
                                  command.done())
                         .map(fetched -> outcome(fetched,
                                                 command.json()));
    }

    private static Outcome outcome(TicketsApi.Fetched fetched, boolean asJson) {
        return Outcome.outcome(asJson
                               ? fetched.body().stripTrailing()
                               : TicketsLines.render(fetched.reply()),
                               fetched.reply().isStale()
                               ? ExitCode.SOURCE_STALE
                               : ExitCode.ACCEPTED);
    }

    record unused() implements TicketsVerb {}
}
