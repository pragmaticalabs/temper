package org.pragmatica.temper.worker.unit;

import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.report.ParsedTestcase;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.Message;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// What report collection produced for one unit (m0-design §7.4 "On container exit", step 3).
///
/// - `reportFiles`: files selected (newer than the marker), parsed or not.
/// - `testsAttr`: the sum of `tests=` over the files that parsed (comparison only; never drives a verdict).
/// - `rows`: every testcase of every file that parsed.
/// - `errorReason`: `report` when any selected file failed to parse; the rows parsed so far are still sent, as
///   observed failures. `marker` when the run marker could not be read, so no file could be selected.
public record ReportOutcome(int reportFiles, long testsAttr, List<ParsedTestcase> rows, Option<String> errorReason) {
    public static final String REPORT = "report";
    public static final String MARKER = "marker";

    public ReportOutcome {
        rows = List.copyOf(rows);
    }

    /// Folds the per-file parse results.
    public static ReportOutcome reportOutcome(List<Result<ReportFile>> parsed) {
        var files = parsed.stream().flatMap(Result::stream).toList();

        return new ReportOutcome(parsed.size(),
                                 files.stream().mapToLong(ReportFile::testsAttr).sum(),
                                 files.stream().flatMap(file -> file.testcases()
                                                                    .stream()).toList(),
                                 files.size() == parsed.size()
                                 ? none()
                                 : some(REPORT));
    }

    /// No file could be selected because the marker is unreadable.
    public static final ReportOutcome MARKER_UNREADABLE = new ReportOutcome(0, 0, List.of(), some(MARKER));

    /// The rows as `UnitReport` chunks of at most `Message.MAX_ROWS_PER_CHUNK`; always at least one chunk, and
    /// only the final one has `last = true`. Each carries the unit's `attempt` (protocol v3).
    public List<Message.UnitReport> chunks(UnitRef ref, int attempt) {
        var dtos = rows.stream().map(row -> TestcaseDto.testcaseDto(TestcaseRow.testcaseRow(ref.unit(), row))).toList();
        var count = Math.max(1, (dtos.size() + Message.MAX_ROWS_PER_CHUNK - 1) / Message.MAX_ROWS_PER_CHUNK);

        return IntStream.range(0, count)
                        .mapToObj(index -> chunk(ref, attempt, dtos, index, count))
                        .toList();
    }

    private static Message.UnitReport chunk(UnitRef ref, int attempt, List<TestcaseDto> dtos, int index, int count) {
        var from = index * Message.MAX_ROWS_PER_CHUNK;
        var to = Math.min(dtos.size(), from + Message.MAX_ROWS_PER_CHUNK);

        return new Message.UnitReport(ref.job().value(),
                                      ref.unit().value(),
                                      attempt,
                                      index,
                                      index == count - 1,
                                      List.copyOf(dtos.subList(from, to)));
    }
}
