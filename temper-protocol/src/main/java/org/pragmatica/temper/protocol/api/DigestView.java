package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.ResultRecordDto.CountsDto;

import static org.pragmatica.lang.Option.option;


/// `GET /api/v1/digest` answer (dashboard design §2): what changed after the caller's cursor and what stands now, in
/// one read an agent can take in place of polling `jobs`, `hosts` and `maintenance`. [DigestText] renders it as text.
///
/// The cursor guarantee: every event up to the cursor's `seq` is in `finished`, `started` or `other`, or inside `gap`
/// (deleted by retention before this reader got to it). `more` says the answer stopped at `max` events; the next call
/// with `since = cursor` continues right after the last one shown. A cursor not from this event log is refused (409
/// `CURSOR_FOREIGN`) once the event it names differs or is missing; one ahead of the log is a 400. The events, the queue, a host's stored `state` and
/// active units, and the dumps come from one database snapshot; a host's `link` and `foreign` are the host links'
/// memory, read just before it. `asOf` is when the answer was assembled.
///
/// A queued job's `blocked` is weaker (D1): it is recomputed from inputs read after that snapshot and outside it (the
/// host's worker facts, its active units, the scheduler's in-memory starvation state, the clock). It is what the grant
/// policy would decide on those inputs, not a record of what the last tick decided, and a starvation hold is
/// forgotten when the controller restarts.
///
/// Nothing in it says an alarm was delivered: delivery state lives in the GitHub issues `temper alarm-check` keeps.
public record DigestView(String cursor,
                         boolean more,
                         Option<Gap> gap,
                         Instant asOf,
                         List<Finished> finished,
                         List<Started> started,
                         List<Other> other,
                         List<Queued> queue,
                         int queued,
                         List<Host> hosts,
                         MaintenanceView maintenance) {
    /// The cursor's wire form, `<seq>.<token>`: opaque to a caller, which hands back the one it got. The token names
    /// the event at `seq`, so a cursor from another event log is refused once that event differs or is missing.
    /// `temper-store`'s `EventCursor.WIRE` is the same pattern (pinned by a test).
    public static final Pattern CURSOR = Pattern.compile("^([0-9]{1,18})\\.([0-9]{1,18}|-)$");
    /// The most events one digest shows (`max`); `temper-store`'s `DigestQuery.MAX_EVENTS`, pinned by the same test.
    public static final int MAX_EVENTS = 1000;

    public DigestView {
        finished = List.copyOf(finished);
        started = List.copyOf(started);
        other = List.copyOf(other);
        queue = List.copyOf(queue);
        hosts = List.copyOf(hosts);
    }

    public static DigestView digestView(String cursor,
                                        boolean more,
                                        Option<Gap> gap,
                                        Instant asOf,
                                        List<Finished> finished,
                                        List<Started> started,
                                        List<Other> other,
                                        List<Queued> queue,
                                        int queued,
                                        List<Host> hosts,
                                        MaintenanceView maintenance) {
        return new DigestView(cursor, more, gap, asOf, finished, started, other, queue, queued, hosts, maintenance);
    }

    /// Events `from..to` (inclusive) were deleted by retention before this reader got to them.
    public record Gap(long from, long to) {
        public static Gap gap(long from, long to) {
            return new Gap(from, to);
        }
    }

    /// A `finished` or `cancelled` event: the job's verdict as the event recorded it, with the job's owner and
    /// priority. `known` is the record's `KNOWN(#n)` annotations as the event line carries them, the same text in the
    /// same order (ruling R-M2-14); an answer without the field reads as none, and a null element in it is dropped [G]
    /// (annotations never change a verdict, so one bad element must not fail the whole digest). `userProperties` are the
    /// job's user `-D` properties, skip and select ones marked (maintainer ruling Q-M4-11c, [UserPropertyDto]); none for an
    /// event without a job, and an answer without the field reads as none.
    public record Finished(long seq,
                           Instant at,
                           Option<Long> job,
                           EventKind kind,
                           Option<String> verdict,
                           Option<String> reason,
                           Option<String> sha,
                           Option<String> ref,
                           Option<String> owner,
                           Option<Integer> prio,
                           Option<CountsDto> counts,
                           List<String> known,
                           List<UserPropertyDto> userProperties) {
        public Finished {
            known = option(known).map(Finished::present).or(List.of());
            userProperties = option(userProperties).map(List::copyOf).or(List.of());
        }

        private static List<String> present(List<String> annotations) {
            return annotations.stream()
                              .flatMap(annotation -> option(annotation).stream())
                              .toList();
        }

        public static Finished finished(long seq,
                                        Instant at,
                                        Option<Long> job,
                                        EventKind kind,
                                        Option<String> verdict,
                                        Option<String> reason,
                                        Option<String> sha,
                                        Option<String> ref,
                                        Option<String> owner,
                                        Option<Integer> prio,
                                        Option<CountsDto> counts,
                                        List<String> known,
                                        List<UserPropertyDto> userProperties) {
            return new Finished(seq,
                                at,
                                job,
                                kind,
                                verdict,
                                reason,
                                sha,
                                ref,
                                owner,
                                prio,
                                counts,
                                known,
                                userProperties);
        }
    }

    /// A `started` event: one unit of the job started (the job turns RUNNING with its first).
    public record Started(long seq,
                          Instant at,
                          Option<Long> job,
                          Option<String> unit,
                          Option<String> sha,
                          Option<String> ref,
                          Option<String> owner,
                          Option<Integer> prio) {
        public static Started started(long seq,
                                      Instant at,
                                      Option<Long> job,
                                      Option<String> unit,
                                      Option<String> sha,
                                      Option<String> ref,
                                      Option<String> owner,
                                      Option<Integer> prio) {
            return new Started(seq, at, job, unit, sha, ref, owner, prio);
        }
    }

    /// Every other event (`queued`, `start-unknown`, `unit-finished`, `superseded`), so that the window is shown whole.
    public record Other(long seq,
                        Instant at,
                        Option<Long> job,
                        EventKind kind,
                        Option<String> unit,
                        Option<String> verdict,
                        Option<Long> supersededBy) {
        public static Other other(long seq,
                                  Instant at,
                                  Option<Long> job,
                                  EventKind kind,
                                  Option<String> unit,
                                  Option<String> verdict,
                                  Option<Long> supersededBy) {
            return new Other(seq, at, job, kind, unit, verdict, supersededBy);
        }
    }

    /// A waiting job, `pos` 1.. in grant order: a stored QUEUED job, or any other with a unit still QUEUED (a RUNNING
    /// Gate whose remaining shards wait for a grant). `state` is the one `GET /jobs/{id}` shows: QUEUED, UNKNOWN when a
    /// unit of a QUEUED job runs with no recorded start (ruling R-13), or the job's own (RUNNING, ...). `waitingSince` is its submit time; `portGroup`
    /// and `portGroupSince` are a unit's bb-hold (`waiting` for the group, `held` once granted) and when it entered it.
    /// `blocked` says why the job would not start (D1, [Blocked]); empty when a still-queued unit of it would start on
    /// some host at the next tick, when none of its units is still queued, or when no host is configured.
    public record Queued(int pos,
                         long job,
                         JobState state,
                         int prio,
                         String owner,
                         Option<String> sha,
                         Option<String> ref,
                         Instant waitingSince,
                         Option<String> portGroup,
                         Option<Instant> portGroupSince,
                         List<Blocked> blocked) {
        /// An answer from a controller older than D1 has no `blocked` (Jackson passes null): read as none.
        public Queued {
            blocked = Option.option(blocked).map(List::copyOf).or(List.of());
        }

        public static Queued queued(int pos,
                                    long job,
                                    JobState state,
                                    int prio,
                                    String owner,
                                    Option<String> sha,
                                    Option<String> ref,
                                    Instant waitingSince,
                                    Option<String> portGroup,
                                    Option<Instant> portGroupSince,
                                    List<Blocked> blocked) {
            return new Queued(pos, job, state, prio, owner, sha, ref, waitingSince, portGroup, portGroupSince, blocked);
        }
    }

    /// One reason a queued job would not start on `host` (D1): what the grant policy would decide on a snapshot of
    /// that host taken when the digest was assembled, not what the last tick decided. `reason` is one of `cap` (host
    /// reactors reached the cap; `detail` `reactors/cap foreign=N`), `memory` (`active+unit>budget` MB), `mem-floor`
    /// (the host's MemAvailable, less temper's units it does not reflect yet and the unit, would fall below its
    /// `memFloorGiB`; `free=M pending=M unit=M floor=MMB`), `disk-floor` (`free=B floor=B`), `port-group` (`units/maxHolding` waiting for or holding the group), `starvation-hold` (it
    /// fits, but the head has been blocked past the starvation bound; `head=<unit id> since=<instant>`), `no-link`
    /// (the scheduler skips the host, not UP or without worker facts: `link=<state>`, plus `no-facts` when it has none;
    /// `link=DEFERRED` while a LOST verdict is deferred, m4-design §2.1), `lost` (the host is LOST, so the scheduler
    /// skips it until it recovers: `<link-down|host-key> since=<instant>`, m4-design §2, §11),
    /// `busy` (the scheduler's previous round on the host has not ended, so the tick skips it; `round-in-flight`),
    /// `unknown` (the host's active units could not be read within the tick bound; `active-read-failed`; the
    /// cause is in the controller log) and `not-read` (the unit is beyond the first N queued units of every CPU class,
    /// the rows a dedicated-CPU host's grant reads while no other grant holds the queue's rows; `limit=N`. A shared-CPU
    /// host's grant skips the `--dedicated-cpu` units, so its own window can reach further down the queue: there
    /// `not-read` may name a unit that grant would read, never the reverse), `retired` (the host's `retireAt` has passed, so the scheduler grants
    /// nothing there; `since=<instant>`, m4-design §10) and `shared-cpu` (the unit's job asked for `--dedicated-cpu` and
    /// the host's CPU is shared, so its grant never reads the unit; `dedicated-cpu`, §9.2). With several hosts, each host is
    /// judged after the earlier ones (configured order) took what they would grant.
    public record Blocked(String host, String reason, String detail) {
        public static Blocked blocked(String host, String reason, String detail) {
            return new Blocked(host, reason, detail);
        }
    }

    /// A configured host: `link` is the live link state (`DOWN`, `CONNECTING`, `UP`), the one the scheduler grants by;
    /// `state` the one the controller last stored (absent for a host with no row; `LOST` while LOST). `reactors =
    /// foreign + active` (A7), as `GET /hosts` counts them. M4 (m4-design §11): `lost` is the link's LOST mark (when and
    /// why), `sharedCpu` the host's CPU class, `retireAt` its configured retirement and `retired` whether it has passed
    /// at `asOf`. A host written without them (an older controller) reads none and false.
    public record Host(String name,
                       String link,
                       Option<String> state,
                       int cap,
                       int reactors,
                       int foreign,
                       int active,
                       Option<HostLossDto> lost,
                       Boolean sharedCpu,
                       Option<Instant> retireAt,
                       Boolean retired) {
        public Host {
            sharedCpu = option(sharedCpu).or(false);
            retired = option(retired).or(false);
        }

        public static Host host(String name,
                                String link,
                                Option<String> state,
                                int cap,
                                int reactors,
                                int foreign,
                                int active) {
            return new Host(name,
                            link,
                            state,
                            cap,
                            reactors,
                            foreign,
                            active,
                            Option.none(),
                            false,
                            Option.none(),
                            false);
        }

        public static Host host(String name,
                                String link,
                                Option<String> state,
                                int cap,
                                int reactors,
                                int foreign,
                                int active,
                                Option<HostLossDto> lost,
                                boolean sharedCpu,
                                Option<Instant> retireAt,
                                boolean retired) {
            return new Host(name, link, state, cap, reactors, foreign, active, lost, sharedCpu, retireAt, retired);
        }
    }
}
