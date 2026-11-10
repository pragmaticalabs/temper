package org.pragmatica.temper.controller.events;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.api.UserPropertyDto;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.pg.WireRecords;


/// The one-line form of an event (spec §8.3), pure:
///
/// ```
/// <kind> <job|-> [VERDICT] [unit=<u>] [sha=<7>] [<recipe>] [testcases=N failed=M] [missing=K] [KNOWN(#n)…] [tests-…="…"…] [superseded-by=J] seq=<seq>
/// finished 4711 FAILED sha=0cf5631 module-test testcases=12 failed=1 seq=88
/// finished 4712 FAILED sha=0cf5631 gate testcases=20786 failed=0 missing=1 seq=91
/// finished 4713 FAILED sha=0cf5631 gate testcases=16234 failed=1 KNOWN(#1679) seq=95
/// ```
///
/// `missing=<n>` is added on `FAILED(missing-classes)` (m1-design §2.5): how many expected classes reported nothing.
/// The record's `KNOWN(#n)` annotations follow the counts (spec §8.3 example, m2-design §4.3); the line carries only
/// those, not the record's other annotations [G].
///
/// The job's user properties and narrowing options follow the annotations as [UserPropertyDto#token]
/// (`tests-skipped="-DskipTests=true"`, value quoted and escaped), on a line that states a verdict (m4-design §5.1), so a
/// green `finished` line with tests skipped or narrowed never reads as tested; [EventFeed] reads them from the job.
///
/// `seq` is always last [P] (the spec says the line carries it but does not place it), so a consumer can drop a
/// repeated line after a crash between print and ack.
public sealed interface EventLines {
    String MISSING_CLASSES = "missing-classes";

    static String render(EventRow event, List<UserPropertyDto> userProperties) {
        var payload = event.payload();

        return Stream.of(Stream.of(WireRecords.eventKind(event.kind()).wire(),
                                   event.job().map(Object::toString).or("-")),
                         payload.verdict().stream(),
                         payload.unit().map(unit -> "unit=" + unit).stream(),
                         payload.sha().map(EventLines::shortSha).stream(),
                         payload.recipe().stream(),
                         counts(payload).stream(),
                         missing(payload).stream(),
                         payload.known().stream(),
                         userProperties.stream().map(UserPropertyDto::token),
                         payload.supersededBy().map(job -> "superseded-by=" + job).stream(),
                         Stream.of("seq=" + event.seq()))
                     .flatMap(part -> part)
                     .collect(Collectors.joining(" "));
    }

    private static String shortSha(String sha) {
        return "sha=" + sha.substring(0,
                                      Math.min(7, sha.length()));
    }

    private static Option<String> counts(EventPayload payload) {
        return payload.counts()
                      .map(EventLines::counts);
    }

    private static Option<String> missing(EventPayload payload) {
        return payload.reason()
                      .filter(MISSING_CLASSES::equals)
                      .flatMap(_ -> payload.counts())
                      .flatMap(Counts::missing)
                      .map(count -> "missing=" + count);
    }

    private static String counts(Counts counts) {
        return "testcases=" + counts.testcases() + " failed=" + counts.failed();
    }

    record unused() implements EventLines {}
}
