package org.pragmatica.temper.worker.unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.manifest.Reach;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.plan.Planner;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.StartFailureKind;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.manifest.PomProbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// `StartUnit` of a hold unit and of a unit with a manifest (m1-design §1.2, §2.3): the effective-POM probe runs
/// before the marker; the marker is written before bb-hold is spawned (Q-11: "no marker" means "never spawned");
/// the reply is `UnitHolding`; a retried start never enqueues a second holder.
class UnitStarterHoldTest {
    private static final String NAME = "temper-42-gate";
    private static final List<String> SPAWN = List.of("setsid");
    private static final List<String> PROBE = List.of("docker", "run", "--rm", "--name", NAME + "-pom");

    @TempDir
    Path home;

    private HoldFixture fixture;
    private final UnitRef ref = Fixtures.ref(42, "gate");

    @BeforeEach
    void setUp() {
        fixture = new HoldFixture(home);
        fixture.holders.status.set(none());
        fixture.runner.on(List.of("docker", "inspect", "--type", "container", "-f", "{{.Id}}", NAME), 1, "", "Error: No such object: " + NAME)
                      .on(List.of("docker", "network", "inspect", "-f", "{{.Name}}", NAME), 1, "", "Error: No such network")
                      .on(List.of("docker", "network", "inspect", "-f", "{{range .IPAM.Config}}{{.Gateway}} {{end}}", NAME),
                          0,
                          "192.0.2.30 \n",
                          "")
                      .on(List.of("docker", "ps", "-a"), 0, "", "")
                      .on(SPAWN, _ -> observed("spawn marker=" + fixture.files.exists(ref, UnitFile.MARKER)))
                      .on(PROBE, _ -> probed(0));
    }

    private Result<Completed> observed(String event) {
        fixture.events.add(event);
        return Result.success(FakeRunner.completed(0, "", ""));
    }

    /// The probe container: records whether the marker exists yet, and writes the effective POM on rc 0.
    private Result<Completed> probed(int rc) {
        fixture.events.add("probe marker=" + fixture.files.exists(ref, UnitFile.MARKER));
        if (rc == 0) {
            ok(UnitFiles.storeAtomic(fixture.config.paths()
                                                   .effectivePomOutput(ref),
                                     "<projects/>"));
        }
        return Result.success(FakeRunner.completed(rc, "", rc == 0
                                                          ? ""
                                                          : "[ERROR] Non-resolvable parent POM"));
    }

    private UnitStarter starter() {
        var bbHold = BbHoldLauncher.bbHoldLauncher(fixture.runner,
                                                   fixture.files,
                                                   fixture.config.bbHoldPath(),
                                                   fixture.config.toolEnv(),
                                                   home.resolve("proc"),
                                                   _ -> true);

        return UnitStarter.unitStarter(fixture.config,
                                       fixture.files,
                                       fixture.docker,
                                       _ -> Promise.success("sha256:image"),
                                       UnitLauncher.routed(UnitLauncher.direct(fixture.docker), bbHold),
                                       fixture.facts,
                                       () -> Promise.success(UnitStarter.FreeSpace.freeSpace(1L << 40, 1L << 40)),
                                       TestcontainersReap.testcontainersReap(fixture.docker,
                                                                             fixture.files,
                                                                             TimeSpan.timeSpan(50).millis(),
                                                                             TimeSpan.timeSpan(50).millis(),
                                                                             TimeSpan.timeSpan(10).millis(),
                                                                             fixture.clock),
                                       fixture.registry,
                                       fixture.timer,
                                       fixture.watcher,
                                       PomProbe.pomProbe(fixture.docker, fixture.config.paths(), TimeSpan.timeSpan(10).seconds()),
                                       fixture.grants,
                                       fixture.holders,
                                       TemperJson.mapper(),
                                       fixture.clock);
    }

    private StartRequest request(boolean holds, boolean manifest) {
        return request(UnitKind.GATE_UNSHARDED, holds, manifest);
    }

    private StartRequest request(UnitKind kind, boolean holds, boolean manifest) {
        var base = Fixtures.request(42, "gate");

        return new StartRequest(base.ref(),
                                base.attempt(),
                                kind.name(),
                                List.of(List.of("mvn", "-B", "clean", "install", Planner.FORCED_REPO_LOCAL)),
                                Duration.ofHours(3),
                                base.memory(),
                                base.cpuShares(),
                                base.runnerVersion(),
                                none(),
                                none(),
                                holds
                                ? some(new HoldRequest("temper", 5))
                                : none(),
                                manifest
                                ? some(ManifestSpec.manifestSpec(List.of("mvn",
                                                                         "-B",
                                                                         ManifestSpec.EFFECTIVE_POM_GOAL,
                                                                         "-Doutput=${ws}/.home/.temper/effective-pom-gate.xml",
                                                                         Planner.FORCED_REPO_LOCAL),
                                                                 Reach.TEST,
                                                                 Map.of(),
                                                                 none(),
                                                                 none()))
                                : none(), InstanceId.DEFAULT);
    }

    /// The probe and spawn events, without the holder reads of the retried-start check.
    private List<String> launchEvents() {
        return fixture.events.stream()
                             .filter(event -> !event.startsWith("holder:"))
                             .toList();
    }

    private StartReply startReply(StartRequest request) {
        return ok(starter().start(request)
                           .await());
    }

    /// As the link does it: the reply, then the grant watch it carries as the reply's follow-up.
    private Message start(StartRequest request) {
        var reply = startReply(request);

        reply.watch()
             .onPresent(fixture.grants::watch);
        return reply.message();
    }

    @Test
    void aHoldUnit_writesTheMarkerBeforeSpawningBbHold_andRepliesUnitHolding() {
        var reply = start(request(true, false));

        assertThat(launchEvents()).containsExactly("spawn marker=true");
        assertThat(reply).isInstanceOfSatisfying(Message.UnitHolding.class,
                                                 holding -> assertThat(holding.markerEpochNs()).isEqualTo(ok(fixture.files.nanos(ref,
                                                                                                                                 UnitFile.MARKER)).or(0L)));
        assertThat(ok(fixture.files.text(ref, UnitFile.IMAGE))).isEqualTo(some("sha256:image"));
        assertThat(ok(fixture.files.text(ref, UnitFile.NETWORK))).isEqualTo(some("host"));
        assertThat(fixture.files.exists(ref, UnitFile.CONTAINER)).isFalse();
        assertThat(fixture.files.exists(ref, UnitFile.START)).isFalse();
        assertThat(fixture.files.exists(ref, UnitFile.BBHOLD_PID)).isTrue();
        assertThat(fixture.registry.isHolding(ref)).isTrue();
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "run", "-d"))).isEmpty();
    }

    /// A Gate unit runs on the host network (m1-design §14): no per-unit network is looked up or created, and the
    /// bb-hold argv runs it with `--network host` and Testcontainers told `localhost`.
    @Test
    void aGateUnit_runsOnTheHostNetwork_andCreatesNoNetwork() {
        start(request(true, false));

        var spawn = fixture.runner.callsStartingWith(SPAWN)
                                  .getFirst();

        assertThat(spawn.get(spawn.indexOf("--network") + 1)).isEqualTo("host");
        assertThat(spawn).contains("TESTCONTAINERS_HOST_OVERRIDE=localhost");
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "network"))).isEmpty();
    }

    /// Only a Gate unit takes the host network (m1-design §14): a held unit of any other kind (here a ModuleTest)
    /// keeps its bridged per-unit network as before. The network is created (labelled with the job and unit) before
    /// the spawn, the bb-hold argv runs the unit in it, Testcontainers is told its gateway, and `network` records it.
    @Test
    void aHeldModuleTestUnit_runsOnItsOwnBridgedNetwork_createdBeforeTheSpawn() {
        start(request(UnitKind.MODULE_TEST, true, false));

        var spawn = fixture.runner.callsStartingWith(SPAWN)
                                  .getFirst();
        var create = List.of("docker", "network", "create", "--label", "temper.job=42", "--label", "temper.unit=gate", NAME);

        assertThat(spawn.get(spawn.indexOf("--network") + 1)).isEqualTo(NAME);
        assertThat(spawn).contains("TESTCONTAINERS_HOST_OVERRIDE=192.0.2.30")
                         .doesNotContain("host", "TESTCONTAINERS_HOST_OVERRIDE=localhost");
        assertThat(fixture.runner.callsStartingWith(create)).hasSize(1);
        assertThat(fixture.runner.indexOf(create)).isLessThan(fixture.runner.indexOf(SPAWN));
        assertThat(ok(fixture.files.text(ref, UnitFile.NETWORK))).isEqualTo(some(NAME));
    }

    /// The held Gate counterpart of the ModuleTest case above, asserted the same way: `--network host`, Testcontainers
    /// told `localhost`, no network created, `network` = `host`.
    @Test
    void aHeldGateUnit_runsOnTheHostNetwork_unlikeAHeldModuleTestUnit() {
        start(request(UnitKind.GATE_UNSHARDED, true, false));

        var spawn = fixture.runner.callsStartingWith(SPAWN)
                                  .getFirst();

        assertThat(spawn.get(spawn.indexOf("--network") + 1)).isEqualTo("host");
        assertThat(spawn).contains("TESTCONTAINERS_HOST_OVERRIDE=localhost")
                         .doesNotContain("TESTCONTAINERS_HOST_OVERRIDE=192.0.2.30");
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "network", "create"))).isEmpty();
        assertThat(ok(fixture.files.text(ref, UnitFile.NETWORK))).isEqualTo(some("host"));
    }

    /// The starter never starts the grant watch itself: the reply carries it, and the link starts it once
    /// `UnitHolding` is written, so the watcher's `UnitStarted` can never overtake that reply.
    @Test
    void aHoldUnit_leavesTheGrantWatchToTheReplysFollowUp() {
        var reply = startReply(request(true, false));

        assertThat(reply.message()).isInstanceOf(Message.UnitHolding.class);
        assertThat(reply.watch()
                        .map(GrantWatcher.Watch::ref)).isEqualTo(some(ref));
        assertThat(fixture.registry.isHolding(ref)).isFalse();
        assertThat(fixture.timers.last()
                                 .isPresent()).isFalse();

        fixture.grants.watch(reply.watch()
                                  .or(() -> GrantWatcher.Watch.watch(ref, 1, Duration.ZERO)));

        assertThat(fixture.registry.isHolding(ref)).isTrue();
        assertThat(fixture.timers.last()
                                 .isPresent()).isTrue();
    }

    @Test
    void aRetriedStart_whileHolding_repliesUnitHoldingAgain_withoutASecondHolder() {
        var first = start(request(true, false));
        var second = start(request(true, false));

        assertThat(second).isEqualTo(first);
        assertThat(fixture.runner.callsStartingWith(SPAWN)).hasSize(1);
    }

    /// After a worker restart nothing is holding in this worker, but the identified holder still waits: it is
    /// watched again, never enqueued twice.
    @Test
    void aRetriedStart_afterARestart_withALiveHolder_isWatchedAgain_notEnqueuedAgain() {
        var first = start(request(true, false));

        fixture.registry.forget(ref, 1);
        fixture.holders.is(HolderStatus.WAITING);

        assertThat(start(request(true, false))).isEqualTo(first);
        assertThat(fixture.runner.callsStartingWith(SPAWN)).hasSize(1);
        assertThat(fixture.registry.isHolding(ref)).isTrue();
    }

    /// The probe runs before the marker (outside any hold); its XML lands in the unit's manifest directory, and
    /// the spec is stored for the worker that will collect the exit.
    @Test
    void aManifestUnit_runsTheProbeBeforeTheMarker_andKeepsItsOutput() {
        start(request(true, true));

        assertThat(launchEvents()).containsExactly("probe marker=false", "spawn marker=true");
        assertThat(fixture.config.paths()
                                 .effectivePom(ref)).hasContent("<projects/>");
        assertThat(fixture.files.exists(ref, UnitFile.MANIFEST_SPEC)).isTrue();
        assertThat(fixture.runner.callsStartingWith(PROBE)
                                 .getFirst()).contains("--label",
                                                       "temper.aux=pom",
                                                       "-Doutput=" + fixture.config.paths()
                                                                                    .effectivePomOutput(ref),
                                                       "-Dmaven.repo.local=" + fixture.config.paths()
                                                                                            .workspaceRepo(ref.job()));
    }

    /// Q-M1-7: a failed effective POM ends the unit `ERROR(manifest: …)` and the build never runs (no marker, no
    /// bb-hold); the probe container is removed.
    @Test
    void aFailedProbe_isAManifestError_andNothingRuns() {
        fixture.runner.on(PROBE, _ -> probed(1));

        var reply = start(request(true, true));

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                 failed -> assertThat(failed).extracting(Message.UnitStartFailed::verdictKind,
                                                                                         Message.UnitStartFailed::reason)
                                                                             .containsExactly(StartFailureKind.ERROR, "manifest: effective-pom rc=1"));
        assertThat(fixture.files.exists(ref, UnitFile.MARKER)).isFalse();
        assertThat(fixture.runner.callsStartingWith(SPAWN)).isEmpty();
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "rm", "-f", NAME + "-pom"))).hasSizeGreaterThanOrEqualTo(2);
    }

    /// A probe that exits 0 without writing its XML did not run, even when an earlier attempt's output is still
    /// there: the stale file is removed before the probe, so it is never taken for this attempt's.
    @Test
    void aProbeThatLeavesNoOutput_isAManifestError_evenWithAStaleOutput() throws Exception {
        var stale = fixture.config.paths()
                                  .effectivePomOutput(ref);

        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "<projects><stale/></projects>");
        fixture.runner.on(PROBE, _ -> observed("probe"));

        var reply = start(request(true, true));

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                 failed -> assertThat(failed).extracting(Message.UnitStartFailed::verdictKind,
                                                                                         Message.UnitStartFailed::reason)
                                                                             .containsExactly(StartFailureKind.ERROR,
                                                                                              "manifest: effective-pom did not run: no output"));
        assertThat(fixture.config.paths()
                                 .effectivePom(ref)).doesNotExist();
        assertThat(fixture.runner.callsStartingWith(SPAWN)).isEmpty();
    }

    /// A cancel that lands after the start began (here, while the probe runs) is seen by the re-check just before
    /// the launch: `CANCELLED`, and bb-hold is never spawned.
    @Test
    void aCancelDuringThePrepare_isHonouredBeforeTheLaunch() {
        fixture.runner.on(PROBE, _ -> cancelledWhileProbing());

        var reply = start(request(true, true));

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                 failed -> assertThat(failed.verdictKind()).isEqualTo(StartFailureKind.CANCELLED));
        assertThat(fixture.runner.callsStartingWith(SPAWN)).isEmpty();
    }

    /// A start that fails after bb-hold was spawned signals that holder (its ticket must not wait for a unit that
    /// already failed), then removes the container and the network.
    @Test
    void aFailureAfterTheSpawn_signalsTheHolder_andCleansUp() throws Exception {
        Files.createDirectories(fixture.files.path(ref, UnitFile.IMAGE)
                                       .resolve("occupied"));

        var reply = start(request(true, false));

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                 failed -> assertThat(failed.reason()).startsWith("infra: "));
        assertThat(launchEvents()).containsExactly("spawn marker=true", "terminate");
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "rm", "-f", NAME))).isNotEmpty();
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "network", "rm", NAME))).hasSize(1);
        assertThat(fixture.registry.isHolding(ref)).isFalse();
    }

    /// Each compensation runs on its own: a holder that cannot be signalled does not leave the container or the
    /// network behind.
    @Test
    void aCompensationThatFails_doesNotSkipTheOthers() throws Exception {
        Files.createDirectories(fixture.files.path(ref, UnitFile.IMAGE)
                                       .resolve("occupied"));
        fixture.holders.terminateFails.set(true);

        assertThat(start(request(true, false))).isInstanceOf(Message.UnitStartFailed.class);
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "rm", "-f", NAME))).isNotEmpty();
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "rm", "-f", NAME + "-pom"))).isNotEmpty();
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "network", "rm", NAME))).hasSize(1);
    }

    private Result<Completed> cancelledWhileProbing() {
        fixture.registry.cancel(ref, 1);
        return probed(0);
    }

    /// A retried start that adopts the running container of a granted hold unit reports its bb-hold ticket.
    @Test
    void anAdoptedHoldUnit_keepsItsTicket() {
        fixture.runner.on(List.of("docker", "inspect", "--type", "container", "-f", "{{.Id}}", NAME), 0, "cid\n", "")
                      .on(List.of("docker", "inspect", "--type", "container", "-f", "{{index .Config.Labels \"temper.attempt\"}}", NAME), 0, "1\n", "");
        fixture.registry.startWatching(ref, 1, "cid");
        ok(fixture.files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(fixture.files.store(ref, UnitFile.BBHOLD_TICKET, "1760-9\n"));

        assertThat(start(request(true, false))).isInstanceOfSatisfying(Message.UnitStarted.class,
                                                                       started -> assertThat(started.ticket()).isEqualTo(some("1760-9")));
    }

    /// The direct path is unchanged: no probe, no hold, `UnitStarted`.
    @Test
    void aDirectUnit_withoutAManifest_startsAsBefore() throws Exception {
        fixture.runner.on(List.of("docker", "run", "-d"), 0, "cid\n", "");
        fixture.registry.startWatching(ref, 1, "cid");
        Files.createDirectories(home.resolve("proc"));

        assertThat(start(request(false, false))).isInstanceOfSatisfying(Message.UnitStarted.class,
                                                                        started -> assertThat(started.ticket()).isEqualTo(none()));
        assertThat(launchEvents()).isEmpty();
    }
}
