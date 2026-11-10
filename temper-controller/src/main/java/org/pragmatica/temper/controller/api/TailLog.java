package org.pragmatica.temper.controller.api;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.host.HostLink;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.host.LinkError;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.UnitRow;

import static org.pragmatica.lang.Result.success;


/// `GET /jobs/{id}/log?unit=&tail=` (m0-design §6.2): the unit's log tail from its host's worker (`LogTail`, at most
/// 10 000 lines and 1 MiB). 404 when the job or unit is unknown or the unit never ran on a host; 503 when that
/// host's link is down.
@FunctionalInterface
public interface TailLog {
    int DEFAULT_LINES = 200;

    /// `unit` defaults to the job's only unit, else `main`; `lines` is clamped to 1..10 000.
    record Request(JobId job, Option<UnitName> unit, int lines) {
        public static Request request(JobId job, Option<UnitName> unit, int lines) {
            return new Request(job, unit, lines);
        }
    }

    Promise<Message.LogChunk> execute(Request request);

    static TailLog tailLog(JobStore jobs, HostLinks links) {
        return request -> tail(jobs, links, request);
    }

    private static Promise<Message.LogChunk> tail(JobStore jobs, HostLinks links, Request request) {
        return jobs.job(request.job())
                   .flatMap(row -> row.async(ApiError.NotFound.FACTORY.apply("job " + request.job())))
                   .flatMap(row -> unitOf(row,
                                          request.unit()).async())
                   .flatMap(unit -> fromHost(links, unit, request));
    }

    /// No `unit` given: the job's only unit (a Gate's is `gate`), else `main`; a miss names the job's units.
    private static Result<UnitRow> unitOf(JobRow row, Option<UnitName> unit) {
        var name = unit.or(() -> soleUnit(row).or(UnitName.MAIN));

        return Option.from(row.units().stream().filter(candidate -> candidate.name()
                                                                             .equals(name)).findFirst()).toResult(ApiError.NotFound.FACTORY.apply("unit " + name
                                                                                                                                                 + " (units: " + unitNames(row)
                                                                                                                                                 + ")"));
    }

    private static Option<UnitName> soleUnit(JobRow row) {
        return row.units()
                  .size() == 1
               ? Option.some(row.units().getFirst().name())
               : Option.none();
    }

    private static String unitNames(JobRow row) {
        return String.join(", ",
                           row.units().stream().map(candidate -> candidate.name()
                                                                          .value()).toList());
    }

    private static Promise<Message.LogChunk> fromHost(HostLinks links, UnitRow unit, Request request) {
        return unit.host()
                   .toResult(ApiError.NotFound.FACTORY.apply("log of unit " + unit.name()))
                   .flatMap(host -> linkOf(links, host))
                   .async()
                   .flatMap(link -> request(link, unit, request));
    }

    private static Result<HostLink> linkOf(HostLinks links, HostName host) {
        return links.link(host)
                    .toResult(ApiError.NotFound.FACTORY.apply("host " + host));
    }

    /// The request names the unit's current attempt as the store has it ([HostLink#currentAttempt], protocol v3).
    private static Promise<Message.LogChunk> request(HostLink link, UnitRow unit, Request request) {
        return link.currentAttempt(UnitRef.unitRef(request.job(),
                                                   unit.name()))
                   .flatMap(attempt -> link.request(logTail(request, unit, attempt)))
                   .mapError(TailLog::unavailable)
                   .flatMap(reply -> chunk(reply).async());
    }

    private static Message.LogTail logTail(Request request, UnitRow unit, int attempt) {
        return new Message.LogTail(request.job().value(),
                                   unit.name().value(),
                                   attempt,
                                   Math.clamp(request.lines(), 1, Message.MAX_LOG_LINES));
    }

    private static Cause unavailable(Cause cause) {
        return switch (cause) {
            case LinkError.Down _ -> ApiError.Unavailable.of("host link").apply(cause);
            default -> cause;
        };
    }

    private static Result<Message.LogChunk> chunk(Message reply) {
        return switch (reply) {
            case Message.LogChunk chunk -> success(chunk);
            default -> LinkError.Unexpected.FACTORY.apply("LogChunk",
                                                          reply.getClass().getSimpleName()).result();
        };
    }
}
