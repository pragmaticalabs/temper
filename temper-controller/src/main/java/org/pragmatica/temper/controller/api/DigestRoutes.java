package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.controller.digest.Digest;
import org.pragmatica.temper.controller.host.HostLink;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.host.UnitAttempt;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.protocol.api.DigestText;
import org.pragmatica.temper.protocol.api.DigestView;
import org.pragmatica.temper.store.api.DigestQuery;
import org.pragmatica.temper.store.api.EventCursor;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.temper.controller.http.Route.readRoute;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// `GET /api/v1/digest?since=<cursor>&max=N[&format=text]` (dashboard design §2), behind the same bearer token as every
/// route: 200 `DigestView` as JSON, or its [DigestText] form (`text/plain`) with `format=text`; 400 when `since` is not
/// a cursor (`<seq>.<token>`, [EventCursor]; a bare `seq` is refused), `max` is outside 1..1000 (default 10), `format` is
/// neither `json` nor `text`, or `since` is ahead of the event log (`StoreError.CursorAhead`); 409 `CURSOR_FOREIGN` when
/// `since` is above the floor and the event it names is missing or another one (`StoreError.CursorForeign`).
///
/// A READ route (D3): the read token may call it. It writes nothing: the store reads in one READ ONLY transaction,
/// and the cursor is the caller's, unlike `GET subscribers/{name}/next`, which creates its subscriber (a WRITE route
/// for that reason). The heads-moved part of the design is left out: the mirror is fetched only by a submit and
/// records no fetch time, so an answer could not say how current it is.
public record DigestRoutes(Responses responses, Digest digest, HostLinks links, Duration startBound) {
    /// The answer's form.
    private enum Format {
        JSON,
        TEXT
    }

    public static DigestRoutes digestRoutes(Responses responses, Digest digest, HostLinks links, Duration startBound) {
        return new DigestRoutes(responses, digest, links, startBound);
    }

    /// The host links' state as the digest reads it: every configured host in name order, with its link's state, its
    /// cap and the foreign reactors of its latest facts.
    public static Digest.Links links(HostLinks links) {
        return () -> links.all()
                          .stream()
                          .map(DigestRoutes::linkedHost)
                          .toList();
    }

    public List<Route> routes() {
        return List.of(readRoute("digest", this::getDigest));
    }

    /// The unrecorded starts are snapshotted BEFORE the store reads, as for every job view ([ApiRoutes]): a start
    /// recorded in between is then in the rows, never missed by both.
    private Promise<ApiResponse> getDigest(ApiRequest request) {
        var unrecorded = links.unrecordedStarts();

        return Result.all(query(request),
                          format(request))
                     .async()
                     .flatMap((query, format) -> answered(query, format, unrecorded))
                     .mapError(ApiRoutes::classify);
    }

    private Promise<ApiResponse> answered(DigestQuery query, Format format, Set<UnitAttempt> unrecorded) {
        return digest.execute(Digest.Request.request(query))
                     .map(response -> DigestViews.digestView(response, startBound, unrecorded))
                     .map(view -> answer(view, format));
    }

    private ApiResponse answer(DigestView view, Format format) {
        return switch (format) {
            case JSON -> responses.ok(view);
            case TEXT -> Responses.text(HttpStatus.OK, DigestText.render(view));
        };
    }

    private static Result<DigestQuery> query(ApiRequest request) {
        return Result.all(Values.optional(request.queryParam("since"),
                                          EventCursor::eventCursor),
                          Values.optional(request.queryParam("max"),
                                          raw -> Number.parseInt(raw).mapError(DomainError.unparseable("max", raw))))
                     .flatMap((since, max) -> DigestQuery.digestQuery(since,
                                                                      max.or(DigestQuery.DEFAULT_MAX)));
    }

    private static Result<Format> format(ApiRequest request) {
        return request.queryParam("format")
                      .map(DigestRoutes::format)
                      .or(() -> success(Format.JSON));
    }

    private static Result<Format> format(String raw) {
        return switch (raw) {
            case "json" -> success(Format.JSON);
            case "text" -> success(Format.TEXT);
            default -> invalid("format").apply(raw).result();
        };
    }

    private static Digest.LinkedHost linkedHost(HostLink link) {
        return Digest.LinkedHost.linkedHost(link.name(),
                                            link.state().wire(),
                                            link.config().cap(),
                                            link.facts().map(HostFacts::foreignReactors).or(0),
                                            Digest.Marks.marks(link.lost(),
                                                               link.config().sharedCpu(),
                                                               link.config().retireAt()));
    }
}
