package org.pragmatica.temper.controller.digest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.controller.policy.GrantExplain;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.DigestQuery;
import org.pragmatica.temper.store.api.DigestSnapshot;
import org.pragmatica.temper.store.api.DigestStore;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventWindow;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.StoredHost;
import org.pragmatica.temper.store.api.UnitRow;
import org.pragmatica.temper.store.api.UnitStore;

import static org.pragmatica.lang.Option.option;


/// `GET /api/v1/digest` (dashboard design §2): what changed after the caller's cursor and what stands now, for an
/// agent that would otherwise poll `jobs`, `hosts` and `maintenance`. The host links' in-memory state (link state,
/// foreign reactors; it cannot fail), then the store's one-snapshot read ([DigestStore]), then the explanation of the
/// grant candidates that snapshot read ([Explainer], D1), assembled by a pure function. Stateless: the cursor is the
/// caller's, nothing is written.
///
/// A queued job's [Blocked] reasons follow one rule (D1): none when the job has no unit still QUEUED, or when one of
/// its queued units would start on some host; otherwise, per host, the reasons of its first still-queued unit (the
/// lowest id, which is also its first in grant order), [GrantExplain.NotRead] when the grant's candidates do not
/// include it. The explanation is recomputed from inputs read after the snapshot and
/// outside it (worker facts, `UnitStore.active`, the starvation state, the clock): what the policy would decide on
/// them now, not what the last tick decided; a starvation hold is forgotten when the controller restarts.
///
/// The events are split by kind: `finished` and `cancelled` events are the jobs that finished, `started` the units that
/// started, and every other kind is kept in `other`, so the whole window the cursor covers is in the answer.
@FunctionalInterface
public interface Digest {
    Set<EventRow.Kind> FINISHED = Set.of(EventRow.Kind.FINISHED, EventRow.Kind.CANCELLED);

    record Request(DigestQuery query) {
        public static Request request(DigestQuery query) {
            return new Request(query);
        }
    }

    /// `queue` lists at most [DigestStore#QUEUE_LIMIT] jobs; `queued` counts them all. `asOf` is when it was assembled.
    record Response(EventWindow window,
                    List<JobEvent> finished,
                    List<JobEvent> started,
                    List<EventRow> other,
                    List<Queued> queue,
                    int queued,
                    List<Host> hosts,
                    Option<MaintenanceRun> latestDump,
                    Option<MaintenanceRun> lastGoodDump,
                    Instant asOf) {
        public Response {
            finished = List.copyOf(finished);
            started = List.copyOf(started);
            other = List.copyOf(other);
            queue = List.copyOf(queue);
            hosts = List.copyOf(hosts);
        }
    }

    /// An event with the job it names, for the job's owner and priority (none for an event without a job).
    record JobEvent(EventRow event, Option<JobRow> job) {}

    /// A waiting job (QUEUED, or with a unit still QUEUED) at `position` (1..) in grant order, with its unit that holds or waits for the port group,
    /// and why it would not start (the rule in the type's comment).
    record Queued(int position, JobRow job, Option<UnitRow> hold, List<Blocked> blocked) {
        public Queued {
            blocked = List.copyOf(blocked);
        }
    }

    /// One reason a queued job would not start on `host`.
    record Blocked(HostName host, GrantExplain.Reason reason) {}

    /// Why each grant candidate would not start, per configured host (`Scheduler#explain`).
    @FunctionalInterface
    interface Explainer {
        Promise<List<GrantExplain.HostReasons>> explain(List<QueuedUnit> candidates);
    }

    /// A configured host: the link's state, foreign reactors and marks, joined with the stored state and active units
    /// (none and 0 for a host the store has no row of).
    record Host(HostName name, String link, Option<String> stored, int cap, int foreign, int active, Marks marks) {
        /// A7: `reactors = foreign (worker facts) + active (store)`, as `GET /hosts` counts them.
        public int reactors() {
            return foreign + active;
        }
    }

    /// One configured host as its link knows it now: the link state's wire form, its cap, and the foreign reactors of
    /// its latest facts (0 before the first facts).
    record LinkedHost(HostName name, String link, int cap, int foreign, Marks marks) {
        /// A dedicated-CPU host that is not LOST and never retired.
        public static LinkedHost linkedHost(HostName name, String link, int cap, int foreign) {
            return new LinkedHost(name, link, cap, foreign, Marks.NONE);
        }

        public static LinkedHost linkedHost(HostName name, String link, int cap, int foreign, Marks marks) {
            return new LinkedHost(name, link, cap, foreign, marks);
        }
    }

    /// A host's M4 marks (m4-design §11): its LOST mark while LOST, its CPU class and its configured `retireAt`.
    record Marks(Option<HostLoss> lost, boolean sharedCpu, Option<Instant> retireAt) {
        public static final Marks NONE = new Marks(Option.none(), false, Option.none());

        public static Marks marks(Option<HostLoss> lost, boolean sharedCpu, Option<Instant> retireAt) {
            return new Marks(lost, sharedCpu, retireAt);
        }

        /// `retireAt` when it is at or before `now` (m4-design §10: retired at the instant itself).
        public Option<Instant> retiredSince(Instant now) {
            return retireAt.filter(at -> !now.isBefore(at));
        }
    }

    /// The host links' state, read from memory.
    @FunctionalInterface
    interface Links {
        List<LinkedHost> current();
    }

    Promise<Response> execute(Request request);

    static Digest digest(DigestStore store, Links links, Explainer explainer, Supplier<Instant> clock) {
        return request -> digest(store, links, explainer, clock, request);
    }

    private static Promise<Response> digest(DigestStore store,
                                            Links links,
                                            Explainer explainer,
                                            Supplier<Instant> clock,
                                            Request request) {
        var linked = links.current();

        return store.snapshot(request.query())
                    .mapWith(snapshot -> explainer.explain(snapshot.candidates()),
                             (snapshot, explained) -> response(snapshot,
                                                               linked,
                                                               explained,
                                                               clock.get()));
    }

    private static Response response(DigestSnapshot snapshot,
                                     List<LinkedHost> linked,
                                     List<GrantExplain.HostReasons> explained,
                                     Instant asOf) {
        var events = snapshot.window().events();

        return new Response(snapshot.window(),
                            jobEvents(snapshot, events, FINISHED),
                            jobEvents(snapshot, events, Set.of(EventRow.Kind.STARTED)),
                            others(events),
                            queue(snapshot.queue(), explained),
                            snapshot.queued(),
                            hosts(linked, snapshot.hosts()),
                            snapshot.latestDump(),
                            snapshot.lastGoodDump(),
                            asOf);
    }

    private static List<JobEvent> jobEvents(DigestSnapshot snapshot, List<EventRow> events, Set<EventRow.Kind> kinds) {
        return events.stream()
                     .filter(event -> kinds.contains(event.kind()))
                     .map(event -> new JobEvent(event,
                                                jobOf(snapshot, event)))
                     .toList();
    }

    private static Option<JobRow> jobOf(DigestSnapshot snapshot, EventRow event) {
        return event.job()
                    .flatMap(job -> option(snapshot.jobs().get(job)));
    }

    private static List<EventRow> others(List<EventRow> events) {
        return events.stream()
                     .filter(event -> !FINISHED.contains(event.kind()) && event.kind() != EventRow.Kind.STARTED)
                     .toList();
    }

    private static List<Queued> queue(List<JobRow> jobs, List<GrantExplain.HostReasons> explained) {
        return IntStream.range(0,
                               jobs.size())
                        .mapToObj(index -> new Queued(index + 1,
                                                      jobs.get(index),
                                                      hold(jobs.get(index)),
                                                      blocked(jobs.get(index),
                                                              explained)))
                        .toList();
    }

    private static List<Blocked> blocked(JobRow job, List<GrantExplain.HostReasons> explained) {
        var queued = job.units().stream().filter(unit -> unit.state() == UnitState.QUEUED).map(UnitRow::id).toList();

        return queued.isEmpty() || wouldStart(queued, explained)
               ? List.of()
               : firstUnit(queued.getFirst(), explained);
    }

    private static boolean wouldStart(List<Long> queued, List<GrantExplain.HostReasons> explained) {
        return explained.stream()
                        .anyMatch(host -> queued.stream()
                                                .anyMatch(unit -> startsOn(host, unit)));
    }

    private static boolean startsOn(GrantExplain.HostReasons host, long unit) {
        return option(host.reasons().get(unit)).filter(List::isEmpty)
                     .isPresent();
    }

    private static List<Blocked> firstUnit(long unit, List<GrantExplain.HostReasons> explained) {
        return explained.stream()
                        .flatMap(host -> blockedOn(host, unit))
                        .toList();
    }

    /// Only reached when no host would grant any queued unit of the job. A host's answer omits exactly the units an
    /// earlier host would grant (none of this job's, then) and the units beyond the grant's candidates, so an absent
    /// unit is one no uncontended grant reads (an overlapping one reads past the rows another holds; [GrantExplain]).
    private static Stream<Blocked> blockedOn(GrantExplain.HostReasons host, long unit) {
        return option(host.reasons().get(unit)).or(() -> List.of(new GrantExplain.NotRead(UnitStore.CANDIDATE_LIMIT)))
                     .stream()
                     .map(reason -> new Blocked(host.host(),
                                                reason));
    }

    /// The first unit (by id) with a port-group hold.
    private static Option<UnitRow> hold(JobRow job) {
        return Option.from(job.units().stream().filter(unit -> unit.portGroup()
                                                                   .isPresent()).findFirst());
    }

    private static List<Host> hosts(List<LinkedHost> linked, List<StoredHost> stored) {
        var byName = stored.stream().collect(Collectors.toUnmodifiableMap(StoredHost::name, Function.identity()));

        return linked.stream()
                     .map(host -> host(host, byName))
                     .toList();
    }

    private static Host host(LinkedHost linked, Map<HostName, StoredHost> stored) {
        var row = option(stored.get(linked.name()));

        return new Host(linked.name(),
                        linked.link(),
                        row.map(StoredHost::state),
                        linked.cap(),
                        linked.foreign(),
                        row.map(StoredHost::active).or(0),
                        linked.marks());
    }
}
