package org.pragmatica.temper.controller.api;

import java.util.List;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.known.KnownEntry;
import org.pragmatica.temper.protocol.api.KnownAddRequest;
import org.pragmatica.temper.protocol.api.KnownDto;
import org.pragmatica.temper.store.api.KnownListing;
import org.pragmatica.temper.store.api.KnownRow;
import org.pragmatica.temper.store.api.KnownStore;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.temper.controller.http.Route.readRoute;
import static org.pragmatica.temper.controller.http.Route.route;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// The known-failure registry over the API (m2-design §4.4), the refusal surface's shape:
///
/// | Method | Path | Answer |
/// |---|---|---|
/// | GET | `known?all=true\|false` | 200 `[KnownDto]` (active only unless `all=true`), ordered by id |
/// | POST | `known` | body `KnownAddRequest` → 201 `KnownDto` · 400 (a field that does not parse) · 409 (an active entry for that `testId`) |
/// | POST | `known/{id}/expire` | 200 `KnownDto` (its `expiredAt`; an expired entry keeps its first; an unreadable row too) · 400 (not a positive id) · 404 |
///
/// A row the domain cannot read is listed and expired as `KnownDto` with `unreadable` set (ruling R-M2-15).
///
/// Expiry is a POST to `…/expire`, not spec §7's `DELETE` (ruling R-M2-9, Q-M2-11): rows are never deleted. The GET is a
/// READ route; the POSTs are WRITE (D3).
public record KnownRoutes(Responses responses, JsonMapper mapper, KnownStore known) {
    public static KnownRoutes knownRoutes(Responses responses, JsonMapper mapper, KnownStore known) {
        return new KnownRoutes(responses, mapper, known);
    }

    public List<Route> routes() {
        return List.of(readRoute("known", this::listKnown),
                       route(HttpMethod.POST, "known", this::addKnown),
                       route(HttpMethod.POST, "known/{id}/expire", this::expireKnown));
    }

    private Promise<ApiResponse> listKnown(ApiRequest request) {
        var all = request.queryParam("all").filter("true"::equalsIgnoreCase).isPresent();

        return known.list()
                    .map(rows -> knownResponse(rows, all))
                    .mapError(KnownRoutes::classify);
    }

    private ApiResponse knownResponse(List<KnownListing> rows, boolean all) {
        return responses.ok(rows.stream()
                                .filter(row -> all || row.expiredAt()
                                                         .isEmpty())
                                .map(KnownRoutes::listedDto)
                                .toList());
    }

    private Promise<ApiResponse> addKnown(ApiRequest request) {
        return entry(request).async()
                    .flatMap(known::add)
                    .map(row -> responses.json(HttpStatus.CREATED,
                                               knownDto(row)))
                    .mapError(KnownRoutes::classify);
    }

    /// The body parses into an entry, or it is a 400.
    private Result<KnownEntry> entry(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                KnownAddRequest.class)
                     .flatMap(KnownAddRequest::toDomain);
    }

    private Promise<ApiResponse> expireKnown(ApiRequest request) {
        return knownId(request).async()
                      .flatMap(known::expire)
                      .map(row -> responses.ok(listedDto(row)))
                      .mapError(KnownRoutes::classify);
    }

    private static Result<Long> knownId(ApiRequest request) {
        var raw = request.param("id").or("");

        return Number.parseLong(raw)
                     .mapError(DomainError.unparseable("known id", raw))
                     .flatMap(id -> Verify.ensure(id,
                                                  Verify.Is::positive,
                                                  invalid("known id")));
    }

    private static KnownDto knownDto(KnownRow row) {
        return KnownDto.knownDto(row.id(), row.entry(), row.createdAt(), row.expiredAt());
    }

    /// A readable row as itself; an unreadable one with its stored values and why (ruling R-M2-15).
    private static KnownDto listedDto(KnownListing listing) {
        return switch (listing) {
            case KnownListing.Readable readable -> knownDto(readable.row());
            case KnownListing.Unreadable row -> KnownDto.unreadableDto(row.id(),
                                                                       row.testId(),
                                                                       row.issue(),
                                                                       row.reason(),
                                                                       row.until(),
                                                                       row.createdBy(),
                                                                       row.createdAt(),
                                                                       row.expiredAt(),
                                                                       row.problem());
        };
    }

    /// An unknown id is 404; an active entry for the test is 409; everything else as every route classifies it.
    private static Cause classify(Cause cause) {
        return switch (cause) {
            case StoreError.KnownNotFound missing -> ApiError.NotFound.FACTORY.apply("known " + missing.id());
            case StoreError.KnownExists exists -> ApiError.Conflict.FACTORY.apply(exists.message());
            default -> ApiRoutes.classify(cause);
        };
    }
}
