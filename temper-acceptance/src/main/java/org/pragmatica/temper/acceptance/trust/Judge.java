package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.TestcaseDto;


/// T1 step 4 for one SHA (m1-design §8.1), from a snapshot and a finished job: bb-report on the snapshot, the
/// snapshot's XML and build-log fork-crash lines (R-14), temper's record, rows, manifest and log tail, then [TrustComparison#compare]. The same code
/// serves the live run and a verifier's re-run from the snapshots.
public record Judge(Processes processes, ControllerApi api, Evidence evidence, Path bbReport) {
    /// Lines asked of `GET /jobs/{id}/log` when the evidence tarball is not there (the controller clamps it).
    public static final int LOG_LINES = 100_000;

    public static Judge judge(Processes processes, ControllerApi api, Path bbReport) {
        return new Judge(processes, api, Evidence.evidence(processes), bbReport);
    }

    public Promise<ShaRun.Judged> judged(Snapshot snapshot, String sha, long job) {
        return Promise.all(bbSide(snapshot),
                           snapshot.bbReports().async(),
                           snapshot.bbCrashEvidence().async(),
                           temperSide(job))
                      .flatMap((bb, bbReports, bbCrash, temper) -> withFacts(sha, bb, bbCrash, bbReports, temper));
    }

    private Promise<BbReport> bbSide(Snapshot snapshot) {
        return snapshot.bbReport(processes, bbReport)
                       .flatMap(completed -> BbReportParser.parse(completed.stdoutText(),
                                                                  completed.rc())
                                                           .async());
    }

    /// temper's record, rows, manifest and log tail of `job`.
    public Promise<TemperSide> temperSide(long job) {
        return Promise.all(api.result(job),
                           api.testcases(job),
                           api.manifest(job))
                      .flatMap((result, rows, manifest) -> withLogTail(job, result, rows, manifest));
    }

    private Promise<TemperSide> withLogTail(long job,
                                            ResultRecordDto result,
                                            List<TestcaseDto> rows,
                                            ManifestView manifest) {
        return logTail(job, result).map(tail -> TemperSide.temperSide(result, rows, manifest, tail));
    }

    /// The first unit's `log.tail` from its evidence tarball, else the controller's log route. FER (degrade
    /// forward): a tail that cannot be read at all is empty, so no fork crash is seen and a missing class fails the
    /// criterion: the conservative direction.
    private Promise<String> logTail(long job, ResultRecordDto result) {
        return Evidence.firstTarball(result)
                       .map(tarball -> evidence.member(tarball, Evidence.LOG_TAIL))
                       .or(() -> api.log(job, LOG_LINES))
                       .recover(_ -> "");
    }

    private Promise<ShaRun.Judged> withFacts(String sha,
                                             BbReport bb,
                                             Option<String> bbCrash,
                                             List<ReportFile> bbReports,
                                             TemperSide temper) {
        return Promise.all(fact(temper.result(),
                                Evidence.FACTS_START),
                           fact(temper.result(),
                                Evidence.FACTS_END))
                      .map((start, end) -> ShaRun.Judged.judged(bb,
                                                                temper,
                                                                TrustComparison.compare(sha,
                                                                                        bb,
                                                                                        bbCrash,
                                                                                        temper,
                                                                                        bbReports),
                                                                start,
                                                                end));
    }

    /// FER (degrade forward): facts are context for the report, not part of the criterion; a member that cannot be
    /// read is shown as unavailable with the reason.
    private Promise<String> fact(ResultRecordDto result, String member) {
        return Evidence.firstTarball(result)
                       .map(tarball -> evidence.member(tarball, member))
                       .or(() -> Promise.success("unavailable: no evidence tarball on this host"))
                       .recover(cause -> "unavailable: " + cause.message());
    }
}
