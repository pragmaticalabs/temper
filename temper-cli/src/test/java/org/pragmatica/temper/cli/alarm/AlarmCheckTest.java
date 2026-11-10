package org.pragmatica.temper.cli.alarm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.TestSupport;
import org.pragmatica.temper.cli.config.AlarmConfig;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.cli.transport.HttpCall;
import org.pragmatica.temper.cli.transport.HttpReply;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;

/// The evaluation over a scripted transport: which conditions get a state when the controller answers, fails to, or
/// answers outside the contract.
class AlarmCheckTest {
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final String HOSTS = "[{\"name\":\"bigboy\",\"state\":\"UP\",\"cap\":5,\"reactors\":0,\"foreign\":0,\"active\":0,\"facts\":null,\"link\":\"UP\",\"lost\":null,\"sharedCpu\":false,\"retireAt\":null,\"retired\":false}]";
    private static final String FRESH = "{\"latestDump\":{\"kind\":\"pg_dump\",\"startedAt\":\"2026-10-13T03:30:00Z\","
                                        + "\"endedAt\":\"2026-10-13T03:31:00Z\",\"ok\":true,\"detail\":null},"
                                        + "\"lastGoodDumpEndedAt\":\"2026-10-13T03:31:00Z\"}";

    @TempDir
    Path disk;

    private final List<String> asked = new CopyOnWriteArrayList<>();

    /// No current alarm on the one host.
    private static final String NO_ALARMS = "{\"v\":1,\"asOf\":\"2026-10-13T10:00:00Z\",\"hosts\":[{\"host\":\"bigboy\",\"status\":\"ok\","
                                            + "\"takenAt\":null,\"hostAt\":null,\"skewS\":null,\"retireAt\":null,\"alarms\":[]}]}";

    /// `replies`, and no current alarms unless `/fleet/alarms` is among them.
    private Controller answering(Map<String, HttpReply> replies) {
        var all = new java.util.HashMap<>(replies);

        all.putIfAbsent("/fleet/alarms", HttpReply.httpReply(200, NO_ALARMS));
        return Controller.controller(call -> answer(call, all), TemperJson.mapper());
    }

    private Promise<HttpReply> answer(HttpCall call, Map<String, HttpReply> replies) {
        asked.add(call.path());
        return Promise.success(replies.getOrDefault(call.path(), HttpReply.httpReply(404, "{\"error\":\"no route\"}")));
    }

    private AlarmConfig config(long minFreeBytes) {
        return config(minFreeBytes, Option.none());
    }

    private AlarmConfig config(long minFreeBytes, Option<AlarmConfig.Skip> skip) {
        return new AlarmConfig(new AlarmConfig.Sink.Stdout(), "bigboy", List.of(disk), minFreeBytes, Duration.ofHours(26), dumps(), skip);
    }

    /// A dump directory with one verified dump, an hour old.
    private Path dumps() {
        var dir = disk.resolve("pgdump");
        Result.lift(Causes::fromThrowable, () -> Files.createDirectories(dir)).unwrap();
        var dump = dir.resolve("temper-20261013T090000Z.dump");
        Result.lift(Causes::fromThrowable, () -> Files.writeString(dump, "PGDMP")).unwrap();
        Result.lift(Causes::fromThrowable, () -> Files.setLastModifiedTime(dump, FileTime.from(NOW.minusSeconds(3600)))).unwrap();
        return dir;
    }

    private final TestSupport.Time time = TestSupport.time();

    private Evaluation evaluated(Controller api) {
        return AlarmCheck.evaluate(api, config(1), time.clock(), time.pacer(), "s3cret-token").await().unwrap();
    }

    /// `/maintenance` answers FRESH; `/hosts` answers `hosts` in turn (the last one repeats).
    private Controller hostsAnswering(HttpReply... hosts) {
        var calls = new AtomicInteger();

        return Controller.controller(call -> call.path().equals("/hosts")
                                             ? Promise.success(hosts[Math.min(calls.getAndIncrement(), hosts.length - 1)])
                                             : Promise.success(HttpReply.httpReply(200,
                                                                                   call.path().equals("/fleet/alarms")
                                                                                   ? NO_ALARMS
                                                                                   : FRESH)),
                                     TemperJson.mapper());
    }

    private static List<AlarmCode> codes(Evaluation evaluation) {
        return evaluation.conditions().stream().map(Condition::code).toList();
    }

    @Test
    void answered_decidesEveryCondition_allClear() {
        var evaluation = evaluated(answering(Map.of("/maintenance", HttpReply.httpReply(200, FRESH),
                                                    "/hosts", HttpReply.httpReply(200, HOSTS))));

        assertThat(evaluation.conditions()).containsExactly(Condition.clear(AlarmCode.CONTROLLER_DOWN),
                                                            Condition.clear(AlarmCode.DUMP_FAILED),
                                                            Condition.clear(AlarmCode.DUMP_STALE),
                                                            Condition.clear(AlarmCode.WORKER_LOST),
                                                            Condition.clear(AlarmCode.HOST_ALARMS),
                                                            Condition.clear(AlarmCode.DISK_LOW),
                                                            Condition.clear(AlarmCode.DUMP_FILE_STALE),
                                                            Condition.clear(AlarmCode.CHECK_BLIND));
        assertThat(evaluation.undetermined()).isEmpty();
        assertThat(evaluation.firing()).isFalse();
        assertThat(time.pauses()).isEmpty();
        assertThat(asked).containsExactlyInAnyOrder("/maintenance", "/hosts", "/fleet/alarms");
    }

    /// Controller down: only `controller-down` is decided for it; the dump and worker conditions are neither clear nor
    /// firing (so a sink never closes their issues on a blind check), and the local disk is still measured.
    @Test
    void unreachable_firesControllerDown_andSkipsTheOtherApiConditions() {
        Controller down = Controller.controller(_ -> CliError.Unreachable.FACTORY.apply("http://bigboy:7070/api/v1",
                                                                                        Causes.cause("Connection refused"))
                                                                                 .promise(),
                                                TemperJson.mapper());

        var evaluation = evaluated(down);

        assertThat(codes(evaluation)).containsExactly(AlarmCode.CONTROLLER_DOWN, AlarmCode.DISK_LOW, AlarmCode.DUMP_FILE_STALE, AlarmCode.CHECK_BLIND);
        assertThat(evaluation.conditions().getFirst()).isInstanceOf(Condition.Firing.class);
        assertThat(evaluation.conditions().getLast()).isEqualTo(Condition.clear(AlarmCode.CHECK_BLIND));
        assertThat(((Condition.Firing) evaluation.conditions().getFirst()).detail()).contains("Controller unreachable at http://bigboy:7070/api/v1",
                                                                                                 "Connection refused");
        assertThat(evaluation.undetermined()).isEmpty();
        assertThat(evaluation.firing()).isTrue();
    }

    /// Down at all three reads, 15 s apart: `controller-down` fires.
    @Test
    void serverError_atEveryRead_isControllerDown() {
        var evaluation = evaluated(hostsAnswering(HttpReply.httpReply(503, "{\"error\":\"starting\"}")));

        assertThat(codes(evaluation)).containsExactly(AlarmCode.CONTROLLER_DOWN, AlarmCode.DISK_LOW, AlarmCode.DUMP_FILE_STALE, AlarmCode.CHECK_BLIND);
        assertThat(evaluation.conditions().getFirst()).isInstanceOf(Condition.Firing.class);
        assertThat(time.pauses()).containsExactly(Duration.ofSeconds(15), Duration.ofSeconds(15));
    }

    /// A restart: down at the first read, up at the second. No fire, and every condition is decided from the answer.
    @Test
    void downThenUp_withinTheRetries_doesNotFire() {
        var evaluation = evaluated(hostsAnswering(HttpReply.httpReply(503, "{\"error\":\"starting\"}"),
                                                  HttpReply.httpReply(200, HOSTS)));

        assertThat(evaluation.firing()).isFalse();
        assertThat(codes(evaluation)).containsExactly(AlarmCode.CONTROLLER_DOWN,
                                                      AlarmCode.DUMP_FAILED,
                                                      AlarmCode.DUMP_STALE,
                                                      AlarmCode.WORKER_LOST,
                                                      AlarmCode.HOST_ALARMS,
                                                      AlarmCode.DISK_LOW,
                                                      AlarmCode.DUMP_FILE_STALE,
                                                      AlarmCode.CHECK_BLIND);
        assertThat(time.pauses()).containsExactly(Duration.ofSeconds(15));
    }

    @Test
    void downTwiceThenUp_doesNotFire_downThreeTimes_fires() {
        var twice = HttpReply.httpReply(503, "{\"error\":\"starting\"}");

        assertThat(evaluated(hostsAnswering(twice, twice, HttpReply.httpReply(200, HOSTS))).firing()).isFalse();
        assertThat(evaluated(hostsAnswering(twice, twice, twice)).fires(AlarmCode.CONTROLLER_DOWN)).isTrue();
    }

    /// A controller without the `maintenance` route (404) answered: not down, but the API conditions are undetermined.
    @Test
    void answerOutsideTheContract_isNotDown_butLeavesTheApiConditionsUndetermined() {
        var evaluation = evaluated(answering(Map.of("/hosts", HttpReply.httpReply(200, HOSTS))));

        assertThat(evaluation.conditions()).hasSize(4);
        assertThat(evaluation.conditions().subList(0, 2)).containsExactly(Condition.clear(AlarmCode.CONTROLLER_DOWN),
                                                                          Condition.clear(AlarmCode.DISK_LOW));
        assertThat(evaluation.undetermined()).hasSize(1);
        assertThat(evaluation.undetermined().getFirst()).isInstanceOf(CliError.NotFound.class);
        assertThat(evaluation.conditions().getLast())
            .isEqualTo(Condition.firing(AlarmCode.CHECK_BLIND, "not evaluated: Not found: /maintenance"));
        assertThat(time.pauses()).isEmpty();
    }

    /// A rotated controller token (401): the alarm is blind, and says so through `check-blind`.
    @Test
    void unauthorized_firesCheckBlind() {
        var evaluation = evaluated(answering(Map.of("/maintenance", HttpReply.httpReply(401, "{\"error\":\"unauthorized\"}"),
                                                    "/hosts", HttpReply.httpReply(401, "{\"error\":\"unauthorized\"}"))));

        assertThat(evaluation.fires(AlarmCode.CHECK_BLIND)).isTrue();
        assertThat(evaluation.fires(AlarmCode.CONTROLLER_DOWN)).isFalse();
        assertThat(((Condition.Firing) evaluation.conditions().getLast()).detail())
            .startsWith("not evaluated: Controller rejected the bearer token (unauthorized)");
    }

    @Test
    void undecodablePayload_firesCheckBlind() {
        var evaluation = evaluated(answering(Map.of("/maintenance", HttpReply.httpReply(200, "{\"latest\":1}"),
                                                    "/hosts", HttpReply.httpReply(200, HOSTS))));

        assertThat(evaluation.fires(AlarmCode.CHECK_BLIND)).isTrue();
        assertThat(evaluation.undetermined().getFirst()).isInstanceOf(CliError.BadPayload.class);
    }

    /// The detail never carries the controller token, should a failure ever quote it.
    @Test
    void checkBlindDetail_redactsTheSecret() {
        var blind = Evaluation.undetermined(CliError.Usage.FACTORY.apply("saw s3cret-token in a reply")).withBlindness("s3cret-token");

        assertThat(blind.conditions())
            .containsExactly(Condition.firing(AlarmCode.CHECK_BLIND, "not evaluated: saw [redacted] in a reply"));
    }

    /// A controller that accepts the connection but never answers: each attempt ends at the read deadline, which counts
    /// as down, so `controller-down` fires after the three attempts (not `check-blind`).
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void neverAnswering_hitsTheReadDeadline_andIsControllerDown() {
        Controller silent = Controller.controller(_ -> Promise.<HttpReply> promise(), TemperJson.mapper());

        var evaluation = AlarmCheck.evaluate(silent, config(1), time.clock(), time.pacer(), "s", Duration.ofMillis(100))
                                   .await()
                                   .unwrap();

        assertThat(evaluation.fires(AlarmCode.CONTROLLER_DOWN)).isTrue();
        assertThat(evaluation.fires(AlarmCode.CHECK_BLIND)).isFalse();
        assertThat(time.pauses()).containsExactly(Duration.ofSeconds(15), Duration.ofSeconds(15));
        assertThat(AlarmCheck.READ_DEADLINE).isEqualTo(Duration.ofSeconds(20));
        assertThat(AlarmCheck.WORST_CASE).isEqualTo(Duration.ofSeconds(90));
    }

    /// `dump-stale` is judged at the time of the answer that is used: the good dump is fresh at the first (down) read
    /// and 5 s past `stale-after` at the second, 15 s later.
    @Test
    void dumpStale_isJudgedAtTheAnswersTime_afterTheRetry() {
        var ended = NOW.minus(Duration.ofHours(26)).plusSeconds(10);
        var view = "{\"latestDump\":null,\"lastGoodDumpEndedAt\":\"" + ended + "\"}";
        var calls = new AtomicInteger();
        Controller restarting = Controller.controller(call -> call.path().equals("/hosts") && calls.getAndIncrement() == 0
                                                              ? Promise.success(HttpReply.httpReply(503, "{}"))
                                                              : Promise.success(call.path().equals("/hosts")
                                                                                ? HttpReply.httpReply(200, HOSTS)
                                                                                : HttpReply.httpReply(200, view)),
                                                      TemperJson.mapper());

        var evaluation = evaluated(restarting);

        assertThat(evaluation.fires(AlarmCode.DUMP_STALE)).isTrue();
        assertThat(evaluation.fires(AlarmCode.CONTROLLER_DOWN)).isFalse();
    }

    private static final AlarmConfig.Skip DUMPS_SKIPPED = AlarmConfig.Skip.skip(List.of(AlarmCode.DUMP_FAILED, AlarmCode.DUMP_STALE),
                                                                                LocalDate.parse("2026-10-20"));

    /// Both dump codes skipped: `/maintenance` is never asked, so a controller without the route is not blindness.
    @Test
    void skippedDumpCodes_doNotQueryMaintenance() {
        var evaluation = AlarmCheck.evaluate(answering(Map.of("/hosts", HttpReply.httpReply(200, HOSTS))),
                                             config(1, Option.some(DUMPS_SKIPPED)),
                                             time.clock(),
                                             time.pacer(), "s3cret-token")
                                   .await()
                                   .unwrap();

        assertThat(asked).containsExactlyInAnyOrder("/hosts", "/fleet/alarms");
        assertThat(codes(evaluation)).containsExactly(AlarmCode.CONTROLLER_DOWN,
                                                      AlarmCode.WORKER_LOST,
                                                      AlarmCode.HOST_ALARMS,
                                                      AlarmCode.DISK_LOW,
                                                      AlarmCode.DUMP_FILE_STALE,
                                                      AlarmCode.CHECK_BLIND);
        assertThat(evaluation.firing()).isFalse();
    }

    /// One dump code skipped: `/maintenance` is read for the other, and only the skipped one is left out.
    @Test
    void oneSkippedDumpCode_stillReadsMaintenance_forTheOther() {
        var skip = AlarmConfig.Skip.skip(List.of(AlarmCode.DUMP_FAILED), LocalDate.parse("2026-10-20"));

        var evaluation = AlarmCheck.evaluate(answering(Map.of("/maintenance", HttpReply.httpReply(200, FRESH),
                                                              "/hosts", HttpReply.httpReply(200, HOSTS))),
                                             config(1, Option.some(skip)),
                                             time.clock(),
                                             time.pacer(), "s3cret-token")
                                   .await()
                                   .unwrap();

        assertThat(asked).contains("/maintenance");
        assertThat(codes(evaluation)).contains(AlarmCode.DUMP_STALE).doesNotContain(AlarmCode.DUMP_FAILED);
    }

    /// Past `skip-until` (the fake day is 2026-10-13), the codes are evaluated again and `check-blind` names the expiry.
    @Test
    void expiredSkip_evaluatesAgain_andFiresCheckBlind() {
        var expired = AlarmConfig.Skip.skip(List.of(AlarmCode.DUMP_FAILED, AlarmCode.DUMP_STALE), LocalDate.parse("2026-10-12"));

        var evaluation = AlarmCheck.evaluate(answering(Map.of("/maintenance", HttpReply.httpReply(200, FRESH),
                                                              "/hosts", HttpReply.httpReply(200, HOSTS))),
                                             config(1, Option.some(expired)),
                                             time.clock(),
                                             time.pacer(), "s3cret-token")
                                   .await()
                                   .unwrap();

        assertThat(asked).contains("/maintenance");
        assertThat(codes(evaluation)).contains(AlarmCode.DUMP_FAILED, AlarmCode.DUMP_STALE);
        assertThat(evaluation.conditions().getLast())
            .isEqualTo(Condition.firing(AlarmCode.CHECK_BLIND,
                                        "not evaluated: skip of dump-failed, dump-stale expired on 2026-10-12 "
                                        + "(remove skip and skip-until from [alarm])"));
    }

    /// The last day of a skip still holds it.
    @Test
    void skip_holdsThroughItsLastDay() {
        var lastDay = AlarmConfig.Skip.skip(List.of(AlarmCode.DUMP_FAILED, AlarmCode.DUMP_STALE), LocalDate.parse("2026-10-13"));

        var evaluation = AlarmCheck.evaluate(answering(Map.of("/hosts", HttpReply.httpReply(200, HOSTS))),
                                             config(1, Option.some(lastDay)),
                                             time.clock(),
                                             time.pacer(), "s3cret-token")
                                   .await()
                                   .unwrap();

        assertThat(asked).containsExactlyInAnyOrder("/hosts", "/fleet/alarms");
        assertThat(evaluation.fires(AlarmCode.CHECK_BLIND)).isFalse();
    }

    private Path dumpDir(String name) throws IOException {
        return Files.createDirectories(disk.resolve(name));
    }

    private static Path dump(Path dir, String name, Instant modified) throws IOException {
        var file = Files.writeString(dir.resolve(name), "PGDMP");
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Test
    void dumpFile_freshDump_isClear() throws IOException {
        var dir = dumpDir("fresh");
        dump(dir, "temper-20261012T033000Z.dump", NOW.minus(Duration.ofHours(30)));
        dump(dir, "temper-20261013T033000Z.dump", NOW.minus(Duration.ofHours(7)));

        assertThat(AlarmCheck.dumpFileConditions(dir, Duration.ofHours(26), NOW).conditions())
            .containsExactly(Condition.clear(AlarmCode.DUMP_FILE_STALE));
    }

    @Test
    void dumpFile_newestOlderThanStaleAfter_fires() throws IOException {
        var dir = dumpDir("stale");
        var newest = NOW.minus(Duration.ofHours(27));
        dump(dir, "temper-20261012T070000Z.dump", newest);

        assertThat(AlarmCheck.dumpFileConditions(dir, Duration.ofHours(26), NOW).conditions())
            .containsExactly(Condition.firing(AlarmCode.DUMP_FILE_STALE,
                                              "the newest temper-*.dump in " + dir + " is from " + newest + ", more than PT26H ago"));
    }

    /// The newest dump is chosen by modification time, not by name: here the later name is the older file.
    @Test
    void dumpFile_newestByMtime_notByName() throws IOException {
        var dir = dumpDir("order");
        dump(dir, "temper-20261013T033000Z.dump", NOW.minus(Duration.ofHours(40)));
        dump(dir, "temper-20261001T033000Z.dump", NOW.minus(Duration.ofHours(2)));

        assertThat(AlarmCheck.dumpFileConditions(dir, Duration.ofHours(26), NOW).conditions())
            .containsExactly(Condition.clear(AlarmCode.DUMP_FILE_STALE));

        var stale = dumpDir("order-stale");
        var older = NOW.minus(Duration.ofHours(40));
        dump(stale, "temper-20261001T033000Z.dump", NOW.minus(Duration.ofHours(30)));
        dump(stale, "temper-20261013T033000Z.dump", older);

        assertThat(AlarmCheck.dumpFileConditions(stale, Duration.ofHours(26), NOW).conditions())
            .containsExactly(Condition.firing(AlarmCode.DUMP_FILE_STALE,
                                              "the newest temper-*.dump in " + stale + " is from " + NOW.minus(Duration.ofHours(30))
                                              + ", more than PT26H ago"));
    }

    @Test
    void dumpFile_emptyDirectory_fires() throws IOException {
        var dir = dumpDir("empty");

        assertThat(AlarmCheck.dumpFileConditions(dir, Duration.ofHours(26), NOW).conditions())
            .containsExactly(Condition.firing(AlarmCode.DUMP_FILE_STALE, "no temper-*.dump in " + dir));
    }

    @Test
    void dumpFile_missingDirectory_isCheckBlind() {
        var evaluation = AlarmCheck.dumpFileConditions(disk.resolve("absent"), Duration.ofHours(26), NOW).withBlindness("");

        assertThat(evaluation.conditions()).hasSize(1);
        assertThat(evaluation.fires(AlarmCode.CHECK_BLIND)).isTrue();
        assertThat(((Condition.Firing) evaluation.conditions().getFirst()).detail()).contains("Cannot read " + disk.resolve("absent"));
    }

    /// Only `temper-*.dump` regular files count: a newer `.partial`, another name, or a directory so named do not.
    @Test
    void dumpFile_unrelatedNewerFiles_doNotCount() throws IOException {
        var dir = dumpDir("mixed");
        dump(dir, "temper-20261011T033000Z.dump", NOW.minus(Duration.ofHours(50)));
        dump(dir, "temper-20261013T033000Z.dump.partial", NOW.minus(Duration.ofHours(1)));
        dump(dir, "other.dump", NOW.minus(Duration.ofHours(1)));
        dump(dir, "xtemper-1.dump", NOW.minus(Duration.ofHours(1)));
        Files.setLastModifiedTime(Files.createDirectories(dir.resolve("temper-20261013T040000Z.dump")), FileTime.from(NOW.minusSeconds(60)));

        assertThat(AlarmCheck.dumpFileConditions(dir, Duration.ofHours(26), NOW).fires(AlarmCode.DUMP_FILE_STALE)).isTrue();
    }

    /// `disk-low` skipped: its paths are not measured, so an unmeasurable one is not blindness.
    @Test
    void skippedDiskLow_isNotMeasured() {
        var skip = AlarmConfig.Skip.skip(List.of(AlarmCode.DISK_LOW), LocalDate.parse("2026-10-14"));
        var config = new AlarmConfig(new AlarmConfig.Sink.Stdout(), "bigboy", List.of(disk.resolve("gone")), 1, Duration.ofHours(26),
                                     dumps(), Option.some(skip));

        var evaluation = AlarmCheck.evaluate(answering(Map.of("/maintenance", HttpReply.httpReply(200, FRESH),
                                                              "/hosts", HttpReply.httpReply(200, HOSTS))),
                                             config, time.clock(), time.pacer(), "s3cret-token")
                                   .await()
                                   .unwrap();

        assertThat(evaluation.undetermined()).isEmpty();
        assertThat(codes(evaluation)).doesNotContain(AlarmCode.DISK_LOW);
    }

    /// The detail is cut at 2000 characters, with an ellipsis.
    @Test
    void checkBlindDetail_isCutAt2000Characters() {
        var blind = Evaluation.undetermined(CliError.Usage.FACTORY.apply("x".repeat(5000))).withBlindness("");
        var detail = ((Condition.Firing) blind.conditions().getFirst()).detail();

        assertThat(detail).hasSize(2001).startsWith("not evaluated: xxx").endsWith("x…");
        var short1 = Evaluation.undetermined(CliError.Usage.FACTORY.apply("x".repeat(1985))).withBlindness("");

        assertThat(((Condition.Firing) short1.conditions().getFirst()).detail()).hasSize(2000).doesNotContain("…");
    }

    @Test
    void unmeasurableDisk_firesCheckBlind() {
        var evaluation = AlarmCheck.diskConditions(List.of(disk.resolve("missing")), 1).withBlindness("x");

        assertThat(evaluation.conditions()).hasSize(1);
        assertThat(((Condition.Firing) evaluation.conditions().getFirst()).detail()).startsWith("not evaluated: Cannot read ");
    }

    @Test
    void failedAndStaleDump_andALostWorker_allFire() {
        var failed = "{\"latestDump\":{\"kind\":\"pg_dump\",\"startedAt\":\"2026-10-13T03:30:00Z\",\"endedAt\":\"2026-10-13T03:31:00Z\","
                     + "\"ok\":false,\"detail\":\"pg_dump exited 1\"},\"lastGoodDumpEndedAt\":\"2026-10-11T03:31:00Z\"}";
        var lost = "[{\"name\":\"bigboy\",\"state\":\"DOWN\",\"cap\":5,\"reactors\":0,\"foreign\":0,\"active\":0,\"facts\":null,\"link\":\"UP\",\"lost\":null,\"sharedCpu\":false,\"retireAt\":null,\"retired\":false}]";

        var evaluation = evaluated(answering(Map.of("/maintenance", HttpReply.httpReply(200, failed),
                                                    "/hosts", HttpReply.httpReply(200, lost))));

        assertThat(evaluation.conditions()).filteredOn(Condition.Firing.class::isInstance)
                                           .extracting(Condition::code)
                                           .containsExactly(AlarmCode.DUMP_FAILED, AlarmCode.DUMP_STALE, AlarmCode.WORKER_LOST);
    }

    private static final String TWO_HOSTS_ALARMED = "{\"v\":1,\"asOf\":\"2026-10-13T10:00:00Z\",\"hosts\":["
        + "{\"host\":\"bigboy\",\"status\":\"ok\",\"takenAt\":null,\"hostAt\":null,\"skewS\":null,\"retireAt\":null,\"alarms\":["
        + "{\"kind\":\"queue-wait\",\"ticket\":\"T-7\",\"ageMin\":65,\"limitMin\":60,\"days\":null,\"skewS\":null,\"firedAt\":\"2026-10-13T09:55:00Z\"}]},"
        + "{\"host\":\"cloudbb-1\",\"status\":\"ok\",\"takenAt\":null,\"hostAt\":null,\"skewS\":null,\"retireAt\":null,\"alarms\":["
        + "{\"kind\":\"host-retiring\",\"ticket\":null,\"ageMin\":null,\"limitMin\":null,\"days\":3,\"skewS\":null,\"firedAt\":\"2026-10-13T09:56:00Z\"}]}]}";

    private Evaluation withAlarms(HttpReply alarms) {
        return evaluated(answering(Map.of("/maintenance", HttpReply.httpReply(200, FRESH),
                                          "/hosts", HttpReply.httpReply(200, HOSTS),
                                          "/fleet/alarms", alarms)));
    }

    /// S1.22: two current alarms on two hosts: firing, both lines in reply order, then the route link.
    /// Mutation: firing on the first host only (the second line goes missing).
    @Test
    void s1_22_hostAlarms_twoAlarmsOnTwoHosts_firesWithBothLines() {
        var evaluation = withAlarms(HttpReply.httpReply(200, TWO_HOSTS_ALARMED));

        assertThat(evaluation.conditions()).contains(Condition.firing(AlarmCode.HOST_ALARMS,
                                                                      "bigboy queue-wait T-7 age=65m limit=60m\n"
                                                                      + "cloudbb-1 host-retiring - days=3\n\n/fleet/alarms"));
        assertThat(evaluation.undetermined()).isEmpty();
    }

    /// S1.23: every host's alarms empty (hosts present): clear. Mutation: clear only when `hosts` is empty.
    @Test
    void s1_23_hostAlarms_everyHostEmpty_isClear() {
        var evaluation = withAlarms(HttpReply.httpReply(200, NO_ALARMS));

        assertThat(evaluation.conditions()).contains(Condition.clear(AlarmCode.HOST_ALARMS));
        assertThat(evaluation.fires(AlarmCode.HOST_ALARMS)).isFalse();
    }

    /// S1.24: a 404 (an older controller) or a reply without `hosts`: undetermined (check-blind fires), never clear.
    /// Mutation: a failure read as clear (`host-alarms` gets a Clear state).
    @Test
    void s1_24_hostAlarms_routeMissingOrUndecodable_isUndetermined_notClear() {
        for (var reply : List.of(HttpReply.httpReply(404, "{\"error\":\"no route\"}"),
                                 HttpReply.httpReply(200, "{\"v\":1,\"asOf\":\"2026-10-13T10:00:00Z\"}"))) {
            var evaluation = withAlarms(reply);

            assertThat(codes(evaluation)).doesNotContain(AlarmCode.HOST_ALARMS);
            assertThat(evaluation.undetermined()).hasSize(1);
            assertThat(evaluation.fires(AlarmCode.CHECK_BLIND)).isTrue();
            assertThat(evaluation.fires(AlarmCode.CONTROLLER_DOWN)).isFalse();
            assertThat(codes(evaluation)).contains(AlarmCode.WORKER_LOST, AlarmCode.DUMP_STALE);
        }
    }

    @Test
    void hostAlarms_serverError_isControllerDown() {
        var evaluation = withAlarms(HttpReply.httpReply(503, "{\"error\":\"starting\"}"));

        assertThat(evaluation.fires(AlarmCode.CONTROLLER_DOWN)).isTrue();
    }

    /// A controller whose only silent route is `/fleet/alarms` (it accepts the connection and never answers): the read
    /// deadline counts as down, as for every other read, so `controller-down` fires, not `check-blind`.
    /// Mutation: no answer mapped to undetermined (`check-blind` fires instead).
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void hostAlarms_noAnswer_isControllerDown() {
        Controller silentAlarms = Controller.controller(call -> call.path().equals("/fleet/alarms")
                                                                ? Promise.<HttpReply> promise()
                                                                : answer(call,
                                                                         Map.of("/maintenance", HttpReply.httpReply(200, FRESH),
                                                                                "/hosts", HttpReply.httpReply(200, HOSTS))),
                                                        TemperJson.mapper());

        var evaluation = AlarmCheck.evaluate(silentAlarms, config(1), time.clock(), time.pacer(), "s", Duration.ofMillis(100))
                                   .await()
                                   .unwrap();

        assertThat(evaluation.fires(AlarmCode.CONTROLLER_DOWN)).isTrue();
        assertThat(evaluation.fires(AlarmCode.CHECK_BLIND)).isFalse();
    }

    /// A `waiter-gone` alarm carries a ticket and no figures: the line is `<host> waiter-gone <ticket>`.
    /// Mutation: any other format (a figure or a `-` appended) is red.
    @Test
    void hostAlarms_waiterGone_printsHostKindTicketOnly() {
        var body = "{\"v\":1,\"asOf\":\"2026-10-13T10:00:00Z\",\"hosts\":[{\"host\":\"bigboy\",\"status\":\"ok\",\"takenAt\":null,"
                   + "\"hostAt\":null,\"skewS\":null,\"retireAt\":null,\"alarms\":["
                   + "{\"kind\":\"waiter-gone\",\"ticket\":\"r9\",\"ageMin\":null,\"limitMin\":null,\"days\":null,\"skewS\":null,"
                   + "\"firedAt\":\"2026-10-13T09:55:00Z\"}]}]}";

        var evaluation = withAlarms(HttpReply.httpReply(200, body));

        assertThat(evaluation.conditions()).contains(Condition.firing(AlarmCode.HOST_ALARMS, "bigboy waiter-gone r9\n\n/fleet/alarms"));
    }

    @Test
    void disk_belowTheThreshold_fires_measuredLocally() {
        var evaluation = AlarmCheck.diskConditions(List.of(disk), Long.MAX_VALUE);

        assertThat(evaluation.conditions()).hasSize(1);
        assertThat(evaluation.conditions().getFirst()).isInstanceOf(Condition.Firing.class);
        assertThat(((Condition.Firing) evaluation.conditions().getFirst()).detail()).startsWith(disk + " has ");
        assertThat(AlarmCheck.diskConditions(List.of(disk), 1).conditions()).containsExactly(Condition.clear(AlarmCode.DISK_LOW));
    }

    /// A path that cannot be measured leaves `disk-low` undetermined rather than clear.
    @Test
    void disk_unmeasurablePath_isUndetermined() {
        var evaluation = AlarmCheck.diskConditions(List.of(disk, disk.resolve("missing/dir")), 1);

        assertThat(evaluation.conditions()).isEmpty();
        assertThat(evaluation.undetermined()).hasSize(1);
        assertThat(evaluation.undetermined().getFirst()).isInstanceOf(CliError.Unreadable.class);
    }
}
