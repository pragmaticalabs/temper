package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.RecordingOutbox;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.facts.FactsProbe;
import org.pragmatica.temper.worker.manifest.ManifestCollector;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The hold path's collaborators, wired as in `WorkerAssembly` but over a scripted docker ([FakeRunner]), a
/// scripted holder ([FakeHolders]), a recording port-group launcher ([FakeLauncher]), a hand-moved clock and
/// hand-fired timers. Every observation the code under test makes is appended to `events`, in order.
final class HoldFixture {
    static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    final List<String> events = new CopyOnWriteArrayList<>();
    final FakeRunner runner = new FakeRunner();
    final WorkerConfig config;
    final UnitFiles files;
    final UnitRegistry registry;
    final Fixtures.ManualTimers timers = new Fixtures.ManualTimers();
    final Fixtures.ManualClock clock = new Fixtures.ManualClock(NOW);
    final DockerCli docker;
    final RecordingOutbox outbox = new RecordingOutbox();
    final FakeHolders holders = new FakeHolders(events);
    final FakeLauncher launcher = new FakeLauncher(events);
    static final HostFacts HOST = HostFacts.hostFacts(0.5, 0.4, 8, 0, 0, none(), none(), 1000, 1L << 40, 1L << 40, NOW, List.of());
    /// What the facts probe answers; a test swaps it for a failing or a late one.
    final AtomicReference<Supplier<Promise<HostFacts>>> probe = new AtomicReference<>(() -> Promise.success(HOST));
    final FactsProbe facts = () -> probe.get()
                                        .get();
    final UnitTimer timer;
    final UnitWatcher watcher;
    final GrantWatcher grants;
    final BbQueueReap reap;

    HoldFixture(Path home) {
        this(home, UnitRegistry.unitRegistry());
    }

    HoldFixture(Path home, UnitRegistry registry) {
        this.registry = registry;
        config = Fixtures.config(home.resolve(".temper"), home.resolve("proc"), home);
        files = UnitFiles.unitFiles(config.paths());
        docker = DockerCli.dockerCli(runner, config.toolEnv(), config.timings());
        timer = UnitTimer.unitTimer(files,
                                    config.paths(),
                                    docker,
                                    ThreadDumper.threadDumper(docker, TimeSpan.timeSpan(1).seconds()),
                                    timers,
                                    clock,
                                    registry);
        watcher = UnitWatcher.unitWatcher(docker,
                                          files,
                                          registry,
                                          UnitCollector.unitCollector(config.paths(),
                                                                      files,
                                                                      docker,
                                                                      ReportCollector.reportCollector(config.paths(), files),
                                                                      TestcontainersReap.testcontainersReap(docker,
                                                                                                            files,
                                                                                                            TimeSpan.timeSpan(50).millis(),
                                                                                                            TimeSpan.timeSpan(50).millis(),
                                                                                                            TimeSpan.timeSpan(10).millis(),
                                                                                                            clock),
                                                                      facts,
                                                                      CgroupSampler.cgroupSampler(home, clock),
                                                                      ManifestCollector.manifestCollector(config.paths(),
                                                                                                          files,
                                                                                                          TemperJson.mapper()),
                                                                      TemperJson.mapper(),
                                                                      clock),
                                          outbox,
                                          TimeSpan.timeSpan(1).hours(),
                                          TimeSpan.timeSpan(1).seconds());
        reap = BbQueueReap.bbQueueReap(runner, config.bbQueue());
        grants = GrantWatcher.grantWatcher(files,
                                           docker,
                                           holders,
                                           registry,
                                           timer,
                                           watcher,
                                           facts,
                                           outbox,
                                           timers,
                                           clock,
                                           Fixtures.fastHold(),
                                           launcher,
                                           reap);
    }

    /// A registry whose removals (`stopHolding`, `forget`) take `delay` before they happen, as a late asynchronous
    /// callback would: a step that ends a watch must complete only after its removal, never before it.
    static UnitRegistry slowRemovals(Duration delay) {
        return new UnitRegistry(new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new SlowRemovals<>(delay),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new SlowRemovals<>(delay),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>());
    }

    static final class SlowRemovals<K, V> extends ConcurrentHashMap<K, V> {
        private final Duration delay;

        SlowRemovals(Duration delay) {
            this.delay = delay;
        }

        @Override
        public V remove(Object key) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.remove(key, value);
        }
    }

    /// A launch of `ref` with a port group (the values `docker run` would get do not matter to the hold code).
    UnitLaunch launch(UnitRef ref) {
        var request = Fixtures.request(ref.job()
                                          .value(),
                                       ref.unit()
                                          .value());
        var held = new StartRequest(request.ref(),
                                    request.attempt(),
                                    "GATE_UNSHARDED",
                                    request.steps(),
                                    request.timeout(),
                                    request.memory(),
                                    request.cpuShares(),
                                    request.runnerVersion(),
                                    request.mavenOpts(),
                                    request.tcHubPrefix(),
                                    some(new HoldRequest("temper", 5)),
                                    none(), InstanceId.DEFAULT);

        return UnitLaunch.unitLaunch(held,
                                     config.paths()
                                           .workspace(ref.job()),
                                     config.paths()
                                           .workspaceHome(ref.job()),
                                     1001,
                                     1001,
                                     999,
                                     WorkerConfig.DOCKER_SOCKET,
                                     UnitNetwork.HOST);
    }

    /// The holder as the test scripts it; every read is recorded as `holder:<status>`.
    static final class FakeHolders implements HolderProbe {
        private final List<String> events;
        final AtomicReference<Option<HolderStatus>> status = new AtomicReference<>(some(HolderStatus.WAITING));
        /// Each `terminate` makes the holder read as this from then on.
        final AtomicReference<HolderStatus> afterSignal = new AtomicReference<>(HolderStatus.DEAD);

        FakeHolders(List<String> events) {
            this.events = events;
        }

        void is(HolderStatus holder) {
            status.set(some(holder));
        }

        /// Statuses the next reads return, in order, before `status` applies again.
        final Deque<HolderStatus> script = new ConcurrentLinkedDeque<>();

        @Override
        public Result<Option<HolderStatus>> status(UnitRef ref) {
            var scripted = Option.option(script.poll());
            var now = scripted.isPresent()
                      ? scripted
                      : status.get();

            events.add("holder:" + now.map(HolderStatus::name)
                                      .or("none"));
            return Result.success(now);
        }

        /// While set, `terminate` fails as an unreadable `bbhold.pid` does.
        final AtomicReference<Boolean> terminateFails = new AtomicReference<>(false);

        @Override
        public Result<Boolean> terminate(UnitRef ref) {
            events.add("terminate");
            return terminateFails.get()
                   ? Causes.cause("unreadable bbhold.pid").result()
                   : signalled();
        }

        private Result<Boolean> signalled() {
            status.set(some(afterSignal.get()));
            return Result.success(true);
        }
    }

    /// A port-group launcher that records each enqueue and answers with the next scripted exit.
    static final class FakeLauncher implements PortGroupLauncher {
        private final List<String> events;
        final List<Promise<Integer>> exits = new CopyOnWriteArrayList<>();
        /// While set, `enqueue` fails (the spawn or the pid record failed).
        final AtomicReference<Option<Cause>> failure = new AtomicReference<>(none());

        FakeLauncher(List<String> events) {
            this.events = events;
        }

        @Override
        public Promise<UnitLauncher.Launch.Holding> enqueue(UnitLaunch launch, HoldRequest hold) {
            return failure.get()
                          .map(Cause::<UnitLauncher.Launch.Holding> promise)
                          .or(this::enqueued);
        }

        private Promise<UnitLauncher.Launch.Holding> enqueued() {
            var exit = Promise.<Integer> promise();

            events.add("enqueue");
            exits.add(exit);
            return Promise.success(UnitLauncher.Launch.holding(HolderRecord.holderRecord(4242, 77), exit));
        }
    }
}
