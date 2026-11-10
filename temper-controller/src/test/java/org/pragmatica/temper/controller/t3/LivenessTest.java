package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import org.pragmatica.temper.controller.t3.Liveness.MacState;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.MacProcess;
import org.pragmatica.temper.store.api.MacSession;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The five owner-liveness values and the order of the rules (T3.4, T3.5, T3.7).
class LivenessTest {
    private static final Instant OK = Instant.parse("2026-11-04T12:00:00Z");
    private static final long START = 1793667998L;

    private static BbTicket ticket(String... kv) {
        var fields = new LinkedHashMap<String, String>();

        for (var i = 0; i < kv.length; i += 2) {
            fields.put(kv[i], kv[i + 1]);
        }

        return BbTicket.bbTicket(1, "t", "running", some("alive"), fields, List.of());
    }

    private static BbTicket sessionTicket(long pid, long start) {
        return ticket("session_pid", Long.toString(pid), "session_start", Long.toString(start), "session_label", "CTO");
    }

    private static MacState fresh(MacProcess... latest) {
        return new MacState(true, some(OK), List.of(latest), List.of());
    }

    private static MacState stale(MacProcess... latest) {
        return new MacState(false, some(OK), List.of(latest), List.of());
    }

    private static MacProcess process(int pid, long start, String label) {
        return MacProcess.macProcess(pid, start, label);
    }

    /// T3.7: a legacy ticket (today's fields only) is UNKNOWN, whatever the Mac says; a missing session is not DEAD.
    @Test
    void t37_aLegacyTicket_isUnknown_evenWhenTheMacJobIsStale() {
        var legacy = ticket("id", "t", "owner", "o", "co", "/w", "created", "1");

        assertThat(Liveness.liveness(legacy, fresh())).isEqualTo("UNKNOWN(no session field)");
        assertThat(Liveness.liveness(legacy,
                                     stale())).as("rule 1 precedes rule 2")
                  .isEqualTo("UNKNOWN(no session field)");
    }

    @Test
    void anIncompleteOrMalformedTriple_isNoSession() {
        assertThat(Liveness.liveness(ticket("session_pid", "10", "session_start", Long.toString(START)), fresh())).isEqualTo("UNKNOWN(no session field)");
        assertThat(Liveness.liveness(ticket("session_pid",
                                            "0",
                                            "session_start",
                                            Long.toString(START),
                                            "session_label",
                                            "CTO"),
                                     fresh())).isEqualTo("UNKNOWN(no session field)");
        assertThat(Liveness.liveness(ticket("session_pid", "10", "session_start", "12", "session_label", "CTO"), fresh())).isEqualTo("UNKNOWN(no session field)");
        assertThat(Liveness.liveness(ticket("session_pid",
                                            "99999999999",
                                            "session_start",
                                            Long.toString(START),
                                            "session_label",
                                            "CTO"),
                                     fresh())).isEqualTo("UNKNOWN(no session field)");
    }

    @Test
    void thePostHoldsThePidAndStart_sessionAlive() {
        assertThat(Liveness.liveness(sessionTicket(10, START), fresh(process(10, START, "CTO")))).isEqualTo("SESSION-ALIVE");
    }

    /// T3.3: the same pid with another start (a reused pid) is DEAD, `last seen never` when no row has that start.
    @Test
    void t33_aReusedPid_withAnotherStart_isDeadNeverSeen() {
        assertThat(Liveness.liveness(sessionTicket(10, START), fresh(process(10, START + 7, "CTO")))).isEqualTo("DEAD(last seen never)");
    }

    @Test
    void dead_namesTheLastSeenOfThatPidAndStart() {
        var seen = Instant.parse("2026-11-04T11:58:30.123Z");
        var state = new MacState(true,
                                 some(OK),
                                 List.of(),
                                 List.of(MacSession.macSession(10, START + 7, "x", seen, seen.plusSeconds(60)),
                                         MacSession.macSession(10, START, "CTO", seen, seen)));

        assertThat(Liveness.liveness(sessionTicket(10, START), state)).isEqualTo("DEAD(last seen 2026-11-04T11:58:30Z)");
    }

    /// T3.4: a silent Mac job never reads DEAD (rule 2 precedes 4-5), and names when it fell silent; `never` when it
    /// never posted.
    @Test
    void t34_aStaleSource_isStaleSource_notDead() {
        assertThat(Liveness.liveness(sessionTicket(10, START), stale())).isEqualTo("STALE-SOURCE(mac job silent since 2026-11-04T12:00:00Z)");
        assertThat(Liveness.liveness(sessionTicket(10, START),
                                     new MacState(false, none(), List.of(), List.of()))).isEqualTo("STALE-SOURCE(mac job silent since never)");
        assertThat(Liveness.liveness(sessionTicket(10, START),
                                     stale(process(10, START, "CTO")))).as("even a listed session")
                  .startsWith("STALE-SOURCE");
    }

    /// T3.5: `detached_reader` is judged by its label in the latest post; rule 3 precedes the session rules.
    @Test
    void t35_aDetachedReader_isJudgedByItsLabel() {
        var detached = ticket("detached_reader", "lead1");
        var withSession = ticket("detached_reader",
                                 "lead1",
                                 "session_pid",
                                 "10",
                                 "session_start",
                                 Long.toString(START),
                                 "session_label",
                                 "CTO");

        assertThat(Liveness.liveness(detached, fresh(process(5, START, "lead1")))).isEqualTo("DETACHED(reader=lead1 ALIVE)");
        assertThat(Liveness.liveness(detached, fresh(process(5, START, "other")))).isEqualTo("DETACHED(reader=lead1 DEAD)");
        assertThat(Liveness.liveness(withSession,
                                     fresh(process(10, START, "CTO")))).as("the reader, not the session")
                  .isEqualTo("DETACHED(reader=lead1 DEAD)");
        assertThat(Liveness.liveness(detached, stale())).as("rule 2 precedes 3").startsWith("STALE-SOURCE");
    }

    @Test
    void theAgent_doesNotChangeTheValue() {
        assertThat(Liveness.liveness(ticket("agent",
                                            "t3wpb",
                                            "session_pid",
                                            "10",
                                            "session_start",
                                            Long.toString(START),
                                            "session_label",
                                            "CTO"),
                                     fresh(process(10, START, "CTO")))).isEqualTo("SESSION-ALIVE");
    }
}
