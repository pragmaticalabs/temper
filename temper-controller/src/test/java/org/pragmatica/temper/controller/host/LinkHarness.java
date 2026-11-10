package org.pragmatica.temper.controller.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.evidence.EvidenceLayout;
import org.pragmatica.temper.controller.evidence.EvidencePuller;
import org.pragmatica.temper.controller.evidence.ResultFiles;
import org.pragmatica.temper.controller.evidence.UnitEvidence;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.controller.fake.FakeWorker;
import org.pragmatica.temper.controller.fake.PipeProcess;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.frame.FrameCodec;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.BuildVersion;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.StoredEvidence;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// One host link over fake ssh processes: every `run` spawn gets a fresh [PipeProcess] with a [FakeWorker]
/// answering through the current responder; an `install` spawn reads the jar and exits 0.
final class LinkHarness implements ProcessRunner {
    /// A judged build version (D-6); [#RUNNER] is its `project.version` part, the runner version `StartUnit` carries.
    static final String VERSION = "0.1.0+0123456789ab";
    /// The full judge [#VERSION] names.
    static final String JUDGE = "0123456789ab" + "0".repeat(28);
    /// A worker jar of this build: its version resource derives [#VERSION] (L4).
    static final byte[] JAR = workerJar("0.1.0", JUDGE);

    /// A jar holding only the worker's version resource with `version=` and `judge=` lines.
    static byte[] workerJar(String projectVersion, String judge) {
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry(BuildVersion.WORKER_RESOURCE));
            zip.write(("version=" + projectVersion + "\njudge=" + judge + "\n").getBytes(StandardCharsets.UTF_8));
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    static final String RUNNER = "0.1.0";
    static final HostName HOST = HostName.hostName("bb").unwrap();
    static final LinkTiming FAST = LinkTiming.linkTiming(timeSpan(3).seconds(),
                                                         Duration.ofSeconds(4),
                                                         timeSpan(100).millis(),
                                                         timeSpan(1500).millis(),
                                                         timeSpan(1500).millis(),
                                                         timeSpan(1500).millis(),
                                                         timeSpan(5).seconds(),
                                                         BackoffStrategy.exponential()
                                                                        .initialDelay(timeSpan(50).millis())
                                                                        .maxDelay(timeSpan(200).millis())
                                                                        .factor(2.0)
                                                                        .withoutJitter(),
                                                         Duration.ofSeconds(60),
                                                         Collections.nCopies(4, timeSpan(1).millis()),
                                                         timeSpan(2).seconds(),
                                                         timeSpan(300).millis());

    /// FAST with these values: a `livenessCheck` of an hour means no tick within a test, which then ticks itself
    /// ([HostLink#livenessTick]) at the instants it chooses.
    static LinkTiming timing(TimeSpan hello, Duration silence, TimeSpan livenessCheck, TimeSpan request, Duration stableUp) {
        return LinkTiming.linkTiming(hello, silence, livenessCheck, request, FAST.prepare(), FAST.start(), FAST.deploy(),
                                     FAST.backoff(), stableUp, FAST.startRetry(), FAST.startAttemptTimeout(),
                                     FAST.unrecordedAfter());
    }

    final FakeStores.Units units = new FakeStores.Units();
    final FakeStores.Jobs jobs = new FakeStores.Jobs();
    final FakeStores.Hosts hosts = new FakeStores.Hosts();
    final FakeStores.Evidence evidence = new FakeStores.Evidence();
    final FakeStores.Results results = new FakeStores.Results();
    final List<String> pulls = new CopyOnWriteArrayList<>();
    final Path evidenceRoot;
    /// The evidence pull every unit end goes through; by default every pull succeeds at once.
    volatile EvidencePuller puller = (host, ref, attempt, kind) -> Promise.success(StoredEvidence.storedEvidence(Path.of("/ev/" + ref.containerName() + "." + kind.wire()),
                                                                                                        10,
                                                                                                        "ab"));
    final Wake wake = Wake.wake();
    final List<List<String>> spawns = new CopyOnWriteArrayList<>();
    final LinkedBlockingQueue<FakeWorker> workers = new LinkedBlockingQueue<>();
    final List<byte[]> installedJars = new CopyOnWriteArrayList<>();
    final Path jar;
    final HostLink link;
    volatile Function<Message, List<Message>> responder = LinkHarness::ackEverything;
    volatile FakeWorker lastWorker;
    /// The exit code of every `install` spawn, and what it prints on stderr first.
    volatile int installExit = 0;
    volatile String installStderr = "";
    /// The process of the latest `run` spawn (its stderr is where ssh reports a host-key failure).
    volatile PipeProcess lastProcess;
    /// Sees each message the controller sends, on the sending thread, as its frame is written (before any worker
    /// can read it, let alone answer).
    volatile Consumer<Message> onSend = _ -> {};
    /// What the link's retirement checks read as "now" (#103, [LinkEnv#withClock]): the wall clock until a test sets it.
    final AtomicReference<Instant> controlledNow = new AtomicReference<>();

    LinkHarness() throws IOException {
        this(FAST);
    }

    LinkHarness(LinkTiming timing) throws IOException {
        this(timing, false);
    }

    LinkHarness(LinkTiming timing, boolean portIsolated) throws IOException {
        this(timing, portIsolated, Duration.ofMinutes(10));
    }

    /// `hostLost`: how long the link may stay down before the host is LOST (m4-design §2.1).
    LinkHarness(LinkTiming timing, Duration hostLost) throws IOException {
        this(timing, false, hostLost);
    }

    private LinkHarness(LinkTiming timing, boolean portIsolated, Duration hostLost) throws IOException {
        this(timing, portIsolated, hostLost, 0);
    }

    /// A host with `[[hosts]] memFloorGiB = memFloorGiB`.
    static LinkHarness withMemFloor(LinkTiming timing, int memFloorGiB) throws IOException {
        return new LinkHarness(timing, false, Duration.ofMinutes(10), memFloorGiB);
    }

    /// A host with `[[hosts]] retireAt = retireAt` (m4-design §10, #103).
    static LinkHarness retiring(Instant retireAt) throws IOException {
        return new LinkHarness(FAST, false, Duration.ofMinutes(10), 0, some(retireAt));
    }

    private LinkHarness(LinkTiming timing, boolean portIsolated, Duration hostLost, int memFloorGiB) throws IOException {
        this(timing, portIsolated, hostLost, memFloorGiB, none());
    }

    private LinkHarness(LinkTiming timing,
                        boolean portIsolated,
                        Duration hostLost,
                        int memFloorGiB,
                        Option<Instant> retireAt) throws IOException {
        evidenceRoot = Files.createTempDirectory("temper-evidence");
        jar = Files.createTempFile("temper-worker", ".jar");
        Files.write(jar, JAR);
        var config = ControllerConfig.Host.host(HOST,
                                                "localhost",
                                                "builder",
                                                Path.of("/home/builder/.temper/key"),
                                                Path.of("/home/builder/.temper/known_hosts"),
                                                5,
                                                20480,
                                                portIsolated,
                                                false,
                                                retireAt,
                                                memFloorGiB);
        var env = LinkEnv.linkEnv(this,
                                  TemperJson.mapper(),
                                  units,
                                  jobs,
                                  hosts,
                                  wake,
                                  VERSION,
                                  "git@example:repo.git",
                                  1024,
                                  none(),
                                  none(),
                                  Duration.ofSeconds(30),
                                  hostLost,
                                  jar,
                                  timing,
                                  OwnerTag.ownerTag("temper").unwrap(),
                                  InstanceId.DEFAULT,
                                  UnitEvidence.unitEvidence(this::pull,
                                                            evidence,
                                                            ResultFiles.resultFiles(EvidenceLayout.evidenceLayout(evidenceRoot),
                                                                                    results,
                                                                                    Instant::now),
                                                            evidenceRoot,
                                                            0))
                         .withClock(() -> Option.option(controlledNow.get()).or(Instant::now));
        link = HostLink.hostLink(config, env);
    }

    private Promise<StoredEvidence> pull(ControllerConfig.Host host,
                                         UnitRef ref,
                                         int attempt,
                                         EvidenceKind kind) {
        pulls.add(ref.containerName() + ":" + kind.wire());
        return puller.pull(host, ref, attempt, kind);
    }

    /// The next connected fake worker (a new ssh session).
    FakeWorker nextWorker() throws InterruptedException {
        return nextWorker(10);
    }

    FakeWorker nextWorker(int seconds) throws InterruptedException {
        var worker = workers.poll(seconds, TimeUnit.SECONDS);
        if (worker == null) {
            throw new AssertionError("no ssh session within " + seconds + " s; spawns " + spawns);
        }
        lastWorker = worker;
        return worker;
    }

    static Message.Hello hello(String version, List<DiskUnitDto> units) {
        return new Message.Hello(Handshake.PROTOCOL_VERSION, version, "bb", 1000, 1000, 999, 32, facts(0), units);
    }

    static HostFactsDto facts(int foreign) {
        return facts(foreign, 1_000_000);
    }

    static HostFactsDto facts(int foreign, long memAvailableKb) {
        return facts(foreign, memAvailableKb, Instant.parse("2026-10-13T10:00:00Z"));
    }

    static HostFactsDto facts(int foreign, long memAvailableKb, Instant at) {
        return new HostFactsDto(1.0, 1.0, 32, foreign, 0, none(), none(), memAvailableKb, 100L << 30, 100L << 30, at, List.of());
    }

    static List<Message> ackEverything(Message request) {
        return switch (request) {
            case Message.Welcome _, Message.Incompatible _, Message.UnitReportAck _, Message.Shutdown _ -> List.of();
            default -> List.of(new Message.Ack(none()));
        };
    }

    static void waitFor(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        waitFor(condition, 10, what);
    }

    static void waitFor(java.util.function.BooleanSupplier condition, int seconds, String what) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Override
    public Promise<Completed> run(Command command) {
        return Causes.cause("run is not used by host links").promise();
    }

    /// When set, every spawn goes to this runner instead of a fake (a real ssh client, `SshdHostKeyIT`).
    volatile ProcessRunner through;

    @Override
    public Result<Spawned> spawn(Command command) {
        spawns.add(command.argv());
        if (through != null) {
            return through.spawn(command);
        }
        var process = new PipeProcess();
        if (command.argv().contains("install")) {
            Thread.ofVirtual().start(() -> install(process));
        } else {
            process.tapControllerWrites(this::sent);
            lastProcess = process;
            workers.add(new FakeWorker(process, message -> responder.apply(message)));
        }
        return Result.success(process);
    }

    private void sent(byte[] frame) {
        FrameCodec.decode(Arrays.copyOfRange(frame, FrameCodec.PREFIX_BYTES, frame.length), TemperJson.mapper())
                  .onSuccess(envelope -> onSend.accept(envelope.msg()));
    }

    private void install(PipeProcess process) {
        try {
            installedJars.add(process.workerIn().readAllBytes());
            process.workerErr().write(installStderr.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            installedJars.add(new byte[0]);
        }
        process.workerExits(installExit);
    }

    static Option<Integer> rc(int value) {
        return Option.some(value);
    }

    static TimeSpan seconds(int value) {
        return timeSpan(value).seconds();
    }
}
