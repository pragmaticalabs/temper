package org.pragmatica.temper.cli.command;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.GateView;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.KnownDto;
import org.pragmatica.temper.protocol.api.LostAttemptDto;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.RefusalDto;
import org.pragmatica.temper.protocol.api.RefusalMatcherDto;
import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.RerunView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.api.SubmitResponse;
import org.pragmatica.temper.protocol.api.UnitView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class LinesTest {
    @Test
    void detail_isAlwaysQuoted_withEscapes() {
        assertThat(Lines.refused(new RefusedResponse("R-SHELL", "exec:exec \"x\" is a shell\nsecond", none())))
                  .isEqualTo("submit - REFUSED code=R-SHELL detail=\"exec:exec \\\"x\\\" is a shell\\nsecond\"");
        assertThat(Lines.refused(new RefusedResponse("R-M2", "plain", some(9L))))
                  .isEqualTo("submit - REFUSED code=R-M2 detail=\"plain\" job=9");
    }

    @Test
    void values_areQuotedOnlyWhenNeeded() {
        assertThat(Lines.value("tests")).isEqualTo("tests");
        assertThat(Lines.value("a,b")).isEqualTo("a,b");
        assertThat(Lines.value("two words")).isEqualTo("\"two words\"");
        assertThat(Lines.value("k=v")).isEqualTo("\"k=v\"");
        assertThat(Lines.value("back\\slash")).isEqualTo("\"back\\\\slash\"");
    }

    /// m2-design §4.4's example line; an expired entry ends with `expired-at`.
    @Test
    void known_line_isTheDesignsForm() {
        var active = new KnownDto(12, "org.x.EmberAmnesiacRestartTest", 1679, "flaky \"restart\"", "#1679 closes", "cto",
                                  Instant.parse("2026-10-19T10:00:00Z"), none(), none());
        var expired = new KnownDto(13, "org.x.FooTest#run(int) [1]", 7, "r", "never", "cto", Instant.parse("2026-10-19T10:00:00Z"),
                                   some(Instant.parse("2026-10-19T11:00:00Z")), none());

        assertThat(Lines.known(active)).isEqualTo("known 12 ACTIVE test=org.x.EmberAmnesiacRestartTest issue=1679 until=\"#1679 closes\" "
                                                  + "reason=\"flaky \\\"restart\\\"\" by=cto");
        assertThat(Lines.known(expired)).isEqualTo("known 13 EXPIRED test=\"org.x.FooTest#run(int) [1]\" issue=7 until=never reason=\"r\" "
                                                   + "by=cto expired-at=2026-10-19T11:00:00Z");
    }

    /// Ruling R-M2-15: a row the controller could not read is shown, never hidden: UNREADABLE, its stored values quoted
    /// like every line's, and why, always quoted, last.
    @Test
    void known_line_ofAnUnreadableRow_isUnreadable_withWhy() {
        var unreadable = KnownDto.unreadableDto(13, "org.x.Unreadable Test", 0, " ", "", "cto", Instant.parse("2026-10-19T10:00:00Z"),
                                                some(Instant.parse("2026-10-19T11:00:00Z")), "Invalid issue: '0'; Invalid reason: ' '");

        assertThat(Lines.known(unreadable)).isEqualTo("known 13 UNREADABLE test=\"org.x.Unreadable Test\" issue=0 until=\"\" reason=\" \" by=cto "
                                                      + "expired-at=2026-10-19T11:00:00Z unreadable=\"Invalid issue: '0'; Invalid reason: ' '\"");
    }

    @Test
    void refusal_line_quotesFreeTextAndOmitsAbsentFields() {
        var refusal = new RefusalDto("R-X",
                                     new RefusalMatcherDto.ExcludeTestClass("FooTest", "@Disabled(\"#1\")", none()),
                                     none(),
                                     "two words",
                                     none(),
                                     none());

        assertThat(Lines.refusal(refusal)).isEqualTo("refusal R-X ACTIVE matcher=excludeTestClass simpleClassName=FooTest disabledMarker=\"@Disabled(\\\"#1\\\")\" reason=\"two words\"");
    }

    @Test
    void refusal_line_ofAnExpiredRefusal_endsWithExpiredAt() {
        var refusal = new RefusalDto("R-OLD",
                                     new RefusalMatcherDto.ItTestRequired("forge-tests"),
                                     some(7),
                                     "why",
                                     some("never"),
                                     some(Instant.parse("2026-10-01T00:00:00Z")));

        assertThat(Lines.refusal(refusal)).isEqualTo("refusal R-OLD EXPIRED matcher=itTestRequired moduleSegment=forge-tests issue=7 until=never reason=\"why\" expired-at=2026-10-01T00:00:00Z");
    }

    /// The verdict keeps its closed-vocabulary token; the free text the unit reported after it follows, quoted.
    @Test
    void result_and_wait_lines_showTheReasonDetail_afterTheToken() {
        var record = ResultRecordDto.resultRecordDto(5,
                                                     "ERROR",
                                                     some("manifest"),
                                                     some("JUnit 3/4 tests are not supported: module legacy, class fx.G"),
                                                     new SourceView(none(), some("a".repeat(40))),
                                                     new RecipeDto.Gate(),
                                                     List.of(),
                                                     new ResultRecordDto.CountsDto(0, 0, 0, 0, 0, 0, 0, none(), none(), none()),
                                                     List.of(),
                                                     0,
                                                     List.of(),
                                                     none(),
                                                     ResultRecordDto.EvidenceDto.NOT_STORED,
                                                     ResultRecordDto.TimingsDto.timingsDto(none(), none()),
                                               none());

        assertThat(Lines.result(record, some(List.of()))).isEqualTo("result 5 ERROR reason=manifest detail=\"JUnit 3/4 tests are not supported: module legacy, class fx.G\" testcases=0 failed=0 skipped=0 units=0 observed-failures=0 evidence=missing");
        assertThat(Lines.waited(record)).isEqualTo("wait 5 ERROR reason=manifest detail=\"JUnit 3/4 tests are not supported: module legacy, class fx.G\" testcases=0 failed=0 observed-failures=0 evidence=missing");
    }

    /// m4-design §11: a unit that lost attempts is named with its current attempt and each lost one as `<host> a<n>`, on
    /// `result`, `wait` and a job's state line; a unit that lost none adds nothing.
    @Test
    void result_wait_state_lines_nameTheLostAttempts() {
        var at = Instant.parse("2026-10-27T10:00:00Z");
        var lost = List.of(LostAttemptDto.lostAttemptDto(1, "cloudbb-3", at, none(), at),
                           LostAttemptDto.lostAttemptDto(2, "cloudbb-4", at, some(at), at));
        var units = List.of(ResultRecordDto.UnitResultDto.unitResultDto("gate", some(0), some("bigboy"), none(), some(at), some(at),
                                                                        none(), none(), some(3), some(false), lost),
                            ResultRecordDto.UnitResultDto.unitResultDto("main", some(0), some("bigboy"), none(), some(at), some(at),
                                                                        none(), none(), some(1), some(false), List.of()));
        var record = ResultRecordDto.resultRecordDto(5,
                                                     "PASSED",
                                                     none(),
                                                     none(),
                                                     new SourceView(none(), some("a".repeat(40))),
                                                     new RecipeDto.Gate(),
                                                     List.of(),
                                                     new ResultRecordDto.CountsDto(1, 1, 0, 0, 0, 1, 1, none(), none(), none()),
                                                     List.of(),
                                                     0,
                                                     units,
                                                     none(),
                                                     ResultRecordDto.EvidenceDto.NOT_STORED,
                                                     ResultRecordDto.TimingsDto.timingsDto(none(), none()),
                                                     none());
        var marker = " attempt(gate)=\"3 (lost: cloudbb-3 a1, cloudbb-4 a2)\"";
        var running = UnitView.unitView("gate", UnitState.RUNNING, none(), some("bigboy"), none(), some(at), none(), none(), none(),
                                        none(), false, none(), none(), none(), 2, some(false), some("none"), lost.subList(0, 1));
        var view = JobView.jobView(5, JobState.RUNNING, none(), none(), "cto", at, 0, false, false, false,
                                   new SourceView(none(), some("a".repeat(40))), new RecipeDto.Gate(), List.of(running), List.of(), false);

        assertThat(Lines.result(record, some(List.of())))
            .isEqualTo("result 5 PASSED testcases=1 failed=0 skipped=0 units=2 observed-failures=0 evidence=missing" + marker);
        assertThat(Lines.waited(record)).isEqualTo("wait 5 PASSED testcases=1 failed=0 observed-failures=0 evidence=missing" + marker);
        assertThat(Lines.state("result", view)).isEqualTo("result 5 RUNNING attempt(gate)=\"2 (lost: cloudbb-3 a1)\"");
    }

    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";
    private static final String JUDGE = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
    private static final String OLD_JUDGE = "5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80";

    private static GateView gate(GateAnswer answer,
                                 String judge,
                                 List<String> annotations,
                                 List<GateView.Vote> older,
                                 List<GateView.JudgeChanged> judgeChanged,
                                 List<Long> unjudged) {
        return GateView.gateView(SHA,
                                 some(judge),
                                 answer,
                                 some(4760L),
                                 some(JobState.valueOf(answer.name())),
                                 some(new ResultRecordDto.CountsDto(21050, 21049, 0, 1, 0, 21050, 600, none(), none(), none())),
                                 annotations,
                                 some("stored"),
                                 none(),
                                 List.of(),
                                 none(),
                                 older,
                                 judgeChanged,
                                 unjudged,
                                 List.of());
    }

    /// m2-design §1.5: the answer's fields, then `older`, `judge-changed` and `unjudged`, each only when not empty; a
    /// judge as its 7-hex form; `failed` counts error testcases too.
    @Test
    void gate_printsTheAnswerThenTheHistory() {
        var view = gate(GateAnswer.PASSED,
                        JUDGE,
                        List.of(),
                        List.of(GateView.Vote.vote(4711, JobState.FAILED, OLD_JUDGE), GateView.Vote.vote(4690, JobState.PASSED, OLD_JUDGE)),
                        List.of(GateView.JudgeChanged.judgeChanged(4700, JobState.FAILED, OLD_JUDGE, JUDGE)),
                        List.of(4702L, 4701L));

        assertThat(Lines.gate(view)).isEqualTo("gate 0cf5631 PASSED job=4760 judge=1a2b3c4 testcases=21050 failed=1 "
                                               + "older=4711:FAILED@5d6e7f8,4690:PASSED@5d6e7f8 "
                                               + "judge-changed=4700:FAILED@5d6e7f8>1a2b3c4 unjudged=4702,4701");
    }

    /// Annotations bare when they are, or are `key=value` of bare parts; quoted otherwise (one field, one line).
    @Test
    void gate_printsAnnotationsBare_orQuoted() {
        var view = gate(GateAnswer.FAILED,
                        "unjudged",
                        List.of("KNOWN(#1679)", "evidence=missing", "judge-changed=5d6e7f8", "superseded", "a b", "x=y=z"),
                        List.of(),
                        List.of(),
                        List.of());

        assertThat(Lines.gate(view)).isEqualTo("gate 0cf5631 FAILED job=4760 judge=unjudged testcases=21050 failed=1 KNOWN(#1679) "
                                               + "evidence=missing judge-changed=5d6e7f8 superseded \"a b\" \"x=y=z\"");
    }

    @Test
    void gate_dirtyJudge_keepsDirty() {
        assertThat(Lines.gate(gate(GateAnswer.EMPTY, JUDGE + "-dirty", List.of(), List.of(), List.of(), List.of())))
            .isEqualTo("gate 0cf5631 EMPTY job=4760 judge=1a2b3c4-dirty testcases=21050 failed=1");
    }

    /// m2-design §5.3: the answering Gate's reruns as `rerun=<id>:<STATE|VERDICT>`, after the answer's own fields and
    /// before the history; information only, the answer stays the Gate's.
    @Test
    void gate_printsTheAnsweringGatesReruns() {
        var plain = gate(GateAnswer.FAILED, JUDGE, List.of(), List.of(), List.of(), List.of(4702L));
        var view = GateView.gateView(plain.sha(), plain.judge(), plain.answer(), plain.job(), plain.state(), plain.counts(),
                                     plain.annotations(), plain.evidence(), plain.previous(), plain.conflict(), plain.running(),
                                     plain.older(), plain.judgeChanged(), plain.unjudged(),
                                     List.of(RerunView.rerunView(4770, JobState.PASSED, List.of("rerun(org.x.FooTest)=PASSED")),
                                             RerunView.rerunView(4771, JobState.QUEUED, List.of())));

        assertThat(Lines.gate(view)).isEqualTo("gate 0cf5631 FAILED job=4760 judge=1a2b3c4 testcases=21050 failed=1 "
                                               + "rerun=4770:PASSED,4771:QUEUED unjudged=4702");
    }

    @Test
    void rerunSubmitted_andRefused() {
        assertThat(Lines.rerunSubmitted(new SubmitResponse(4740, JobState.QUEUED, false),
                                        4711,
                                        "org.x.A,org.x.B",
                                        "0cf5631"))
            .isEqualTo("rerun 4740 QUEUED of=4711 classes=org.x.A,org.x.B sha=0cf5631 deduplicated=false");
        assertThat(Lines.refused("rerun", new RefusedResponse("rerun", "no module for org.x.A", some(4741L))))
            .isEqualTo("rerun - REFUSED code=rerun detail=\"no module for org.x.A\" job=4741");
    }
}
