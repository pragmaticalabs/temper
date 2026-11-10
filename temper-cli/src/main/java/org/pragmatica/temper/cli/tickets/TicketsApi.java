package org.pragmatica.temper.cli.tickets;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.transport.HttpCall;
import org.pragmatica.temper.cli.transport.HttpReply;
import org.pragmatica.temper.cli.transport.HttpTransport;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// `GET /fleet/tickets[?host=][&done=]` over the version-checked [HttpTransport].
///
/// Statuses: `200` the reply, with its body kept for `--json`; `401` and `403` exit 77; `400` [CliError.Rejected] and
/// `404` [CliError.NotFound] (`NO_HOST`, or a controller older than T3) exit 64; anything else, an answer that does not
/// decode, or a `v` other than 1 exit 69; the transport's own (unreachable 69, version skew 13) pass as they are.
record TicketsApi(HttpTransport http, JsonMapper json) {
    static final String PATH = "/fleet/tickets";
    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;

    /// The reply as decoded, and the body it was decoded from.
    record Fetched(String body, TicketsWire.Reply reply) {}

    static TicketsApi ticketsApi(HttpTransport http, JsonMapper json) {
        return new TicketsApi(http, json);
    }

    Promise<Fetched> tickets(Option<String> host, Option<Integer> done) {
        var query = Stream.concat(host.map(value -> "host=" + URLEncoder.encode(value, StandardCharsets.UTF_8)).stream(),
                                  done.map(value -> "done=" + value).stream())
                          .collect(Collectors.joining("&"));
        var path = PATH + (query.isEmpty()
                           ? ""
                           : "?" + query);

        return http.exchange(HttpCall.httpCall("GET",
                                               path,
                                               none()))
                   .flatMap(reply -> fetched(path, reply).async());
    }

    private Result<Fetched> fetched(String path, HttpReply reply) {
        return reply.status() == OK
               ? decoded(path, reply.body())
               : failure(path, reply).result();
    }

    private Result<Fetched> decoded(String path, String body) {
        return json.readString(body, TicketsWire.Reply.class)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause))
                   .flatMap(reply -> checked(path, reply))
                   .map(reply -> new Fetched(body, reply));
    }

    /// Version, host status, ticket directory and process fact are closed sets; a value outside them is not printed as if
    /// it were one of them.
    private static Result<TicketsWire.Reply> checked(String path, TicketsWire.Reply reply) {
        return ! reply.isComplete()
               ? unreadable(path, "a reply without its mac or host fields")
               : reply.v() != TicketsWire.VERSION
                 ? unreadable(path, "reply version " + reply.v() + " is not " + TicketsWire.VERSION)
                 : reply.hosts()
                        .stream()
                        .allMatch(TicketsApi::isKnown)
                   ? success(reply)
                   : unreadable(path, "a host status, ticket directory or process fact it does not know");
    }

    private static boolean isKnown(TicketsWire.Host host) {
        return TicketsWire.STATUSES.contains(host.status())
               && host.tickets()
                      .stream()
                      .allMatch(ticket -> TicketsWire.DIRS.contains(ticket.dir()) && TicketsWire.PROCESSES.contains(ticket.process()));
    }

    private static Result<TicketsWire.Reply> unreadable(String path, String detail) {
        return CliError.BadPayload.FACTORY.apply(path, cause(detail)).result();
    }

    private Cause failure(String path, HttpReply reply) {
        return switch (reply.status()) {
            case UNAUTHORIZED -> CliError.General.UNAUTHORIZED;
            case FORBIDDEN -> CliError.General.FORBIDDEN;
            case BAD_REQUEST -> CliError.Rejected.FACTORY.apply(errorText(reply));
            case NOT_FOUND -> CliError.NotFound.FACTORY.apply(path + " (" + errorText(reply) + ")");
            default -> CliError.UnexpectedStatus.FACTORY.apply(reply.status(), path);
        };
    }

    /// The body's `error` text; a body without one is shown raw.
    private String errorText(HttpReply reply) {
        return json.readTree(reply.body())
                   .option()
                   .flatMap(TicketsApi::errorField)
                   .or(() -> reply.body()
                                  .strip());
    }

    private static Option<String> errorField(JsonNode node) {
        return Option.from(node.path("error").stringValueOpt());
    }
}
