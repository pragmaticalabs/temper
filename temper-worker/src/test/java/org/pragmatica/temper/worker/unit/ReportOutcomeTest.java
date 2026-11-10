package org.pragmatica.temper.worker.unit;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.ParsedTestcase;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.protocol.message.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ref;

class ReportOutcomeTest {
    private static ReportFile file(String name, long testsAttr, int rows) {
        return ReportFile.reportFile(name,
                                     testsAttr,
                                     IntStream.range(0, rows)
                                              .mapToObj(index -> ParsedTestcase.parsedTestcase("a.B", "t" + index, Outcome.PASSED, 0.1))
                                              .toList());
    }

    @Test
    void reportOutcome_sumsFilesThatParsed_andMarksReportWhenOneFails() {
        var outcome = ReportOutcome.reportOutcome(List.of(Result.success(file("TEST-a.xml", 3, 5)),
                                                          Causes.cause("bad").result(),
                                                          Result.success(file("TEST-b.xml", 2, 2))));

        assertThat(outcome.reportFiles()).isEqualTo(3);
        assertThat(outcome.testsAttr()).isEqualTo(5);
        assertThat(outcome.rows()).hasSize(7);
        assertThat(outcome.errorReason()).isEqualTo(Option.some("report"));
    }

    @Test
    void reportOutcome_hasNoErrorWhenEverythingParsed() {
        assertThat(ReportOutcome.reportOutcome(List.of(Result.success(file("TEST-a.xml", 1, 1))))
                                .errorReason()).isEqualTo(Option.none());
    }

    @Test
    void chunks_alwaysSendsOneChunk_andOnlyTheLastIsLast() {
        var unit = ref(9, "main");

        assertThat(ReportOutcome.reportOutcome(List.of())
                                .chunks(unit, 1)).singleElement()
                                              .satisfies(chunk -> {
                                                  assertThat(chunk.last()).isTrue();
                                                  assertThat(chunk.rows()).isEmpty();
                                              });

        var exact = ReportOutcome.reportOutcome(List.of(Result.success(file("f", 0, Message.MAX_ROWS_PER_CHUNK))))
                                 .chunks(unit, 1);

        assertThat(exact).hasSize(1);

        var over = ReportOutcome.reportOutcome(List.of(Result.success(file("f", 0, Message.MAX_ROWS_PER_CHUNK + 1))))
                                .chunks(unit, 1);

        assertThat(over).extracting(Message.UnitReport::chunk)
                        .containsExactly(0, 1);
        assertThat(over).extracting(Message.UnitReport::last)
                        .containsExactly(false, true);
        assertThat(over.get(1)
                       .rows()).hasSize(1);
        assertThat(over.getFirst()
                       .rows()
                       .getFirst()
                       .unit()).isEqualTo("main");
    }

    @Test
    void markerUnreadable_selectsNothing() {
        assertThat(ReportOutcome.MARKER_UNREADABLE.errorReason()).isEqualTo(Option.some("marker"));
        assertThat(ReportOutcome.MARKER_UNREADABLE.reportFiles()).isZero();
    }
}
