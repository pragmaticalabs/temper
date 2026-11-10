package org.pragmatica.temper.controller.t2;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.temper.store.api.ConsumedGo;
import org.pragmatica.temper.store.api.InvalidatedGo;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.NewVerdict;
import org.pragmatica.temper.store.api.RecordedOutcome;
import org.pragmatica.temper.store.api.RegisteredVerdict;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;


/// The events a T2 route's transaction emits, `source=controller`, `catchUp=false`, payload keys
/// in the order.
public sealed interface T2Events {
    String VERDICT_REGISTERED = "verdict-registered";
    String GO_INVALIDATED = "go-invalidated";
    String VERDICT_CHANGED = "verdict-changed";
    String SIBLING_CONSUMED = "sibling-consumed";
    String ROUTE_TEMPER_MERGE = "temper-merge";

    /// A verdict's: `verdict-registered`, then one `go-invalidated verdict-changed` per GO it invalidated (no
    /// `causeSeq`: the cause is this transaction, not a fleet row).
    static List<NewFleetEvent> verdictEvents(Instant now, NewVerdict verdict, RegisteredVerdict registered) {
        return Stream.concat(Stream.of(registeredEvent(now, verdict, registered)),
                             registered.invalidated().stream().map(go -> invalidatedEvent(now, verdict, go)))
                     .toList();
    }

    private static NewFleetEvent registeredEvent(Instant now, NewVerdict verdict, RegisteredVerdict registered) {
        var payload = JsonNodeFactory.instance.objectNode()
                                              .put("verdict",
                                                   verdict.verdict())
                                              .put("by",
                                                   verdict.by())
                                              .put("base",
                                                   verdict.base())
                                              .put("id",
                                                   registered.id())
                                              .put("reportSha256",
                                                   registered.reportSha256());

        return event(now,
                     VERDICT_REGISTERED,
                     verdict.repo() + "#" + verdict.pr() + "@" + verdict.sha(),
                     carried(payload, verdict.basis()));
    }

    private static ObjectNode carried(ObjectNode payload, NewVerdict.Basis basis) {
        return switch (basis) {
            case NewVerdict.Basis.Carried carried -> payload.put("carriedFrom", carried.from());
            case NewVerdict.Basis.Report _ -> payload;
        };
    }

    private static NewFleetEvent invalidatedEvent(Instant now, NewVerdict verdict, InvalidatedGo go) {
        return event(now,
                     GO_INVALIDATED,
                     verdict.repo() + "#" + verdict.pr(),
                     JsonNodeFactory.instance.objectNode()
                                             .put("go",
                                                  go.id())
                                             .put("sha",
                                                  go.sha())
                                             .put("base",
                                                  go.base())
                                             .put("reason", VERDICT_CHANGED));
    }

    /// A consume's: one `go-invalidated sibling-consumed` per sibling it invalidated (no `causeSeq`), subject the
    /// sibling's own PR.
    static List<NewFleetEvent> consumeEvents(Instant now, ConsumedGo consumed) {
        return consumed.invalidated()
                       .stream()
                       .map(go -> event(now,
                                        GO_INVALIDATED,
                                        consumed.basis().repo() + "#" + go.pr(),
                                        JsonNodeFactory.instance.objectNode()
                                                                .put("go",
                                                                     go.id())
                                                                .put("sha",
                                                                     go.sha())
                                                                .put("base",
                                                                     go.base())
                                                                .put("reason", SIBLING_CONSUMED)))
                       .toList();
    }

    /// An outcome's: for a `merged` this transaction recorded, `merged` (route `temper-merge`) first, then
    /// `queue-advanced` when the PR left a queue; nothing otherwise.
    static List<NewFleetEvent> outcomeEvents(Instant now, RecordedOutcome recorded) {
        return recorded.merge()
                       .map(merge -> mergeEvents(now,
                                                 recorded.id(),
                                                 merge))
                       .or(List.of());
    }

    private static List<NewFleetEvent> mergeEvents(Instant now, String id, RecordedOutcome.Merge merge) {
        return Stream.concat(Stream.of(event(now,
                                             T2Plan.MERGED,
                                             merge.basis().repo() + "#" + merge.basis().pr(),
                                             mergedPayload(id, merge))),
                             merge.dequeued()
                                  .stream()
                                  .map(gone -> event(now,
                                                     "queue-advanced",
                                                     gone.base(),
                                                     T2Plan.queuePayload(gone,
                                                                         merge.next()))))
                     .toList();
    }

    /// C8 `merged`: `go`, `sha`, `base`, `baseTipBefore` (absent when the GO recorded none), `route`.
    private static ObjectNode mergedPayload(String id, RecordedOutcome.Merge merge) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("go", id);
        payload.put("sha",
                    merge.basis().sha());
        payload.put("base",
                    merge.basis().base());
        merge.baseTipBefore().onPresent(tip -> payload.put("baseTipBefore", tip));
        payload.put("route", ROUTE_TEMPER_MERGE);

        return payload;
    }

    private static NewFleetEvent event(Instant now, String kind, String subject, ObjectNode payload) {
        return NewFleetEvent.newFleetEvent(now, T2Plan.SOURCE, kind, subject, payload.toString());
    }

    record unused() implements T2Events {}
}
