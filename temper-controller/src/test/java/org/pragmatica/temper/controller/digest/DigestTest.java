package org.pragmatica.temper.controller.digest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.controller.policy.Fit;
import org.pragmatica.temper.controller.policy.GrantExplain;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.DigestQuery;
import org.pragmatica.temper.store.api.DigestSnapshot;
import org.pragmatica.temper.store.api.DigestStore;
import org.pragmatica.temper.store.api.EventCursor;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventWindow;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.StoredHost;
import org.pragmatica.temper.store.api.UnitStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [Digest]'s assembly over a stubbed store and links: every event of the window lands in exactly one part, queue
/// positions follow the store's grant order, a hold is found, and the hosts are the configured ones joined with what
/// the store holds (A7 reactors).
class DigestTest {
    /// A LOST, shared-CPU host with a `retireAt`: its marks reach the response as the link gave them (m4-design §11).
    private static final Digest.Marks SPARE_MARKS = Digest.Marks.marks(some(HostLoss.hostLoss(Instant.parse("2026-10-16T09:00:00Z"),
                                                                                              "host-key")),
                                                                       true,
                                                                       some(Instant.parse("2026-11-13T00:00:00Z")));
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final HostName BB = HostName.hostName("bb").unwrap();
    private static final HostName SPARE = HostName.hostName("spare").unwrap();
    private static final EventPayload PAYLOAD = EventPayload.eventPayload(none(), none(), none(), none(), none(), none(), none(), none());

    @Test
    void execute_splitsTheWindowByKind_numbersTheQueue_joinsTheHosts() {
        var finishedJob = Rows.job(7, JobState.PASSED, List.of());
        var queuedFirst = Rows.job(9, JobState.QUEUED, List.of(Rows.granted(NOW.minusSeconds(30), some(PortGroup.WAITING))));
        var queuedSecond = Rows.job(8, JobState.QUEUED, List.of(Rows.unit(UnitState.QUEUED, none(), none())));
        var events = List.of(event(1, EventRow.Kind.QUEUED, some(7L)),
                             event(2, EventRow.Kind.STARTED, some(7L)),
                             event(3, EventRow.Kind.UNIT_FINISHED, some(7L)),
                             event(4, EventRow.Kind.FINISHED, some(7L)),
                             event(5, EventRow.Kind.CANCELLED, some(99L)),
                             event(6, EventRow.Kind.SUPERSEDED, none()));
        var snapshot = DigestSnapshot.digestSnapshot(EventWindow.eventWindow(some(EventCursor.noEvent(0)), 0, 6, none(), events, 10).unwrap(),
                                                     Map.of(finishedJob.id(), finishedJob),
                                                     List.of(queuedFirst, queuedSecond),
                                                     5,
                                                     List.of(),
                                                     List.of(StoredHost.storedHost(BB, "UP", 2)),
                                                     none(),
                                                     none());
        var queries = new CopyOnWriteArrayList<DigestQuery>();
        DigestStore store = query -> record(queries, query, snapshot);
        Digest.Links links = () -> List.of(Digest.LinkedHost.linkedHost(BB, "UP", 5, 1),
                                           Digest.LinkedHost.linkedHost(SPARE, "DOWN", 3, 0, SPARE_MARKS));
        var query = DigestQuery.digestQuery(some(EventCursor.noEvent(0)), 10).unwrap();

        var response = Digest.digest(store, links, _ -> Promise.success(List.of()), () -> NOW)
                             .execute(Digest.Request.request(query))
                             .await()
                             .unwrap();

        assertThat(queries).containsExactly(query);
        assertThat(seqs(response.finished())).containsExactly(4L, 5L);
        assertThat(response.finished().getFirst().job()).isEqualTo(some(finishedJob));
        assertThat(response.finished().get(1).job()).as("a job the snapshot does not hold").isEqualTo(none());
        assertThat(seqs(response.started())).containsExactly(2L);
        assertThat(response.other().stream().map(EventRow::seq).toList()).containsExactly(1L, 3L, 6L);
        assertThat(response.queue().stream().map(Digest.Queued::position).toList()).containsExactly(1, 2);
        assertThat(response.queue().stream().map(queued -> queued.job().id()).toList()).containsExactly(JobId.jobId(9).unwrap(),
                                                                                                         JobId.jobId(8).unwrap());
        assertThat(response.queue().getFirst().hold().flatMap(unit -> unit.portGroup())).isEqualTo(some(PortGroup.WAITING));
        assertThat(response.queue().get(1).hold()).isEqualTo(none());
        assertThat(response.queued()).isEqualTo(5);
        assertThat(response.hosts()).containsExactly(new Digest.Host(BB, "UP", some("UP"), 5, 1, 2, Digest.Marks.NONE),
                                                     new Digest.Host(SPARE, "DOWN", none(), 3, 0, 0, SPARE_MARKS));
        assertThat(response.hosts().getFirst().reactors()).isEqualTo(3);
        assertThat(response.asOf()).isEqualTo(NOW);
        assertThat(response.window().cursor().seq()).isEqualTo(6L);
    }

    /// The per-job rule (D1): the reasons of the job's first still-queued unit, per host; none when any queued unit of
    /// it would start somewhere or none is still queued; `not-read` for a unit the grant's candidates do not include.
    /// The explainer is asked about exactly the snapshot's candidates.
    @Test
    void execute_blockedJobs_carryTheirFirstQueuedUnitsReasonsPerHost() {
        var portGroupFull = new Fit.PortGroup(1, 1);
        var memory = new Fit.Memory(8192, 6144, 8192);
        var down = new GrantExplain.NoLink("DOWN", false);
        var blocked = Rows.job(9, JobState.QUEUED, List.of(Rows.unit(11, UnitState.QUEUED), Rows.unit(12, UnitState.QUEUED)));
        var startsWithItsSecond = Rows.job(8, JobState.QUEUED, List.of(Rows.unit(21, UnitState.QUEUED), Rows.unit(22, UnitState.QUEUED)));
        var notRead = Rows.job(7, JobState.QUEUED, List.of(Rows.unit(31, UnitState.QUEUED)));
        var granted = Rows.job(6, JobState.QUEUED, List.of(Rows.unit(41, UnitState.GRANTED)));
        var firstQueuedIsSecond = Rows.job(5, JobState.QUEUED, List.of(Rows.unit(51, UnitState.GRANTED), Rows.unit(52, UnitState.QUEUED)));
        var candidates = List.of(candidate(11), candidate(12), candidate(21), candidate(22), candidate(52));
        var snapshot = DigestSnapshot.digestSnapshot(EventWindow.eventWindow(some(EventCursor.noEvent(0)), 0, 0, none(), List.of(), 10).unwrap(),
                                                     Map.of(),
                                                     List.of(blocked, startsWithItsSecond, notRead, granted, firstQueuedIsSecond),
                                                     5,
                                                     candidates,
                                                     List.of(),
                                                     none(),
                                                     none());
        var asked = new CopyOnWriteArrayList<List<QueuedUnit>>();
        var bb = GrantExplain.HostReasons.hostReasons(BB, Map.of(11L, List.of(portGroupFull),
                                                                 12L, List.of(memory),
                                                                 21L, List.of(memory),
                                                                 22L, List.of(),
                                                                 52L, List.of(memory, portGroupFull)));
        var spare = GrantExplain.HostReasons.hostReasons(SPARE, Map.of(11L, List.of(down),
                                                                       12L, List.of(down),
                                                                       21L, List.of(down),
                                                                       22L, List.of(down),
                                                                       52L, List.of(down)));
        Digest.Explainer explainer = units -> explained(asked, units, List.of(bb, spare));
        var query = DigestQuery.digestQuery(some(EventCursor.noEvent(0)), 10).unwrap();

        var queue = Digest.digest(_ -> Promise.success(snapshot), List::of, explainer, () -> NOW)
                          .execute(Digest.Request.request(query))
                          .await()
                          .unwrap()
                          .queue();

        assertThat(asked).containsExactly(candidates);
        assertThat(queue.get(0).blocked()).as("no unit would start: the reasons of unit 11, not of unit 12, per host")
                                          .containsExactly(new Digest.Blocked(BB, portGroupFull),
                                                           new Digest.Blocked(SPARE, down));
        assertThat(queue.get(1).blocked()).as("unit 22 would start on bb: the job is not blocked").isEmpty();
        assertThat(queue.get(2).blocked()).as("unit 31 is beyond the grant's candidates")
                                          .containsExactly(new Digest.Blocked(BB, new GrantExplain.NotRead(UnitStore.CANDIDATE_LIMIT)),
                                                           new Digest.Blocked(SPARE, new GrantExplain.NotRead(UnitStore.CANDIDATE_LIMIT)));
        assertThat(queue.get(3).blocked()).as("no unit still queued").isEmpty();
        assertThat(queue.get(4).blocked()).as("its first still-queued unit is 52, every reason of it, per host")
                                          .containsExactly(new Digest.Blocked(BB, memory),
                                                           new Digest.Blocked(BB, portGroupFull),
                                                           new Digest.Blocked(SPARE, down));
    }

    private static QueuedUnit candidate(long id) {
        return QueuedUnit.queuedUnit(id, -20, NOW, 6144, false, UnitKind.MVN);
    }

    private static Promise<List<GrantExplain.HostReasons>> explained(List<List<QueuedUnit>> asked,
                                                                    List<QueuedUnit> units,
                                                                    List<GrantExplain.HostReasons> answer) {
        asked.add(units);
        return Promise.success(answer);
    }

    private static Promise<DigestSnapshot> record(List<DigestQuery> queries, DigestQuery query, DigestSnapshot snapshot) {
        queries.add(query);
        return Promise.success(snapshot);
    }

    private static List<Long> seqs(List<Digest.JobEvent> events) {
        return events.stream().map(named -> named.event().seq()).toList();
    }

    private static EventRow event(long seq, EventRow.Kind kind, Option<Long> job) {
        return EventRow.eventRow(seq, NOW, job.map(id -> JobId.jobId(id).unwrap()), kind, PAYLOAD);
    }
}
