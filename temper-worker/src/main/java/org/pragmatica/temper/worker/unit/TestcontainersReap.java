package org.pragmatica.temper.worker.unit;

import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.VoidReason;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.docker.TcContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Unit.unit;


/// The Testcontainers reap (m0-design §12.4, S1 lesson): a unit is not clean until the Testcontainers containers
/// it spawned are gone. Ryuk removes them once the unit's JVMs are dead; the worker polls every `poll` until none
/// is left or the window has passed, and reports whatever is left. The window is checked after each `docker ps`, so
/// the reap can take up to the window plus one `poll` plus the `docker ps` calls (about 62 s for the 60 s
/// `reapWait`, about 7 s for the 5 s `voidedReapWait`, with production timings). Each poll is scheduled afresh, so
/// the call depth does not grow with the number of polls.
///
/// **A unit voided for `port-group-lost` waits `voidedReapWait`** (`.temper/void` holds that reason: `HoldMonitor`
/// voided and killed it). Its verdict is already decided, and the reap delays the `UnitExited` that delivers it, so
/// the window is short enough to keep `VOID(port-group-lost)` inside A11's 30 s (m1-design §9.1). Ryuk needs longer
/// than that window, so such a unit's own containers are typically still present when it ends: they are reported
/// with `unverified` set to [#CUT_SHORT] (present when the cut-short window ended, not proven stuck). A unit voided
/// for `disk` keeps running and exits normally, so it waits the full `reapWait` like any other unit.
///
/// **Attribution, and its honest bound.** Containers started through the shared socket carry a session id but
/// nothing that names the unit. At unit start the worker records the session ids that already exist
/// (`.temper/tc-sessions-start`); at exit, a Testcontainers container counts as the unit's when it has a session id
/// and that session is NOT in that set. The guarantee is therefore: leftovers are the containers of sessions that
/// were new since the unit started and were still present at exit. Two kinds of the unit's own containers carry no
/// session id and are invisible to it: its Ryuk (removed on its own) and its reusable containers
/// (`testcontainers.reuse.enable`). A container without a session id is never counted, because on a shared socket
/// nothing names its owner (A11 live, 2026-10-14: one without, most likely another process's Ryuk, was reported as
/// every unit's leftover). The rule can over-include a session another process (a bare-host bb-* build, a
/// concurrent unit) started during the unit's run: such a container is waited for and may be reported as a
/// leftover. A unit started without the snapshot file attributes every session.
public record TestcontainersReap(DockerCli docker,
                                 UnitFiles files,
                                 TimeSpan reapWait,
                                 TimeSpan voidedReapWait,
                                 TimeSpan poll,
                                 InstantSource clock) {
    private static final Logger LOG = LoggerFactory.getLogger(TestcontainersReap.class);

    /// The `unverified` reason of a port-group-voided unit's leftovers: its window was cut short.
    public static final String CUT_SHORT = "reap window cut short (unit voided for port-group-lost): present when it ended, not proven stuck";

    public static TestcontainersReap testcontainersReap(DockerCli docker,
                                                        UnitFiles files,
                                                        TimeSpan reapWait,
                                                        TimeSpan voidedReapWait,
                                                        TimeSpan poll,
                                                        InstantSource clock) {
        return new TestcontainersReap(docker, files, reapWait, voidedReapWait, poll, clock);
    }

    /// Records the session ids present now, before the unit's container starts.
    public Promise<Unit> snapshot(UnitRef ref) {
        return docker.testcontainers()
                     .flatMap(containers -> snapshotStored(ref, containers).async());
    }

    private Result<Unit> snapshotStored(UnitRef ref, List<TcContainer> containers) {
        return files.store(ref, UnitFile.TC_SESSIONS_START, String.join("\n", sessions(containers)));
    }

    /// Waits for the unit's Testcontainers containers to disappear (up to [#window]); returns the leftovers.
    ///
    /// FER (degrade forward): when docker cannot be asked, the reap cannot be verified; that is reported as
    /// [TcLeftovers#unverified] with the reason rather than failing the unit's exit processing, because the reap is
    /// evidence about cleanliness, not an input to the verdict. Guarantee: an unverifiable reap is never reported
    /// clean. Mechanism: the recovered value carries the cause's message in its own field.
    public Promise<TcLeftovers> await(UnitRef ref) {
        var window = window(ref);
        var deadline = clock.instant().plus(window.span().duration());

        return sessionsBefore(ref).async()
                             .flatMap(before -> polled(before, deadline, window))
                             .onFailure(cause -> LOG.warn("unit {}: Testcontainers reap not verifiable: {}",
                                                          ref.containerName(),
                                                          cause))
                             .recover(cause -> TcLeftovers.tcLeftovers(List.of(),
                                                                       some(cause.message())))
                             .onSuccess(leftovers -> LOG.info("unit {}: Testcontainers leftovers after the reap: {}",
                                                              ref.containerName(),
                                                              leftovers));
    }

    /// How long the reap may wait, and the `unverified` reason of what is left when it ends.
    private record ReapWindow(TimeSpan span, Option<String> cutShort) {
        static ReapWindow reapWindow(TimeSpan span, Option<String> cutShort) {
            return new ReapWindow(span, cutShort);
        }
    }

    /// `voidedReapWait` for a unit voided for `port-group-lost`, `reapWait` otherwise (no void, or `disk`).
    private ReapWindow window(UnitRef ref) {
        return voidReason(ref).filter(VoidReason.PORT_GROUP_LOST.wire()::equals)
                         .map(_ -> ReapWindow.reapWindow(voidedReapWait,
                                                         some(CUT_SHORT)))
                         .or(ReapWindow.reapWindow(reapWait,
                                                   none()));
    }

    /// FER (degrade forward): an unreadable `.temper/void` reads as no reason, so the full window. Guarantee: a reap
    /// is never cut short without the recorded port-group-lost reason; the cost is a voided unit's verdict delayed by
    /// up to the full window. Mechanism: the read failure is logged and replaced by `none()`.
    private Option<String> voidReason(UnitRef ref) {
        return files.text(ref, UnitFile.VOID)
                    .onFailure(cause -> LOG.warn("unit {}: void reason unreadable, full reap window: {}",
                                                 ref.containerName(),
                                                 cause))
                    .or(none())
                    .map(String::trim);
    }

    private Result<Set<String>> sessionsBefore(UnitRef ref) {
        return files.text(ref, UnitFile.TC_SESSIONS_START)
                    .map(text -> text.map(TestcontainersReap::lines)
                                     .or(Set.of()));
    }

    /// The containers of sessions that did not exist before the unit started; a container without a session id
    /// belongs to no session, so never to the unit.
    public static List<TcContainer> attributed(List<TcContainer> containers, Set<String> sessionsBefore) {
        return containers.stream()
                         .filter(container -> isNewSession(container, sessionsBefore))
                         .toList();
    }

    private static boolean isNewSession(TcContainer container, Set<String> sessionsBefore) {
        return ! container.sessionId()
                          .isEmpty() && !sessionsBefore.contains(container.sessionId());
    }

    /// Iteration: one `docker ps` per poll until nothing is left or the deadline passed. Each poll is a fresh
    /// promise started by a timer, never chained onto the previous one, so the depth stays constant (F6).
    private Promise<TcLeftovers> polled(Set<String> before, Instant deadline, ReapWindow window) {
        return promise(result -> polledInto(result, before, deadline, window));
    }

    private Unit polledInto(Promise<TcLeftovers> result, Set<String> before, Instant deadline, ReapWindow window) {
        // May run late: these callbacks are what resolve `result`, which is all the caller waits for.
        docker.testcontainers()
              .map(containers -> attributed(containers, before))
              .onFailure(result::fail)
              .onSuccess(left -> settledOrNext(result, left, before, deadline, window));

        return unit();
    }

    private Unit settledOrNext(Promise<TcLeftovers> result,
                               List<TcContainer> left,
                               Set<String> before,
                               Instant deadline,
                               ReapWindow window) {
        return left.isEmpty() || !clock.instant()
                                       .isBefore(deadline)
               ? settled(result, leftovers(left, window))
               : next(result, before, deadline, window);
    }

    private static Unit settled(Promise<TcLeftovers> result, TcLeftovers leftovers) {
        result.succeed(leftovers);

        return unit();
    }

    private Unit next(Promise<TcLeftovers> result, Set<String> before, Instant deadline, ReapWindow window) {
        // May run late: the next poll is a detached chain; `result` carries the outcome.
        promise(poll, Result::unitResult).onSuccessRun(() -> polledInto(result, before, deadline, window));

        return unit();
    }

    /// The containers left; a non-empty list after a cut-short window carries that window's `unverified` reason.
    private static TcLeftovers leftovers(List<TcContainer> left, ReapWindow window) {
        return TcLeftovers.tcLeftovers(left.stream()
                                           .map(container -> TcLeftovers.TcLeftover.tcLeftover(container.id(),
                                                                                               container.sessionId()))
                                           .toList(),
                                       window.cutShort().filter(_ -> !left.isEmpty()));
    }

    private static Set<String> sessions(List<TcContainer> containers) {
        return containers.stream()
                         .map(TcContainer::sessionId)
                         .filter(session -> !session.isEmpty())
                         .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> lines(String text) {
        return Arrays.stream(text.split("\n"))
                     .map(String::trim)
                     .filter(line -> !line.isEmpty())
                     .collect(Collectors.toUnmodifiableSet());
    }
}
