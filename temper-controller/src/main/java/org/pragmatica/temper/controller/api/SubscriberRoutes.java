package org.pragmatica.temper.controller.api;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.events.EventFeed;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.protocol.api.SelectorChange;
import org.pragmatica.temper.protocol.api.SubscriberView;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.SubscriberRow;

import static org.pragmatica.temper.controller.http.Route.route;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// `PUT /api/v1/subscribers/{name}/selectors` (m2-design §2.3, WP-C), body [SelectorChange] `{add, remove}` with
/// selectors in wire form: 200 [SubscriberView] (the subscriber after the change; created at the long-poll's cursor
/// rule when absent, so selectors can be set before the first `watch`) · 400 (a bad name, or a selector without its
/// prefix or that does not parse, judged before any lookup) · 404 (an added `job:` names no job).
///
/// A WRITE route, as every non-GET ([Route#route]). Every added job is looked up before the change: jobs are never
/// deleted, so a job that exists at the check still exists when the change commits. A removed job is not looked up,
/// since removing what is not there changes nothing. The change itself is one store statement (`EventStore.selectors`).
public record SubscriberRoutes(Responses responses, JsonMapper mapper, EventFeed feed, JobStore jobs) {
    /// The parsed body.
    record Change(List<Selector> add, List<Selector> remove) {}

    public static SubscriberRoutes subscriberRoutes(Responses responses,
                                                    JsonMapper mapper,
                                                    EventFeed feed,
                                                    JobStore jobs) {
        return new SubscriberRoutes(responses, mapper, feed, jobs);
    }

    public List<Route> routes() {
        return List.of(route(HttpMethod.PUT, "subscribers/{name}/selectors", this::changeSelectors));
    }

    /// The name and the body are judged first, together (400), so a bad request never reaches the job lookups (404).
    private Promise<ApiResponse> changeSelectors(ApiRequest request) {
        return Result.all(EventFeed.subscriberName(request.param("name").or("")),
                          change(request))
                     .async()
                     .flatMap(this::changed)
                     .map(row -> responses.ok(subscriberView(row)))
                     .mapError(SubscriberRoutes::firstOf)
                     .mapError(ApiRoutes::classify);
    }

    /// `Result.all` over the name and both lists nests composites, whose message is a stack of `Composite:` headers;
    /// the 400 answers the first reason alone, the failure itself (not a translation of it), so the client fixes one
    /// thing at a time [G].
    private static Cause firstOf(Cause cause) {
        return switch (cause) {
            case Causes.CompositeCause composite -> Option.from(composite.stream().findFirst()).map(SubscriberRoutes::firstOf).or(cause);
            default -> cause;
        };
    }

    private Promise<SubscriberRow> changed(String name, Change change) {
        return withKnownJobs(change).flatMap(known -> feed.selectors(name, known.add(), known.remove()));
    }

    private Result<Change> change(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                SelectorChange.class)
                     .flatMap(SubscriberRoutes::present)
                     .flatMap(SubscriberRoutes::change);
    }

    /// The body `null` decodes to no object at all: a 400, as any body that does not parse.
    private static Result<SelectorChange> present(SelectorChange body) {
        return Option.option(body).toResult(invalid("selectors body").apply("null"));
    }

    private static Result<Change> change(SelectorChange body) {
        return Result.all(body.additions(),
                          body.removals())
                     .map(Change::new);
    }

    /// The added jobs are read one after another, so the first missing one is the failure itself, a
    /// [StoreError.JobNotFound] (404); a fork-join would collect it into a composite, which classifies as a 500.
    private Promise<Change> withKnownJobs(Change change) {
        return known(change.add().stream().flatMap(SubscriberRoutes::jobOf).toList()).map(_ -> change);
    }

    private Promise<Unit> known(List<JobId> jobs) {
        return jobs.isEmpty()
               ? Promise.unitPromise()
               : existing(jobs.getFirst()).flatMap(_ -> known(jobs.subList(1, jobs.size())));
    }

    private static Stream<JobId> jobOf(Selector selector) {
        return switch (selector) {
            case Selector.JobSelector job -> Stream.of(job.job());
            case Selector.ShaSelector _, Selector.RefSelector _ -> Stream.empty();
        };
    }

    private Promise<JobRow> existing(JobId job) {
        return jobs.job(job)
                   .flatMap(row -> row.async(StoreError.JobNotFound.FACTORY.apply(job)));
    }

    private static SubscriberView subscriberView(SubscriberRow row) {
        return SubscriberView.subscriberView(row.name(),
                                             row.selectors().stream().map(Selector::wire).toList(),
                                             row.cursor());
    }
}
