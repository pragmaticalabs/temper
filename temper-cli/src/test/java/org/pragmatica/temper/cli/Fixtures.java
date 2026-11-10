package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.diff.DiffOutcome;
import org.pragmatica.temper.domain.diff.KeyOutcome;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.DiffRefusal;
import org.pragmatica.temper.protocol.api.DiffView;
import org.pragmatica.temper.protocol.api.EventKind;
import org.pragmatica.temper.protocol.api.FleetAlarmsView;
import org.pragmatica.temper.protocol.api.GateView;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.KnownDto;
import org.pragmatica.temper.protocol.api.MaintenanceRunDto;
import org.pragmatica.temper.protocol.api.MaintenanceView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.RefusalDto;
import org.pragmatica.temper.protocol.api.RefusalMatcherDto;
import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.api.SubmitResponse;
import org.pragmatica.temper.protocol.api.WsServerMessage;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Controller DTOs as the fake controller serves them.
final class Fixtures {
    static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";
    static final Instant AT = Instant.parse("2026-10-13T10:00:00Z");

    private Fixtures() {}

    /// The judges of the gate fixtures: `JUDGE` the newest build, `OLD_JUDGE` the one before.
    static final String JUDGE = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
    static final String OLD_JUDGE = "5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80";

    /// A PASSED, FAILED or EMPTY gate answer about job 4711 under [#JUDGE]: 12 testcases, `failed` red ones.
    static GateView gateVerdict(GateAnswer answer, int failed, List<String> annotations) {
        return GateView.gateView(SHA,
                                 some(JUDGE),
                                 answer,
                                 some(4711L),
                                 some(JobState.valueOf(answer.name())),
                                 some(new ResultRecordDto.CountsDto(12, 12 - failed, failed, 0, 0, 12, 3, none(), none(), none())),
                                 annotations,
                                 some("stored"),
                                 none(),
                                 List.of(),
                                 none(),
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of());
    }

    /// CONFLICT under [#JUDGE]: 4711 FAILED and 4690 PASSED, with `running` when a newer Gate runs.
    static GateView gateConflict(Option<Long> running) {
        return GateView.gateView(SHA,
                                 some(JUDGE),
                                 GateAnswer.CONFLICT,
                                 none(),
                                 none(),
                                 none(),
                                 List.of(),
                                 none(),
                                 none(),
                                 List.of(GateView.Vote.vote(4711, JobState.FAILED, JUDGE),
                                         GateView.Vote.vote(4690, JobState.PASSED, JUDGE)),
                                 running,
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of());
    }

    /// RUNNING: job 4720 in `state`, its previous vote 4711 FAILED.
    static GateView gateRunning(JobState state) {
        return GateView.gateView(SHA,
                                 some(JUDGE),
                                 GateAnswer.RUNNING,
                                 some(4720L),
                                 some(state),
                                 none(),
                                 List.of(),
                                 none(),
                                 some(GateView.Vote.vote(4711, JobState.FAILED, JUDGE)),
                                 List.of(),
                                 none(),
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of());
    }

    /// NONE: only a VOID Gate, 4702.
    static GateView gateNone() {
        return GateView.gateView(SHA,
                                 none(),
                                 GateAnswer.NONE,
                                 none(),
                                 none(),
                                 none(),
                                 List.of(),
                                 none(),
                                 none(),
                                 List.of(),
                                 none(),
                                 List.of(),
                                 List.of(),
                                 List.of(4702L),
                                 List.of());
    }

    /// PASSED under [#JUDGE] with history: an older judge's FAILED and a Gate judged across a deploy.
    static GateView gateWithHistory() {
        var passed = gateVerdict(GateAnswer.PASSED, 0, List.of());

        return GateView.gateView(passed.sha(),
                                 passed.judge(),
                                 passed.answer(),
                                 passed.job(),
                                 passed.state(),
                                 passed.counts(),
                                 passed.annotations(),
                                 passed.evidence(),
                                 none(),
                                 List.of(),
                                 none(),
                                 List.of(GateView.Vote.vote(4650, JobState.FAILED, OLD_JUDGE)),
                                 List.of(GateView.JudgeChanged.judgeChanged(4700, JobState.FAILED, OLD_JUDGE, JUDGE)),
                                 List.of(4702L),
                                 List.of());
    }

    static JobView job(long id, JobState state) {
        return job(id, state, none());
    }

    static JobView job(long id, JobState state, Option<String> reason) {
        return JobView.jobView(id,
                               state,
                               state.isTerminal()
                               ? some(state.name())
                               : none(),
                               reason,
                               "tester",
                               AT,
                               -20,
                               false,
                               false,
                               false,
                               new SourceView(some("main"), some(SHA)),
                               new RecipeDto.ModuleTest(List.of("core"), some("Foo*"), none()),
                               List.of(),
                               List.of());
    }

    static ResultRecordDto result(long id, String verdict, Option<String> reason, int failed, int error, int observed) {
        return ResultRecordDto.resultRecordDto(id,
                                               verdict,
                                               reason,
                                               none(),
                                               new SourceView(some("main"), some(SHA)),
                                               new RecipeDto.ModuleTest(List.of("core"), some("Foo*"), none()),
                                               List.of(),
                                               new ResultRecordDto.CountsDto(12, 12 - failed - error, failed, error, 2, 12, 3, none(), none(), none()),
                                               List.of(),
                                               observed,
                                               List.of(ResultRecordDto.UnitResultDto.unitResultDto("build",
                                                                                                   some(0),
                                                                                                   some("bigboy"),
                                                                                                   none(),
                                                                                                   some(AT),
                                                                                                   some(AT),
                                                                                                   none(),
                                                                                                   none())),
                                               none(),
                                               ResultRecordDto.EvidenceDto.NOT_STORED,
                                               ResultRecordDto.TimingsDto.timingsDto(none(), none()),
                                               none());
    }

    static final ResultRecordDto.EvidenceDto STORED = ResultRecordDto.EvidenceDto.evidenceDto(true,
                                                                                        some("/var/lib/temper/jobs/41"),
                                                                                        some(2048L));

    /// A result record whose job had a manifest: `missing` and `drift` counted, the evidence as given.
    static ResultRecordDto manifestResult(long id,
                                          String verdict,
                                          Option<String> reason,
                                          int missing,
                                          int drift,
                                          ResultRecordDto.EvidenceDto evidence) {
        var plain = result(id, verdict, reason, 0, 0, 0);
        var counts = plain.counts();

        return ResultRecordDto.resultRecordDto(id,
                                               verdict,
                                               reason,
                                               plain.reasonDetail(),
                                               plain.source(),
                                               plain.recipe(),
                                               plain.annotations(),
                                               new ResultRecordDto.CountsDto(counts.testcases(),
                                                                             counts.passed(),
                                                                             counts.failed(),
                                                                             counts.error(),
                                                                             counts.skipped(),
                                                                             counts.testsAttr(),
                                                                             counts.classes(),
                                                                             some(counts.classes()),
                                                                             some(missing),
                                                                             some(drift)),
                                               plain.failed(),
                                               plain.observedFailures(),
                                               plain.units(),
                                               some(ResultRecordDto.ManifestDto.manifestDto(true,
                                                                                            List.of(),
                                                                                            List.of(),
                                                                                            List.of(),
                                                                                            0)),
                                               evidence,
                                               plain.timings(),
                                               plain.judge());
    }

    static RefusalDto refusal(String code, RefusalMatcherDto matcher, Option<Instant> expiredAt) {
        return new RefusalDto(code, matcher, some(9), "flaky until #9", some("#9 closed"), expiredAt);
    }

    /// A known-failure entry as the controller answers it (m2-design §4.4).
    static KnownDto known(long id, String testId, Option<Instant> expiredAt) {
        return new KnownDto(id, testId, 1679, "flaky until #1679", "#1679 closes", "cto", AT, expiredAt, none());
    }

    /// A row the controller could not read (ruling R-M2-15): its stored values and why.
    static KnownDto unreadableKnown(long id) {
        return KnownDto.unreadableDto(id, "org.x.UnreadableTest", 7, " ", "u", "cto", AT, none(), "Invalid reason: ' '");
    }

    static SubmitResponse submitted(long id, JobState state, boolean deduplicated) {
        return SubmitResponse.submitResponse(id, state, deduplicated);
    }

    static RefusedResponse refused(String code, String detail, Option<Long> job) {
        return new RefusedResponse(code, detail, job);
    }

    static WsServerMessage.WsEvent finished(long seq, long job, String verdict) {
        return new WsServerMessage.WsEvent(seq,
                                           AT,
                                           some(job),
                                           EventKind.FINISHED,
                                           some(verdict),
                                           "finished " + job + " " + verdict + " sha=0cf5631 module-test testcases=12 failed=0 seq=" + seq);
    }

    /// `GET /maintenance`: the latest dump ended at `latestEnded` with `latestOk`; the latest good one at `lastGood`.
    static MaintenanceView maintenance(Instant latestEnded, boolean latestOk, Option<Instant> lastGood) {
        return MaintenanceView.maintenanceView(Option.some(MaintenanceRunDto.maintenanceRunDto("pg_dump",
                                                                                               latestEnded.minusSeconds(60),
                                                                                               latestEnded,
                                                                                               latestOk,
                                                                                               latestOk
                                                                                               ? Option.none()
                                                                                               : Option.some("pg_dump exited 1"))),
                                               lastGood);
    }

    /// `GET /hosts` with one host in `state`.
    static List<HostView> hosts(String state) {
        return List.of(HostView.hostView("bigboy", state, 5, 0, 0, 0, Option.none()));
    }

    /// `GET /fleet/alarms` with the one host `bigboy` and no current alarm.
    static FleetAlarmsView noAlarms() {
        return FleetAlarmsView.fleetAlarmsView(AT,
                                               List.of(FleetAlarmsView.HostAlarms.hostAlarms("bigboy", "ok", Option.none(), Option.none(),
                                                                                              Option.none(), Option.none(), List.of())));
    }

    /// `dump-dir = "…"` for `[alarm]`: a directory under `home` holding one verified dump, written an hour before [#AT].
    static String dumpDir(Path home) throws IOException {
        var dir = Files.createDirectories(home.resolve("pgdump"));
        var dump = Files.writeString(dir.resolve("temper-20261013T090000Z.dump"), "PGDMP");
        Files.setLastModifiedTime(dump, FileTime.from(AT.minusSeconds(3600)));
        return "dump-dir = \"" + dir + "\"\n";
    }

    /// `diff 4690 4711`: A PASSED, B FAILED with 12 rows; `added` and `newlyFailing` as given, one removed test, one
    /// skip change.
    static DiffView diffView(List<DiffView.Entry> added, List<DiffView.Entry> newlyFailing, Option<Long> base) {
        return diffView(DiffView.Side.side(4711, JobState.FAILED, "gate", 12), added, newlyFailing, base);
    }

    /// `diff 4690 4711` where B is EMPTY: no row, A's one test removed.
    static DiffView diffEmptyB() {
        return diffView(DiffView.Side.side(4711, JobState.EMPTY, "gate", 0), List.of(), List.of(), none());
    }

    private static DiffView diffView(DiffView.Side b, List<DiffView.Entry> added, List<DiffView.Entry> newlyFailing, Option<Long> base) {
        var addedFailing = (int) added.stream().filter(test -> test.outcome() == KeyOutcome.FAILED).count();

        return DiffView.diffView(DiffView.Side.side(4690, JobState.PASSED, "gate", 12),
                                 b,
                                 base,
                                 added,
                                 List.of(DiffView.Entry.entry("org.x.GoneTest", "old", KeyOutcome.PASSED)),
                                 newlyFailing,
                                 List.of(),
                                 List.of(DiffView.Skip.skip("org.x.OffTest", "off", KeyOutcome.FAILED, KeyOutcome.SKIPPED)),
                                 DiffView.Counts.counts(added.size(), addedFailing, 1, newlyFailing.size(), 0, 1, 0));
    }

    static DiffView.Entry test(String className, String name, KeyOutcome outcome) {
        return DiffView.Entry.entry(className, name, outcome);
    }

    /// A refused `diff 4690 4711` about side `side` in `state` (PRUNED, NOT_JUDGED, NOT_TERMINAL).
    static DiffRefusal diffRefused(DiffObstacle reason, DiffOutcome.Side side, JobState state) {
        return DiffRefusal.diffRefusal(reason,
                                       some(4690L),
                                       4711,
                                       List.of(DiffRefusal.SideState.sideState(side, side == DiffOutcome.Side.A ? 4690 : 4711, state)),
                                       none(),
                                       List.of());
    }

    /// A refused `diff 4711 --base 0cf5631`: NO_BASE, or BASE_CONFLICT with the votes of [#gateConflict].
    static DiffRefusal diffBaseRefused(DiffObstacle reason) {
        return DiffRefusal.diffRefusal(reason,
                                       none(),
                                       4711,
                                       List.of(),
                                       some(SHA),
                                       reason == DiffObstacle.BASE_CONFLICT
                                       ? List.of(GateView.Vote.vote(4711, JobState.FAILED, JUDGE), GateView.Vote.vote(4690, JobState.PASSED, JUDGE))
                                       : List.of());
    }
}
