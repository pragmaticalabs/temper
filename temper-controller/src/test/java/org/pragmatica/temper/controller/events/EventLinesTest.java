package org.pragmatica.temper.controller.events;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.api.UserPropertyDto;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Event lines (spec §8.3, m1-design §2.5): `missing=<n>` on `FAILED(missing-classes)`, before `seq`; the record's
/// `KNOWN(#n)` annotations after the counts (m2-design §4.3).
class EventLinesTest {
    private static EventRow finished(String verdict, Option<String> reason, Option<Integer> missing) {
        var counts = Counts.counts(20786, 20786, 0, 0, 0, 20786, 4158, some(4159), missing, some(0));

        return EventRow.eventRow(91,
                                 Instant.parse("2026-10-13T10:00:00Z"),
                                 some(JobId.jobId(4712).unwrap()),
                                 EventRow.Kind.FINISHED,
                                 EventPayload.eventPayload(some("0cf563192ad5"), none(), some("gate"), none(), some(verdict), reason,
                                                           some(counts), none()));
    }

    @Test
    void render_addsMissing_onFailedMissingClasses() {
        assertThat(EventLines.render(finished("FAILED", some("missing-classes"), some(1)), List.of()))
            .isEqualTo("finished 4712 FAILED sha=0cf5631 gate testcases=20786 failed=0 missing=1 seq=91");
    }

    /// R-13, amended: a unit whose start could not be recorded is announced as `start-unknown`, with its unit.
    @Test
    void render_startUnknown_namesTheUnit() {
        var event = EventRow.eventRow(90,
                                      Instant.parse("2026-10-13T10:00:00Z"),
                                      some(JobId.jobId(4712).unwrap()),
                                      EventRow.Kind.START_UNKNOWN,
                                      EventPayload.eventPayload(some("0cf563192ad5"), none(), some("gate"), some("gate"), none(), none(),
                                                                none(), none()));

        assertThat(EventLines.render(event, List.of())).isEqualTo("start-unknown 4712 unit=gate sha=0cf5631 gate seq=90");
    }

    @Test
    void render_omitsMissing_forAnyOtherVerdict() {
        assertThat(EventLines.render(finished("PASSED", none(), some(0)), List.of()))
            .isEqualTo("finished 4712 PASSED sha=0cf5631 gate testcases=20786 failed=0 seq=91");
        assertThat(EventLines.render(finished("FAILED", some("tests"), some(0)), List.of())).doesNotContain("missing=");
    }

    @Test
    void render_appendsKnownAnnotations_afterTheCounts_beforeSeq() {
        var counts = Counts.counts(16234, 16190, 1, 0, 43, 13755, 2101, none(), none(), none());
        var event = EventRow.eventRow(88,
                                      Instant.parse("2026-10-13T10:00:00Z"),
                                      some(JobId.jobId(4711).unwrap()),
                                      EventRow.Kind.FINISHED,
                                      EventPayload.eventPayload(some("0cf563192ad5"), none(), some("gate"), none(), some("FAILED"),
                                                                some("tests"), some(counts), none(), List.of("KNOWN(#21)", "KNOWN(#1679)")));

        assertThat(EventLines.render(event, List.of()))
            .isEqualTo("finished 4711 FAILED sha=0cf5631 gate testcases=16234 failed=1 KNOWN(#21) KNOWN(#1679) seq=88");
    }

    /// A superseded job's cancelled event: KNOWN follows the counts and precedes `superseded-by`, per the line's grammar.
    @Test
    void render_cancelledSuperseded_placesKnownBeforeSupersededBy() {
        var counts = Counts.counts(12, 11, 1, 0, 0, 12, 1, none(), none(), none());
        var event = EventRow.eventRow(93,
                                      Instant.parse("2026-10-13T10:00:00Z"),
                                      some(JobId.jobId(4714).unwrap()),
                                      EventRow.Kind.CANCELLED,
                                      EventPayload.eventPayload(some("0cf563192ad5"), none(), some("gate"), none(), some("CANCELLED"),
                                                                none(), some(counts), some(4715L), List.of("KNOWN(#7)")));

        assertThat(EventLines.render(event, List.of()))
            .isEqualTo("cancelled 4714 CANCELLED sha=0cf5631 gate testcases=12 failed=1 KNOWN(#7) superseded-by=4715 seq=93");
    }

    /// Verify4 blocker 2: the job's user properties follow the KNOWN annotations and precede `superseded-by`, each a
    /// quoted token whose value can never break the line.
    @Test
    void render_placesUserProperties_afterKnown_beforeSupersededBy_escaped() {
        var event = EventRow.eventRow(93,
                                      Instant.parse("2026-10-13T10:00:00Z"),
                                      some(JobId.jobId(4714).unwrap()),
                                      EventRow.Kind.CANCELLED,
                                      EventPayload.eventPayload(some("0cf563192ad5"), none(), some("mvn"), none(), some("CANCELLED"),
                                                                none(), none(), some(4715L), List.of("KNOWN(#7)")));

        assertThat(EventLines.render(event,
                                     List.of(UserPropertyDto.userPropertyDto("-DskipTests=true", some("skipped")),
                                             UserPropertyDto.userPropertyDto("-Dx=a\rb\u001b", none()))))
            .isEqualTo("cancelled 4714 CANCELLED sha=0cf5631 mvn KNOWN(#7) tests-skipped=\"-DskipTests=true\""
                       + " property=\"-Dx=a\\rb\\u001b\" superseded-by=4715 seq=93");
    }

    /// A payload stored before `known` existed reads as no annotations, not as a broken event.
    @Test
    void payload_storedWithoutKnown_readsAsNone() {
        var payload = TemperJson.mapper()
                                .readString("{\"sha\":\"0cf563192ad5\",\"ref\":null,\"recipe\":\"gate\",\"unit\":null,"
                                            + "\"verdict\":\"PASSED\",\"reason\":null,\"counts\":null,\"supersededBy\":null}",
                                            EventPayload.class)
                                .unwrap();

        assertThat(payload.known()).isEmpty();
        assertThat(TemperJson.mapper()
                             .readString("{\"sha\":null,\"ref\":null,\"recipe\":\"gate\",\"unit\":null,\"verdict\":\"FAILED\",\"reason\":null,"
                                         + "\"counts\":null,\"supersededBy\":null,\"known\":[null,\"KNOWN(#3)\"]}",
                                         EventPayload.class)
                             .unwrap()
                             .known()).as("a null element is dropped [G]").containsExactly("KNOWN(#3)");
        assertThat(TemperJson.mapper().writeAsString(payload).unwrap()).contains("\"known\":[]");
    }
}
