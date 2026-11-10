package org.pragmatica.temper.acceptance.lost;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.temper.acceptance.stack.AcceptanceStack;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.UnitView;
import org.pragmatica.temper.store.api.HostName;

/// The steps `MultiHostLostIT` and `SideEffectNotRequeuedIT` share on the two-host stack (m4-design §12.3): the
/// fixture's `slow` module test, a unit seen RUNNING on one host, that host blackholed and seen LOST.
final class LostHosts {
    /// `[scheduler] hostLostSeconds` for these tests; the liveness tick (5 s, production) decides after it.
    static final Duration HOST_LOST = Duration.ofSeconds(5);
    static final Duration BOUND = Duration.ofMinutes(5);

    private LostHosts() {}

    /// The fixture module the `slow` overlay adds `SlowProbeTest` to.
    static final String MODULE = "includes";
    static final String SLOW = "fx.includes.SlowProbeTest";

    /// Waits until both hosts are healthy, not merely UP: the LOST clock starts at controller start and clears only
    /// after `stableUp` (60 s, production) of UP (m4-design §2.1, R-M4-16). Before that a host past `hostLost` is
    /// deferred, and its link leaving UP makes it LOST at once (Q-M4-12 (b)), not `hostLost` later. Two liveness ticks
    /// of margin.
    static void healthy(AcceptanceStack stack) throws Exception {
        Thread.sleep(LinkTiming.DEFAULT.stableUp().plus(LinkTiming.DEFAULT.livenessCheck().duration().multipliedBy(2)));
        stack.awaitHostUp(Duration.ofSeconds(10));
    }

    /// The job's only unit once it is RUNNING (its start recorded), with the host it runs on.
    static UnitView running(AcceptanceStack stack, long job) throws Exception {
        return until(BOUND, "job " + job + " RUNNING", () -> {
            var units = stack.job(job).units();
            return units.size() == 1 && units.getFirst().state().name().equals("RUNNING") && units.getFirst().startedAt().isPresent()
                   ? Optional.of(units.getFirst())
                   : Optional.empty();
        });
    }

    static HostName hostOf(UnitView unit) {
        return HostName.hostName(unit.host().or("")).expect("a running unit has a host");
    }

    static HostName other(HostName host) {
        return host.equals(AcceptanceStack.HOST_A) ? AcceptanceStack.HOST_B : AcceptanceStack.HOST_A;
    }

    static HostView view(AcceptanceStack stack, HostName host) throws Exception {
        return stack.hosts()
                    .stream()
                    .filter(view -> view.name().equals(host.value()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no host view " + host));
    }

    /// The host's view the first time `GET /hosts` shows it LOST.
    static HostView lost(AcceptanceStack stack, HostName host) throws Exception {
        return until(BOUND, host + " LOST", () -> Optional.of(view(stack, host)).filter(view -> view.state().equals(HostView.LOST)));
    }

    /// The host's view the first time it is UP again with no loss mark.
    static HostView recovered(AcceptanceStack stack, HostName host) throws Exception {
        return until(BOUND, host + " UP again", () -> Optional.of(view(stack, host))
                                                              .filter(view -> view.state().equals("UP") && view.lost().isEmpty()));
    }

    /// `temper digest`'s `host <name> …` line.
    static String digestLine(AcceptanceStack stack, HostName host) {
        var digest = stack.temper("digest");
        if (digest.rc() != 0) {
            throw new AssertionError("temper digest exited " + digest.rc() + ": " + digest.stderrText());
        }
        return digest.stdoutText()
                     .lines()
                     .filter(line -> line.startsWith("host " + host.value() + " "))
                     .findFirst()
                     .orElseThrow(() -> new AssertionError("no digest line for " + host + ":\n" + digest.stdoutText()));
    }

    /// `docker ps -a` names of the unit's containers in the given attempt (the worker labels `temper.attempt`).
    static String containers(long job, String unit, int attempt) throws Exception {
        var process = HermeticProcess.builder("docker", "ps", "-a", "--filter", "name=^temper-" + job + "-" + unit + "$", "--filter",
                                         "label=temper.attempt=" + attempt, "--format", "{{.Names}}").redirectErrorStream(true)
                                                                                                      .start();
        var out = new String(process.getInputStream().readAllBytes()).strip();
        if (process.waitFor() != 0) {
            throw new AssertionError("docker ps: " + out);
        }
        return out;
    }

    /// Every unit container START on the docker daemon from now on, as `name|attempt` lines, read from one live
    /// `docker events` subscription (m4-design §2.4 "execution"). A start is an execution: the subscription keeps it
    /// after the container is removed, so a container the other host's adoption guard removed is still counted.
    ///
    /// Live, not replayed (M4 verification F5): `docker events --since … --until …` replays from the daemon's event
    /// buffer, which keeps only the last 256 events of every kind, daemon-wide (moby `daemon/events/events.go`,
    /// `eventsLimit = 256`, the oldest discarded in `PublishMessage`), so on a busy shared daemon an early start ages out
    /// of a window that spans minutes. A live subscriber is sent every matching event as it is published: the filter
    /// runs before the send, into a channel buffered at 1024 (`bufferSize`; `pubsub.Publisher.sendTopic`), and a send is
    /// skipped only when that buffer stays full 100 ms, which a reader draining a handful of starts never lets happen.
    /// `--since` covers only the moment between this process starting and the daemon registering it. The label filter
    /// (`temper.attempt`, on every unit container since M4) keeps the stream to temper's containers; the name is matched
    /// exactly in [#of], since the unit's probe container carries the same labels under a longer name.
    static final class Starts implements AutoCloseable {
        private final Process process;
        private final List<String> lines = new CopyOnWriteArrayList<>();

        private Starts(Process process) {
            this.process = process;
            Thread.ofVirtual().name("docker-events").start(this::drain);
        }

        static Starts watch() throws Exception {
            return new Starts(HermeticProcess.builder("docker", "events", "--since", String.valueOf(Instant.now().getEpochSecond()),
                                                 "--filter", "type=container", "--filter", "event=start", "--filter",
                                                 "label=temper.attempt", "--format",
                                                 "{{.Actor.Attributes.name}}|{{index .Actor.Attributes \"temper.attempt\"}}").redirectErrorStream(true)
                                                                                                                         .start());
        }

        private void drain() {
            try (var reader = process.inputReader()) {
                reader.lines().forEach(lines::add);
            } catch (Exception e) {
                lines.add("unreadable: " + e);
            }
        }

        /// The `temper.attempt` label of every start of the unit's container so far, in order. Fails when the
        /// subscription ended, since a stream that stopped would undercount.
        List<String> of(long job, String unit) {
            if (!process.isAlive()) {
                throw new AssertionError("docker events ended (rc " + process.exitValue() + "): " + lines);
            }
            var name = "temper-" + job + "-" + unit + "|";
            return lines.stream()
                        .filter(line -> line.startsWith(name))
                        .map(line -> line.substring(name.length()))
                        .toList();
        }

        @Override
        public void close() {
            process.destroy();
        }
    }

    static <T> T until(Duration bound, String what, Callable<Optional<T>> probe) throws Exception {
        var deadline = Instant.now().plus(bound);
        while (Instant.now().isBefore(deadline)) {
            var seen = probe.call();
            if (seen.isPresent()) {
                return seen.get();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("not within " + bound + ": " + what);
    }
}
