package org.pragmatica.temper.controller.t2;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.DetectorBatch;
import org.pragmatica.temper.store.api.DetectorInput;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.GoInvalidation;
import org.pragmatica.temper.store.api.MergeFacts;
import org.pragmatica.temper.store.api.MergeKey;
import org.pragmatica.temper.store.api.MergeRow;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.OpenGo;
import org.pragmatica.temper.store.api.QueueEntry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// What one `t2:detector` batch does, as data the store writes in one transaction. Pure,
/// so every branch is tested without Postgres (as [org.pragmatica.temper.controller.fleet.KilnPlan]).
///
/// - **Gap** (retention deleted rows above the cursor): `gap t2:detector {from,to}`, the cursor moves to the floor;
///   the rows missed are a declared loss.
/// - **Rows**: the cursor moves to the last seq read. A row matches a rule by its kind ([#sightings], [GoRules]); the
///   detector's own kinds (`merged`, `ungated-merge`, `queue-advanced`, `go-invalidated`, `gap`) match none.
/// - **Check and base kinds** ([#invalidations]): an outstanding GO a row after its `fleet_seq` matches (`checks-changed`, `base-moved`) is invalidated with the row's seq as its cause, and `go-invalidated` emitted.
/// - **`pr-state` → `merged`** ([#plan]): a merge not recorded yet becomes a `merges` row and `merged` when a GO with
///   outcome `merging`/`merged` gates it, else `ungated-merge` (R3-2: a GO consumed and then refused, or failed in
///   `gh`, does not gate a hand merge); a PR in a queue leaves it, the later positions move down, and
///   `queue-advanced` names the new head. A merge recorded already (the `temper merge` route got there first, or kiln
///   replayed it) does nothing more.
/// - A catch-up `pr-state` row observed before the detector's `started_at` is skipped (a first kiln pull or a kiln
///   rewind replays kiln's whole retention as catch-up); every other row is processed, and `merges` dedupes a replay.
public sealed interface T2Plan {
    String DETECTOR = "t2:detector";
    String SOURCE = "controller";
    int PERIOD_S = 5;
    int LIMIT = 500;
    String ROUTE_KILN = "kiln";
    String MERGED = "merged";
    Pattern SHA40 = Pattern.compile("^[0-9a-f]{40}$");

    /// A merge a `pr-state` row reports: the row's seq, the key (`sha` = `payload.head`, or `unknown` when absent or
    /// not 40 lower-case hex), `base` (`unknown` when absent) and kiln's `mergedBy`/`mergedAt` when it sent them.
    record Sighting(long seq, MergeKey key, String base, Option<String> mergedBy, Option<Instant> mergedAt) {
        public static Sighting sighting(long seq,
                                        MergeKey key,
                                        String base,
                                        Option<String> mergedBy,
                                        Option<Instant> mergedAt) {
            return new Sighting(seq, key, base, mergedBy, mergedAt);
        }
    }

    /// A sighting with what the store holds about it, read under the T2 lock.
    record Observed(Sighting sighting, MergeFacts facts) {
        public static Observed observed(Sighting sighting, MergeFacts facts) {
            return new Observed(sighting, facts);
        }
    }

    /// The merges a batch reports, in seq order (none for a gap).
    static List<Sighting> sightings(DetectorInput input) {
        return switch (input.pending()) {
            case FleetPending.Gap _ -> List.of();
            case FleetPending.Events events -> events.rows().stream().flatMap(row -> sighting(row, input.startedAt()).stream()).toList();
        };
    }

    /// The batch for `input`, with the facts of each of its [#sightings], in the same order.
    static DetectorBatch plan(DetectorInput input, List<Observed> observed, Instant now) {
        return switch (input.pending()) {
            case FleetPending.Gap gap -> DetectorBatch.detectorBatch(gap.to(),
                                                                     now,
                                                                     List.of(),
                                                                     List.of(),
                                                                     List.of(event(now, "gap", DETECTOR, gapPayload(gap))));
            case FleetPending.Events events -> eventsBatch(input, events, observed, now);
        };
    }

    /// The GO invalidations first (their events in cause order), then the merges.
    private static DetectorBatch eventsBatch(DetectorInput input,
                                             FleetPending.Events events,
                                             List<Observed> observed,
                                             Instant now) {
        var invalidated = invalidations(input.open(), events.rows());
        var planned = Planned.planned(observed, now);

        return DetectorBatch.detectorBatch(lastSeq(input.cursor(), events),
                                           now,
                                           invalidated,
                                           planned.merges(),
                                           planned.dequeued(),
                                           Stream.concat(invalidated.stream()
                                                                    .map(invalidation -> invalidatedEvent(invalidation,
                                                                                                          now)),
                                                         planned.events().stream())
                                                 .toList());
    }

    /// Each outstanding GO the batch's rows invalidate (`checks-changed`, `base-moved`, [GoRules]), by its first
    /// matching row; ordered by that row's seq, then creation order.
    static List<GoInvalidation> invalidations(List<OpenGo> open, List<FleetEventRow> rows) {
        return open.stream()
                   .flatMap(go -> invalidation(go, rows).stream())
                   .sorted(Comparator.comparingLong(GoInvalidation::causeSeq))
                   .toList();
    }

    private static Option<GoInvalidation> invalidation(OpenGo go, List<FleetEventRow> rows) {
        return Option.from(rows.stream()
                               .flatMap(row -> GoRules.reason(go.basis(),
                                                              row)
                                                      .map(reason -> GoInvalidation.goInvalidation(go,
                                                                                                   reason,
                                                                                                   row.seq()))
                                                      .stream())
                               .findFirst());
    }

    /// C8 `go-invalidated`: `go`, `sha`, `base`, `reason`, `causeSeq`.
    private static NewFleetEvent invalidatedEvent(GoInvalidation invalidation, Instant now) {
        var go = invalidation.go();
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("go", go.id());
        payload.put("sha",
                    go.basis().sha());
        payload.put("base",
                    go.basis().base());
        payload.put("reason", invalidation.reason());
        payload.put("causeSeq", invalidation.causeSeq());

        return event(now,
                     "go-invalidated",
                     go.basis().repo() + "#" + go.basis().pr(),
                     payload);
    }

    private static long lastSeq(long cursor, FleetPending.Events events) {
        return events.rows()
                     .stream()
                     .mapToLong(FleetEventRow::seq)
                     .max()
                     .orElse(cursor);
    }

    /// The merge rule a row matches, by kind: `pr-state` (the check and base kinds are [GoRules]).
    private static Option<Sighting> sighting(FleetEventRow row, Option<Instant> startedAt) {
        return switch (row.kind()) {
            case "pr-state" -> prState(row, startedAt);
            default -> none();
        };
    }

    /// Design-out: the payload is a `jsonb` column written by the kiln pull, so it always parses; a row whose payload
    /// names no repo or no positive PR cannot be attributed and is skipped.
    private static Option<Sighting> prState(FleetEventRow row, Option<Instant> startedAt) {
        return some(row).filter(candidate -> !replayedBeforeStart(candidate, startedAt))
                   .flatMap(candidate -> TemperJson.mapper()
                                                   .readTree(candidate.payload())
                                                   .option())
                   .flatMap(payload -> merged(row.seq(),
                                              payload));
    }

    /// `catchUp == true` and `observedAt < started_at`.
    static boolean replayedBeforeStart(FleetEventRow row, Option<Instant> startedAt) {
        return row.catchUp() && startedAt.filter(row.observedAt()::isBefore)
                                         .isPresent();
    }

    private static Option<Sighting> merged(long seq, JsonNode payload) {
        return text(payload, "after").filter(MERGED::equals)
                   .flatMap(_ -> attributed(seq, payload));
    }

    private static Option<Sighting> attributed(long seq, JsonNode payload) {
        return some(payload).all(node -> text(node, "repo"),
                                 node -> positive(node, "pr"))
                   .map((repo, pr) -> sighting(seq, payload, repo, pr));
    }

    private static Sighting sighting(long seq, JsonNode payload, String repo, long pr) {
        return Sighting.sighting(seq,
                                 MergeKey.mergeKey(repo,
                                                   pr,
                                                   text(payload, "head").filter(SHA40.asMatchPredicate())
                                                       .or(MergeKey.UNKNOWN)),
                                 text(payload, "base").or(MergeKey.UNKNOWN),
                                 text(payload, "mergedBy"),
                                 text(payload, "mergedAt").flatMap(T2Plan::instant));
    }

    private static Option<String> text(JsonNode payload, String key) {
        return some(payload.path(key)).filter(JsonNode::isString)
                   .map(JsonNode::stringValue)
                   .filter(value -> !value.isEmpty());
    }

    private static Option<Long> positive(JsonNode payload, String key) {
        return some(payload.path(key)).filter(JsonNode::isIntegralNumber)
                   .filter(JsonNode::canConvertToLong)
                   .map(JsonNode::longValue)
                   .filter(value -> value > 0);
    }

    /// FER: a `mergedAt` that is not ISO-8601 is dropped (the merge is still announced, without the time).
    private static Option<Instant> instant(String text) {
        return DateTime.parseInstant(text).option();
    }

    /// The merges of one batch, folded in seq order against the facts read before it, so a merge seen twice in the
    /// batch is announced once and a queue losing two PRs shifts twice. `queues` holds each queue as the batch left it,
    /// keyed `<repo> <base>`.
    record Planned(Set<MergeKey> keys,
                   Map<String, List<QueueEntry>> queues,
                   List<MergeRow> merges,
                   List<QueueEntry> dequeued,
                   List<NewFleetEvent> events) {
        static final Planned EMPTY = new Planned(Set.of(), Map.of(), List.of(), List.of(), List.of());

        static Planned planned(List<Observed> observed, Instant now) {
            return observed.stream()
                           .reduce(EMPTY,
                                   (done, seen) -> done.with(seen, now),
                                   (left, _) -> left);
        }

        /// A merge recorded already, or planned earlier in this batch, does nothing more.
        Planned with(Observed seen, Instant now) {
            return seen.facts()
                       .recorded() || plannedAlready(seen.sighting().key())
                   ? this
                   : merged(seen, now).dequeued(seen.sighting().key(),
                                                seen.facts().queue(),
                                                now);
        }

        /// R3-2.i: one merge per (repo, pr), the key FACTS uses; the same PR seen at another sha (or `unknown`) in the
        /// batch is the same merge.
        private boolean plannedAlready(MergeKey key) {
            return keys.stream()
                       .anyMatch(planned -> planned.pr() == key.pr() && planned.repo()
                                                                               .equals(key.repo()));
        }

        private Planned merged(Observed seen, Instant now) {
            return new Planned(plus(keys,
                                    seen.sighting().key()),
                               queues,
                               plus(merges, mergeRow(seen)),
                               dequeued,
                               plus(events, announcement(seen, now)));
        }

        /// The PR's queue as this batch left it (the facts' copy the first time); a PR no longer in it is not queued.
        private Planned dequeued(MergeKey key, List<QueueEntry> read, Instant now) {
            return Option.from(read.stream().findFirst())
                         .map(first -> first.repo() + " " + first.base())
                         .map(name -> removedFrom(name,
                                                  option(queues.get(name)).or(read),
                                                  key,
                                                  now))
                         .or(this);
        }

        private Planned removedFrom(String name, List<QueueEntry> queue, MergeKey key, Instant now) {
            return Option.from(queue.stream().filter(entry -> isPr(entry,
                                                                   key.pr())).findFirst())
                         .map(gone -> advanced(name, queue, gone, now))
                         .or(this);
        }

        private Planned advanced(String name, List<QueueEntry> queue, QueueEntry gone, Instant now) {
            var shifted = shifted(queue, gone);

            return new Planned(keys,
                               put(queues, name, shifted),
                               merges,
                               plus(dequeued, gone),
                               plus(events,
                                    event(now,
                                          "queue-advanced",
                                          gone.base(),
                                          queuePayload(gone,
                                                       Option.from(shifted.stream().findFirst())))));
        }
    }

    private static boolean isPr(QueueEntry entry, long pr) {
        return entry.pr() == pr;
    }

    /// The queue without `gone`, renumbered 1..n in order (positions stay dense).
    private static List<QueueEntry> shifted(List<QueueEntry> queue, QueueEntry gone) {
        var rest = queue.stream().filter(entry -> !isPr(entry, gone.pr())).toList();

        return IntStream.range(0,
                               rest.size())
                        .mapToObj(index -> QueueEntry.queueEntry(gone.repo(),
                                                                 gone.base(),
                                                                 index + 1,
                                                                 rest.get(index).pr()))
                        .toList();
    }

    private static MergeRow mergeRow(Observed seen) {
        var sighting = seen.sighting();

        return MergeRow.mergeRow(sighting.key(),
                                 sighting.base(),
                                 seen.facts().gate().map(MergeFacts.GatingGo::id),
                                 ROUTE_KILN,
                                 sighting.mergedBy(),
                                 sighting.mergedAt(),
                                 some(sighting.seq()));
    }

    /// Gated → `merged` route kiln; otherwise `ungated-merge`.
    private static NewFleetEvent announcement(Observed seen, Instant now) {
        var sighting = seen.sighting();

        return seen.facts()
                   .gate()
                   .map(go -> event(now,
                                    MERGED,
                                    subject(sighting.key()),
                                    mergedPayload(sighting, go)))
                   .or(() -> event(now,
                                   "ungated-merge",
                                   subject(sighting.key()),
                                   ungatedPayload(sighting)));
    }

    private static <T> List<T> plus(List<T> list, T item) {
        return Stream.concat(list.stream(),
                             Stream.of(item))
                     .toList();
    }

    private static <T> Set<T> plus(Set<T> set, T item) {
        return Stream.concat(set.stream(),
                             Stream.of(item))
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static <V> Map<String, V> put(Map<String, V> map, String key, V value) {
        var copy = new HashMap<>(map);

        copy.put(key, value);

        return Map.copyOf(copy);
    }

    private static String subject(MergeKey key) {
        return key.repo() + "#" + key.pr();
    }

    private static NewFleetEvent event(Instant now, String kind, String subject, ObjectNode payload) {
        return NewFleetEvent.newFleetEvent(now, SOURCE, kind, subject, payload.toString());
    }

    /// C8 `ungated-merge`: `sha`, `by`, `base`, `mergedAt` (absent when kiln sent none), `prStateSeq`.
    private static ObjectNode ungatedPayload(Sighting sighting) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("sha",
                    sighting.key().sha());
        payload.put("by",
                    sighting.mergedBy().or(MergeKey.UNKNOWN));
        payload.put("base", sighting.base());
        sighting.mergedAt().onPresent(at -> payload.put("mergedAt", at.toString()));
        payload.put("prStateSeq", sighting.seq());

        return payload;
    }

    /// C8 `merged`: `go`, `sha`, `base`, `baseTipBefore` (absent when the GO recorded none), `route`.
    private static ObjectNode mergedPayload(Sighting sighting, MergeFacts.GatingGo go) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("go", go.id());
        payload.put("sha",
                    sighting.key().sha());
        payload.put("base", sighting.base());
        go.baseTipBefore().onPresent(tip -> payload.put("baseTipBefore", tip));
        payload.put("route", ROUTE_KILN);

        return payload;
    }

    /// C8 `queue-advanced`: `repo`, `base`, `merged`, `next` (the head after the removal; absent when it emptied).
    static ObjectNode queuePayload(QueueEntry gone, Option<QueueEntry> next) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("repo", gone.repo());
        payload.put("base", gone.base());
        payload.put("merged",
                    gone.repo() + "#" + gone.pr());
        next.onPresent(head -> payload.put("next",
                                           head.repo() + "#" + head.pr()));

        return payload;
    }

    private static ObjectNode gapPayload(FleetPending.Gap gap) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("from", gap.from());
        payload.put("to", gap.to());

        return payload;
    }

    record unused() implements T2Plan {}
}
