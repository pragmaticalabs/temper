package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbStore.Listing;
import org.pragmatica.temper.store.api.BbStore.RoundWrite;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.NewFleetEvent;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The collection-round alarms, pure: each round runs over the marks the
/// previous rounds left, as the store's transaction would read them. Rows S1.1, S1.2, S1.4-S1.6, S1.8, S1.9, S1.14,
/// S1.25-S1.27 (S1.3, S1.7, S1.13 and the restart are over Postgres in `TicketAlarmsIT`).
class TicketAlarmsTest {
    private static final String HOST = "h1";
    /// The host clock's `at` of the first listing (epoch seconds).
    private static final long AT = 1_793_900_000L;
    private static final long NANOS = 1_000_000_000L;

    /// The marks as the store would hold them between rounds, and every event emitted.
    private static final class Rounds {
        private final List<BbAlarm> rows = new ArrayList<>();
        private final List<NewFleetEvent> events = new ArrayList<>();
        private Option<BbSnapshot> baseline = none();
        private TicketAlarms.Limits limits = TicketAlarms.Limits.DEFAULT;

        /// One successful round at host time `at`, the controller 20 s ahead of the host.
        List<NewFleetEvent> round(long at, int skewS, BbTicket... tickets) {
            var now = Instant.ofEpochSecond(at + 20);
            var listing = Listing.listing("present", List.of(tickets), Instant.ofEpochSecond(at), skewS);
            var write = TicketAlarms.alarms(HOST, now, listing, baseline, List.copyOf(rows), limits);

            apply(write);
            baseline = some(BbSnapshot.bbSnapshot(HOST,
                                                  now,
                                                  some("present"),
                                                  List.of(tickets),
                                                  some(listing.hostAt()),
                                                  some(skewS)));

            return write.events();
        }

        List<NewFleetEvent> round(long at, BbTicket... tickets) {
            return round(at, 0, tickets);
        }

        private void apply(RoundWrite write) {
            write.clear().forEach(gone -> rows.removeIf(gone::sameKey));
            rows.addAll(write.fire());
            events.addAll(write.events());
        }

        long count(String kind) {
            return events.stream()
                         .filter(event -> event.kind()
                                               .equals(kind))
                         .count();
        }
    }

    private static BbTicket ticket(String id, String dir, String waiter, String... kv) {
        var fields = new LinkedHashMap<String, String>();

        for (var i = 0; i < kv.length; i += 2) {
            fields.put(kv[i], kv[i + 1]);
        }

        return BbTicket.bbTicket(1, id, dir, Option.option(waiter), fields, List.of());
    }

    /// A waiting ticket created `ageS` seconds before `at` (nanoseconds, as `bb-build` writes it).
    private static BbTicket waiting(String id, long at, long ageS, String... kv) {
        var all = new ArrayList<>(List.of("created",
                                          Long.toString((at - ageS) * NANOS),
                                          "owner",
                                          "s1a",
                                          "agent",
                                          "lane-a"));

        all.addAll(List.of(kv));

        return ticket(id, "waiting", "alive", all.toArray(String[]::new));
    }

    private static BbTicket groupRun(String id, long at, long ageS) {
        return ticket(id,
                      "running",
                      "alive",
                      "created",
                      Long.toString((at - ageS - 5) * NANOS),
                      "started",
                      Long.toString(at - ageS),
                      "group",
                      "1");
    }

    private static JsonNode payload(NewFleetEvent event) {
        return TemperJson.mapper()
                         .readTree(event.payload())
                         .unwrap();
    }

    // ---- queue-wait ---------------------------------------------------------------------------------------------
    /// S1.1: `at − ⌊created/1e9⌋ = 3600` with limit 60 fires `queue-wait` once (also over later rounds); at 3599 nothing.
    /// The controller clock is 20 s ahead of `at`. Mutations: `>` instead of `≥` → 3600 does not fire, red; age from the
    /// controller clock → 3599 reads 3619 and fires, red.
    @Test
    void s1_1_queueWait_firesAtExactlyTheLimit_onTheHostClock_once() {
        var rounds = new Rounds();
        var fired = rounds.round(AT, waiting("w1", AT, 3600));

        assertThat(fired).hasSize(1);
        assertThat(fired.getFirst().kind()).isEqualTo("queue-wait");
        assertThat(fired.getFirst().subject()).isEqualTo("h1/w1");
        assertThat(fired.getFirst().observedAt()).isEqualTo(Instant.ofEpochSecond(AT + 20));
        assertThat(payload(fired.getFirst()).path("ageMin").intValue()).isEqualTo(60);
        assertThat(payload(fired.getFirst()).path("limitMin").intValue()).isEqualTo(60);
        assertThat(rounds.round(AT + 30, waiting("w1", AT, 3600))).isEmpty();
        assertThat(rounds.round(AT + 60, waiting("w1", AT, 3600))).isEmpty();
        var under = new Rounds();

        assertThat(under.round(AT, waiting("w2", AT, 3599))).as("3599 s on the host clock").isEmpty();
    }

    /// S1.2: `created` 61 min before `at`, `blocked_since` 5 min before: fires, aged from `created`. Mutation: age from
    /// `blocked_since` → 5 min, nothing, red.
    @Test
    void s1_2_queueWait_agesFromCreated_notBlockedSince() {
        var fired = new Rounds().round(AT, waiting("w1", AT, 61 * 60, "blocked_since", Long.toString(AT - 300)));

        assertThat(fired).hasSize(1);
        assertThat(payload(fired.getFirst()).path("ageMin").intValue()).isEqualTo(61);
        assertThat(payload(fired.getFirst()).path("blockedSince").stringValue()).isEqualTo(Instant.ofEpochSecond(AT - 300).toString());
        assertThat(payload(fired.getFirst()).path("owner").stringValue()).isEqualTo("s1a");
    }

    /// S1.4: the limit changed 60 → 30 with a ticket already fired at 60: one more event, `limitMin` 30. Mutation: the
    /// threshold left out of the key → the 60 mark covers it, no event, red.
    @Test
    void s1_4_aChangedLimit_rearms_withTheNewLimit() {
        var rounds = new Rounds();

        assertThat(rounds.round(AT, waiting("w1", AT, 61 * 60))).hasSize(1);
        rounds.limits = TicketAlarms.Limits.limits(30, 30, 360);
        var again = rounds.round(AT + 30, waiting("w1", AT, 61 * 60));

        assertThat(again).hasSize(1);
        assertThat(payload(again.getFirst()).path("limitMin").intValue()).isEqualTo(30);
        assertThat(rounds.round(AT + 60, waiting("w1", AT, 61 * 60))).isEmpty();
    }

    /// S1.25: a waiting ticket at 60 → `limitMin` 60; at 180 → a second event, `limitMin` 180; then nothing more.
    /// Mutation: no second threshold → nothing at 180, red.
    @Test
    void s1_25_aSecondThreshold_firesOnceMore() {
        var rounds = new Rounds();
        var start = AT;

        assertThat(rounds.round(start,
                                waiting("w1", start, 60 * 60))).extracting(event -> payload(event).path("limitMin")
                                                                                           .intValue())
                  .containsExactly(60);
        assertThat(rounds.round(start + 120 * 60,
                                waiting("w1", start, 60 * 60))).extracting(event -> payload(event).path("limitMin")
                                                                                           .intValue())
                  .containsExactly(180);
        assertThat(rounds.round(start + 300 * 60, waiting("w1", start, 60 * 60))).isEmpty();
        assertThat(rounds.round(start + 900 * 60, waiting("w1", start, 60 * 60))).isEmpty();
    }

    /// S1.26: first seen at 200 min → one event, `limitMin` 180, and no 60 event ever after; both marks written.
    /// Mutation: emit every due threshold → a 60 and a 180 event, red.
    @Test
    void s1_26_firstSeenAboveBoth_firesTheHigherOnly() {
        var rounds = new Rounds();
        var fired = rounds.round(AT, waiting("w1", AT, 200 * 60));

        assertThat(fired).extracting(event -> payload(event).path("limitMin")
                                                     .intValue()).containsExactly(180);
        assertThat(rounds.rows).extracting(BbAlarm::threshold).containsExactlyInAnyOrder("60", "180");
        assertThat(rounds.round(AT + 30, waiting("w1", AT, 200 * 60))).isEmpty();
        assertThat(rounds.count("queue-wait")).isEqualTo(1);
    }

    /// S1.27: `queueWaitAlarmMin` = 200 → no 180 threshold: at 190 min nothing, at 200 one event. Mutation: the second
    /// threshold always applies → 180 fires at 190, red.
    @Test
    void s1_27_aFirstLimitAboveTheSecond_dropsTheSecond() {
        var rounds = new Rounds();

        rounds.limits = TicketAlarms.Limits.limits(200, 30, 360);
        assertThat(rounds.round(AT, waiting("w1", AT, 190 * 60))).isEmpty();
        assertThat(rounds.round(AT + 600,
                                waiting("w1", AT, 190 * 60))).extracting(event -> payload(event).path("limitMin")
                                                                                         .intValue())
                  .containsExactly(200);
        assertThat(rounds.round(AT + 6000, waiting("w1", AT, 190 * 60))).isEmpty();
    }

    // ---- portgroup-held -----------------------------------------------------------------------------------------
    /// S1.5 (stall form): a running `group=1` ticket without `progressAt` is judged from `started` (the fallback for an old
    /// host script): `at − started = 1800`, stall limit 30, fires with `groupWaiting`; 1799: nothing; a running ticket
    /// without `group=1` at 10 h: nothing. Mutations: `>` → 1800 silent, red; group ignored → the 10 h one fires, red.
    @Test
    void s1_5_portGroupHeld_withoutProgressAt_fallsBackToStarted_andFiresAtTheStallLimit() {
        var fired = new Rounds().round(AT,
                                       groupRun("r1", AT, 1800),
                                       waiting("w1", AT, 10, "group", "1"),
                                       waiting("w2", AT, 10, "group", "0"));

        assertThat(fired).hasSize(1);
        assertThat(fired.getFirst().kind()).isEqualTo("portgroup-held");
        assertThat(payload(fired.getFirst()).path("groupWaiting").intValue()).isEqualTo(1);
        assertThat(payload(fired.getFirst()).path("stallMin").intValue()).isEqualTo(30);
        assertThat(payload(fired.getFirst()).path("limitMin").intValue()).isEqualTo(30);
        assertThat(payload(fired.getFirst()).has("progressAt")).as("no progressAt in an old listing").isFalse();
        assertThat(new Rounds().round(AT, groupRun("r2", AT, 1799))).isEmpty();
        assertThat(new Rounds().round(AT,
                                      ticket("r3",
                                             "running",
                                             "alive",
                                             "started",
                                             Long.toString(AT - 36000),
                                             "group",
                                             "0"),
                                      ticket("r4", "running", "alive", "started", Long.toString(AT - 36000)))).isEmpty();
    }

    /// A running group holder whose host listing carries `progressAt`.
    private static BbTicket progressing(String id, long startedAgoS, long progressAgoS) {
        return ticket(id,
                      "running",
                      "alive",
                      "started",
                      Long.toString(AT - startedAgoS),
                      "progressAt",
                      Long.toString(AT - progressAgoS),
                      "group",
                      "1");
    }

    private static List<String> kinds(List<NewFleetEvent> events) {
        return events.stream()
                     .map(NewFleetEvent::kind)
                     .toList();
    }

    /// The issue's case: 5 h old (under the 360 backstop) but a report 1 min ago: no alarm at all, in any later round
    /// while it keeps progressing. Mutation: age instead of stall -> `portgroup-held` fires, red.
    @Test
    void portGroupHeld_aLongButProgressingTicket_firesNothing() {
        var rounds = new Rounds();

        assertThat(rounds.round(AT, progressing("r1", 300 * 60, 60))).isEmpty();
        assertThat(rounds.round(AT + 600, progressing("r1", 310 * 60, 30))).isEmpty();
        assertThat(rounds.rows).isEmpty();
    }

    /// A stalled one: 3 h old, last progress 45 min ago: one `portgroup-held`, with the stall, the limit and `progressAt`;
    /// not again while stalled; a progress clears the mark and the next stall fires once more.
    /// Mutation: compare the stall with `>` the other way (`<`) -> the stalled one is silent, red.
    @Test
    void portGroupHeld_aStalledTicket_firesOncePerStall_andReArmsOnProgress() {
        var rounds = new Rounds();
        var first = rounds.round(AT, progressing("r1", 180 * 60, 45 * 60));

        assertThat(kinds(first)).containsExactly("portgroup-held");
        assertThat(payload(first.getFirst()).path("stallMin").intValue()).isEqualTo(45);
        assertThat(payload(first.getFirst()).path("limitMin").intValue()).isEqualTo(30);
        assertThat(payload(first.getFirst()).path("progressAt").longValue()).isEqualTo(AT - 45 * 60);
        assertThat(payload(first.getFirst()).path("ageMin").intValue()).isEqualTo(180);
        assertThat(rounds.round(AT + 600, progressing("r1", 190 * 60, 55 * 60))).isEmpty();
        // a report lands: progress 1 min ago -> the mark goes
        assertThat(rounds.round(AT + 1200, progressing("r1", 200 * 60, 60))).isEmpty();
        assertThat(rounds.rows).isEmpty();
        // and stalls again
        assertThat(kinds(rounds.round(AT + 4000, progressing("r1", 250 * 60, 40 * 60)))).containsExactly("portgroup-held");
        assertThat(rounds.count("portgroup-held")).isEqualTo(2);
    }

    /// `at − started ≥ 360 min` fires `portgroup-long` whatever the progress, once, with the held alarm only when
    /// the stall also holds. Mutation: age limit applied to `portgroup-held` events, or no mark -> red.
    @Test
    void portGroupLong_firesAtTheAgeLimit_whateverTheProgress_once() {
        var rounds = new Rounds();

        assertThat(rounds.round(AT, progressing("r1", 359 * 60, 10))).isEmpty();
        var fired = rounds.round(AT + 120, progressing("r1", 361 * 60, 10));

        assertThat(kinds(fired)).containsExactly("portgroup-long");
        assertThat(payload(fired.getFirst()).path("ageMin").intValue()).isEqualTo(363);
        assertThat(payload(fired.getFirst()).path("limitMin").intValue()).isEqualTo(360);
        assertThat(rounds.round(AT + 7200, progressing("r1", 480 * 60, -7190))).isEmpty();
        assertThat(kinds(new Rounds().round(AT, progressing("r2", 400 * 60, 90 * 60)))).containsExactly("portgroup-held",
                                                                                                        "portgroup-long");
        assertThat(kinds(new Rounds().round(AT,
                                            ticket("w",
                                                   "running",
                                                   "alive",
                                                   "started",
                                                   Long.toString(AT - 400 * 60))))).as("no group, no alarm")
                  .isEmpty();
    }

    /// A mark an older controller wrote for the age form (`portgroup-held` at 120) is cleared when the ticket is not
    /// stalled, so `fleet/alarms` does not keep listing it. Mutation: leave other-limit marks alone -> red.
    @Test
    void portGroupHeld_anOldAgeMark_isClearedWhileTheTicketProgresses() {
        var rounds = new Rounds();

        rounds.rows.add(BbAlarm.bbAlarm(HOST, BbAlarm.PORTGROUP_HELD, "r1", "120", Instant.ofEpochSecond(AT - 100)));
        assertThat(rounds.round(AT, progressing("r1", 200 * 60, 30))).isEmpty();
        assertThat(rounds.rows).isEmpty();
    }

    /// The age backstop's mark goes with the ticket, like the others.
    @Test
    void portGroupLong_markIsClearedWhenTheTicketLeaves() {
        var rounds = new Rounds();

        rounds.round(AT, progressing("r1", 400 * 60, 10));
        assertThat(rounds.rows).extracting(BbAlarm::kind).containsExactly("portgroup-long");
        rounds.round(AT + 30, ticket("r1", "done", null));
        assertThat(rounds.rows).isEmpty();
    }

    /// A waiting `group=1` ticket's `queue-wait` names the holder; one without `group=1` does not.
    @Test
    void queueWait_ofAGroupTicket_namesTheHolder() {
        var fired = new Rounds().round(AT,
                                       groupRun("r1", AT, 60),
                                       waiting("w1", AT, 3600, "group", "1"),
                                       waiting("w2", AT, 3600, "group", "0"));

        assertThat(fired).extracting(NewFleetEvent::subject).containsExactly("h1/w1", "h1/w2");
        assertThat(payload(fired.get(0)).path("holder").stringValue()).isEqualTo("r1");
        assertThat(payload(fired.get(0)).path("group").stringValue()).isEqualTo("1");
        assertThat(payload(fired.get(1)).has("holder")).isFalse();
    }

    // ---- waiter-gone --------------------------------------------------------------------------------------------
    /// S1.6 (pure part): gone in one listing then alive → nothing; gone in two listings 30 s apart → one event; 29 s
    /// apart → nothing that round, fires the next; gone on the first-ever success → nothing. (The failed round between
    /// is over Postgres.) Mutations: fire on one listing → the first listing fires, red; no spacing check → 29 s fires, red.
    @Test
    void s1_6_waiterGone_needsTwoListingsAtLeast30sApart() {
        var flip = new Rounds();

        assertThat(flip.round(AT, ticket("r1", "running", "gone"))).as("no baseline").isEmpty();
        assertThat(flip.round(AT + 30, ticket("r1", "running", "alive"))).isEmpty();
        assertThat(flip.round(AT + 60, ticket("r1", "running", "alive"))).isEmpty();
        var twice = new Rounds();

        assertThat(twice.round(AT, ticket("r1", "running", "gone"))).isEmpty();
        var fired = twice.round(AT + 30, ticket("r1", "running", "gone"));

        assertThat(fired).extracting(NewFleetEvent::kind).containsExactly("waiter-gone");
        assertThat(payload(fired.getFirst()).path("dir").stringValue()).isEqualTo("running");
        assertThat(payload(fired.getFirst()).path("goneSinceAt").stringValue()).isEqualTo(Instant.ofEpochSecond(AT).toString());
        assertThat(twice.round(AT + 60, ticket("r1", "running", "gone"))).isEmpty();
        var close = new Rounds();

        assertThat(close.round(AT, ticket("w1", "waiting", "gone"))).isEmpty();
        assertThat(close.round(AT + 29, ticket("w1", "waiting", "gone"))).as("29 s apart").isEmpty();
        assertThat(close.round(AT + 59,
                               ticket("w1", "waiting", "gone"))).extracting(NewFleetEvent::kind)
                  .containsExactly("waiter-gone");
    }

    /// A baseline written before S1 has no `at`: nothing fires against it.
    @Test
    void waiterGone_againstABaselineWithoutAt_firesNothing() {
        var old = BbSnapshot.bbSnapshot(HOST,
                                        Instant.ofEpochSecond(AT),
                                        some("present"),
                                        List.of(ticket("r1", "running", "gone")));
        var write = TicketAlarms.alarms(HOST,
                                        Instant.ofEpochSecond(AT + 120),
                                        Listing.listing("present",
                                                        List.of(ticket("r1", "running", "gone")),
                                                        Instant.ofEpochSecond(AT + 100),
                                                        0),
                                        some(old),
                                        List.of(),
                                        TicketAlarms.Limits.DEFAULT);

        assertThat(write.events()).isEmpty();
    }

    // ---- clock-skew ---------------------------------------------------------------------------------------------
    /// S1.8: `t0 = 1000.0`, `t1 = 1010.0`: `at = 1040` → 30, nothing; 1041 → `+31` fires; 969 → `−31` fires; 970 →
    /// nothing; 1005 → 0. Mutations: compare to `t0` only → 1040 reads 40 and fires, red; `≥ 30` → 30 fires, red.
    @Test
    void s1_8_skew_isMeasuredAgainstTheReadInterval() {
        var t0 = Instant.ofEpochSecond(1000);
        var t1 = Instant.ofEpochSecond(1010);

        assertThat(TicketAlarms.skew(t0, t1, Instant.ofEpochSecond(1040))).isEqualTo(30);
        assertThat(TicketAlarms.skew(t0, t1, Instant.ofEpochSecond(1041))).isEqualTo(31);
        assertThat(TicketAlarms.skew(t0, t1, Instant.ofEpochSecond(969))).isEqualTo(-31);
        assertThat(TicketAlarms.skew(t0, t1, Instant.ofEpochSecond(970))).isEqualTo(-30);
        assertThat(TicketAlarms.skew(t0, t1, Instant.ofEpochSecond(1005))).isEqualTo(0);
        assertThat(TicketAlarms.skew(t0, t1.plusMillis(1), Instant.ofEpochSecond(1041))).as("⌈t1⌉ = 1011").isEqualTo(30);
        assertThat(TicketAlarms.skew(t0.plusMillis(500),
                                     t1,
                                     Instant.ofEpochSecond(969))).as("⌊t0⌋ = 1000, not ⌈t0⌉")
                  .isEqualTo(-31);
        assertThat(skewEvents(TicketAlarms.skew(t0, t1, Instant.ofEpochSecond(1040)))).isEmpty();
        assertThat(skewEvents(TicketAlarms.skew(t0, t1, Instant.ofEpochSecond(970)))).isEmpty();
        assertThat(skewEvents(TicketAlarms.skew(t0, t1, Instant.ofEpochSecond(1041)))).containsExactly(31);
        assertThat(skewEvents(TicketAlarms.skew(t0, t1, Instant.ofEpochSecond(969)))).containsExactly(-31);
    }

    private static List<Integer> skewEvents(int skewS) {
        return new Rounds().round(AT, skewS)
                           .stream()
                           .filter(event -> event.kind()
                                                 .equals("clock-skew"))
                           .map(event -> payload(event).path("skewS")
                                                .intValue())
                           .toList();
    }

    /// S1.9: skew 31 for five rounds → one event; 25 → no re-arm; 31 → nothing; 20 → re-armed; 31 → a second event.
    /// Mutation: re-arm at `≤ 30` → 25 re-arms and the next 31 fires, red.
    @Test
    void s1_9_skew_firesOncePerEpisode_reArmsAt20() {
        var rounds = new Rounds();

        for (var i = 0; i < 5; i++) {
            rounds.round(AT + 30L * i, 31);
        }

        assertThat(rounds.count("clock-skew")).isEqualTo(1);
        assertThat(rounds.events.getFirst().subject()).isEqualTo("h1");
        rounds.round(AT + 200, 25);
        rounds.round(AT + 230, 31);
        assertThat(rounds.count("clock-skew")).isEqualTo(1);
        rounds.round(AT + 260, 20);
        assertThat(rounds.rows).isEmpty();
        rounds.round(AT + 290, 31);
        assertThat(rounds.count("clock-skew")).isEqualTo(2);
    }

    // ---- robustness, order --------------------------------------------------------------------------------------
    /// S1.14 (pure part): one ticket with `created=abc` and one over the limit: the second fires, nothing throws.
    /// Mutation: the whole round fails on the bad field → no event, red.
    @Test
    void s1_14_anUnparseableCreated_skipsThatTicketOnly() {
        var fired = new Rounds().round(AT,
                                       ticket("w0", "waiting", "alive", "created", "abc"),
                                       ticket("w00", "waiting", "alive"),
                                       ticket("r0", "running", "alive", "group", "1", "started", "12x"),
                                       waiting("w1", AT, 3600));

        assertThat(fired).extracting(NewFleetEvent::subject).containsExactly("h1/w1");
    }

    /// Order: `clock-skew`, then `queue-wait`, `portgroup-held`, `waiter-gone`, each by ticket id; the diff
    /// events come before them all.
    @Test
    void alarms_comeInTheContractsOrder_afterTheDiffEvents() {
        var gone = ticket("r9", "running", "gone", "group", "1", "started", Long.toString(AT - 7200));
        var baseline = BbSnapshot.bbSnapshot(HOST,
                                             Instant.ofEpochSecond(AT - 10),
                                             some("present"),
                                             List.of(gone),
                                             some(Instant.ofEpochSecond(AT - 30)),
                                             some(0));
        var diffEvent = NewFleetEvent.newFleetEvent(Instant.ofEpochSecond(AT),
                                                    "controller",
                                                    "ticket-queued",
                                                    "h1/w2",
                                                    "{}");
        var round = TicketAlarms.round((_, _, _, _) -> List.of(diffEvent), TicketAlarms.Limits.DEFAULT);
        var write = round.write(HOST,
                                Instant.ofEpochSecond(AT),
                                Listing.listing("present",
                                                List.of(waiting("w2", AT, 3600), gone, waiting("w1", AT, 3600)),
                                                Instant.ofEpochSecond(AT),
                                                - 45),
                                some(baseline),
                                List.of());

        assertThat(write.events()).extracting(event -> event.kind() + " " + event.subject())
                  .containsExactly("ticket-queued h1/w2",
                                   "clock-skew h1",
                                   "queue-wait h1/w1",
                                   "queue-wait h1/w2",
                                   "portgroup-held h1/r9",
                                   "waiter-gone h1/r9");
    }

    /// S1.7 (pure part): a fired ticket that leaves running/waiting has its marks cleared in the same round; one that
    /// moves from waiting to running keeps its `queue-wait` mark.
    @Test
    void s1_7_aTicketThatLeaves_hasItsMarksCleared() {
        var rounds = new Rounds();

        rounds.round(AT, waiting("w1", AT, 200 * 60), waiting("w2", AT, 3600));
        assertThat(rounds.rows).hasSize(3);
        rounds.round(AT + 30, ticket("w1", "done", null), ticket("w2", "running", "alive"));
        assertThat(rounds.rows).extracting(BbAlarm::subject).containsExactly("w2");
        assertThat(rounds.events).hasSize(2);
    }
}
