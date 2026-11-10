package org.pragmatica.temper.acceptance.trust;

import java.util.List;

import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.TestcaseDto;


/// temper's side of one SHA (m1-design §8.1): the job's result record, its testcase rows and its manifest view
/// (`GET /jobs/{id}/result`, `/testcases`, `/manifest`), plus the unit's log tail, read from the small evidence
/// tarball (`log.tail`), where a fork crash shows as `The forked VM terminated`. The log tail is a WP7 addition to
/// the design's record: the crash check of step 4 needs it and the design names no other carrier.
public record TemperSide(ResultRecordDto result, List<TestcaseDto> rows, ManifestView manifest, String logTail) {
    public TemperSide {
        rows = List.copyOf(rows);
    }

    public static TemperSide temperSide(ResultRecordDto result,
                                        List<TestcaseDto> rows,
                                        ManifestView manifest,
                                        String logTail) {
        return new TemperSide(result, rows, manifest, logTail);
    }
}
