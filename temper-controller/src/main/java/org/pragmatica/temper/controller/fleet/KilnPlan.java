package org.pragmatica.temper.controller.fleet;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.temper.store.api.KilnBatch;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.SourceSeen;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.pragmatica.lang.Option.some;


/// What one kiln reply does to the stream (design §2.2), as data the store writes in one
/// transaction. Pure, so every branch is tested without kiln or Postgres.
///
/// - **Changes**: each change becomes a row keyed `(kiln, <epoch>[~<generation>], id)`; the cursor moves to the last id.
///   A row is `catchUp` while its id is at or below `catchUpTo`, the head kiln had when this epoch was first read (H4).
///   The first read ever (no stored epoch) is a catch-up of everything kiln holds, announced by nothing, whatever the
///   reply's shape (a gap or a reset on the first read included).
/// - **Reset** (kiln's epoch is not the stored one: a rebuilt database): `source-reset kiln reason=reset`, the new epoch,
///   cursor 0, generation 0, catch-up to the reset's head.
/// - **Rewind** (same epoch, but kiln's head is below the cursor: kiln was restored from a backup, R2-6.1): treated as a
///   reset, `reason=rewind`, cursor 0, catch-up to the head; the generation grows, so the ids kiln now reuses get new
///   source keys instead of being dropped by `ON CONFLICT DO NOTHING`.
/// - **Gap** (retention deleted changes after the cursor): `gap kiln from..to`, then the cursor continues from the floor.
///
/// Every reply also records the pull's own success (`kiln`, period 5 s) and kiln's per-source `lastOkAt` as
/// `kiln:<name>`, from which staleness is computed by age (F13).
public sealed interface KilnPlan {
    String KILN = "kiln";
    int PULL_PERIOD_S = 5;
    String KILN_PREFIX = KILN + ":";

    static KilnBatch plan(FleetSourceRow state, KilnReply reply, Instant now) {
        return state.epoch()
                    .isEmpty()
               ? first(state, reply, now)
               : planned(state, reply, now);
    }

    /// The first read ever (no stored kiln epoch): whatever the reply's shape, every row up to
    /// its head is catch-up, and nothing is announced (temper never held a cursor, so nothing was missed).
    private static KilnBatch first(FleetSourceRow state, KilnReply reply, Instant now) {
        return switch (reply) {
            case KilnReply.Changes changes -> changesOrRewind(state, changes, now);
            case KilnReply.Reset reset -> KilnBatch.kilnBatch(state(reset.epoch(), 0, 0, reset.head(), reset.head(), now),
                                                              seen(reset.sources()),
                                                              List.of());
            case KilnReply.Gap gap -> KilnBatch.kilnBatch(state(gap.epoch(),
                                                                Math.max(state.cursor(), gap.to() - 1),
                                                                0,
                                                                gap.head(),
                                                                gap.head(),
                                                                now),
                                                          seen(gap.sources()),
                                                          List.of());
        };
    }

    private static KilnBatch planned(FleetSourceRow state, KilnReply reply, Instant now) {
        return switch (reply) {
            case KilnReply.Changes changes -> changesOrRewind(state, changes, now);
            case KilnReply.Reset reset -> restarted(state, "reset", reset.epoch(), reset.head(), 0, reset.sources(), now);
            case KilnReply.Gap gap -> KilnBatch.kilnBatch(state(gap.epoch(),
                                                                Math.max(state.cursor(), gap.to() - 1),
                                                                state.generation(),
                                                                state.catchUpTo(),
                                                                gap.head(),
                                                                now),
                                                          seen(gap.sources()),
                                                          List.of(announcement("gap", gapPayload(gap), now)));
        };
    }

    /// The pull's kind for a kiln kind: `HEAD_MOVED` is `pr-head-moved`; every other kind, known or not, lower-kebab.
    static String kind(String kilnKind) {
        return "HEAD_MOVED".equals(kilnKind)
               ? "pr-head-moved"
               : kilnKind.toLowerCase(Locale.ROOT)
                         .replace('_', '-');
    }

    /// kiln's epoch as stored in `source_epoch`: a rewind's generation is appended, so re-used ids stay distinct.
    static String sourceEpoch(String epoch, int generation) {
        return generation == 0
               ? epoch
               : epoch + "~" + generation;
    }

    private static KilnBatch changesOrRewind(FleetSourceRow state, KilnReply.Changes changes, Instant now) {
        return switch (relation(state, changes)) {
            case FIRST -> rows(state(changes.epoch(), state.cursor(), 0, changes.head(), changes.head(), now),
                               changes,
                               now);
            case REWOUND -> restarted(state,
                                      "rewind",
                                      changes.epoch(),
                                      changes.head(),
                                      state.generation() + 1,
                                      changes.sources(),
                                      now);
            case OTHER_EPOCH -> restarted(state, "reset", changes.epoch(), changes.head(), 0, changes.sources(), now);
            case SAME -> rows(state(changes.epoch(),
                                    state.cursor(),
                                    state.generation(),
                                    state.catchUpTo(),
                                    changes.head(),
                                    now),
                              changes,
                              now);
        };
    }

    enum Relation {
        FIRST,
        SAME,
        REWOUND,
        OTHER_EPOCH
    }

    private static Relation relation(FleetSourceRow state, KilnReply.Changes changes) {
        return state.epoch()
                    .map(epoch -> epoch.equals(changes.epoch())
                                  ? sameEpoch(state, changes)
                                  : Relation.OTHER_EPOCH)
                    .or(Relation.FIRST);
    }

    private static Relation sameEpoch(FleetSourceRow state, KilnReply.Changes changes) {
        return changes.head() < state.cursor()
               ? Relation.REWOUND
               : Relation.SAME;
    }

    /// A reset or a rewind: announced, cursor 0, catch-up to the head; the rows come with the next pull.
    private static KilnBatch restarted(FleetSourceRow state,
                                       String reason,
                                       String epoch,
                                       long head,
                                       int generation,
                                       Map<String, KilnReply.SourceInfo> sources,
                                       Instant now) {
        return KilnBatch.kilnBatch(state(epoch, 0, generation, head, head, now),
                                   seen(sources),
                                   List.of(announcement("source-reset",
                                                        resetPayload(reason,
                                                                     state.epoch().or(""),
                                                                     epoch,
                                                                     head),
                                                        now)));
    }

    private static KilnBatch rows(FleetSourceRow next, KilnReply.Changes changes, Instant now) {
        var last = changes.changes().stream().mapToLong(KilnReply.Change::id).max().orElse(next.cursor());
        var advanced = state(next.epoch().or(""),
                             Math.max(next.cursor(), last),
                             next.generation(),
                             next.catchUpTo(),
                             changes.head(),
                             now);

        return KilnBatch.kilnBatch(advanced,
                                   seen(changes.sources()),
                                   changes.changes().stream().map(change -> row(advanced, change)).toList());
    }

    private static NewFleetEvent row(FleetSourceRow state, KilnReply.Change change) {
        return NewFleetEvent.kilnRow(change.observedAt(),
                                     sourceEpoch(state.epoch().or(""),
                                                 state.generation()),
                                     change.id(),
                                     kind(change.kind()),
                                     change.subject(),
                                     changePayload(change).toString(),
                                     change.id() <= state.catchUpTo());
    }

    /// kiln's own payload fields, then `repo`, `pr`, `before`, `after` and `recordedAt` where kiln's payload does not
    /// name them, so `--match` can address them as top-level payload fields.
    static ObjectNode changePayload(KilnReply.Change change) {
        var payload = change.payload().isObject()
                      ? (ObjectNode) change.payload().deepCopy()
                      : JsonNodeFactory.instance.objectNode();

        change.repo().filter(_ -> !payload.has("repo")).onPresent(repo -> payload.put("repo", repo));
        change.pr().filter(_ -> !payload.has("pr")).onPresent(pr -> payload.put("pr", pr));
        change.before().filter(_ -> !payload.has("before")).onPresent(before -> payload.put("before", before));
        change.after().filter(_ -> !payload.has("after")).onPresent(after -> payload.put("after", after));
        change.recordedAt()
              .filter(_ -> !payload.has("recordedAt"))
              .onPresent(at -> payload.put("recordedAt",
                                           at.toString()));

        return payload;
    }

    private static FleetSourceRow state(String epoch,
                                        long cursor,
                                        int generation,
                                        long catchUpTo,
                                        long head,
                                        Instant now) {
        return FleetSourceRow.fleetSourceRow(KILN,
                                             some(epoch),
                                             cursor,
                                             some(now),
                                             PULL_PERIOD_S,
                                             generation,
                                             catchUpTo,
                                             head,
                                             false);
    }

    private static List<SourceSeen> seen(Map<String, KilnReply.SourceInfo> sources) {
        return sources.entrySet()
                      .stream()
                      .sorted(Map.Entry.comparingByKey())
                      .map(entry -> SourceSeen.sourceSeen(sourceName(entry.getKey()),
                                                          entry.getValue().lastOkAt(),
                                                          entry.getValue().periodS()))
                      .toList();
    }

    /// A kiln source's heartbeat name: `kiln:` + its key, and a key kiln already prefixed
    /// (`kiln:highwater`, lead ruling 11-03) as is, never `kiln:kiln:highwater`. Unambiguous: kiln guarantees no
    /// `source_state` key starts with `kiln:`, which is why it prefixes its own high-water source.
    static String sourceName(String key) {
        return key.startsWith(KILN_PREFIX)
               ? key
               : KILN_PREFIX + key;
    }

    private static NewFleetEvent announcement(String kind, ObjectNode payload, Instant now) {
        return NewFleetEvent.newFleetEvent(now, KILN, kind, KILN, payload.toString());
    }

    private static ObjectNode resetPayload(String reason, String old, String epoch, long head) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("reason", reason);
        payload.put("old", old);
        payload.put("new", epoch);
        payload.put("head", head);

        return payload;
    }

    private static ObjectNode gapPayload(KilnReply.Gap gap) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("from", gap.from());
        payload.put("to", gap.to());

        return payload;
    }

    record unused() implements KilnPlan {}
}
