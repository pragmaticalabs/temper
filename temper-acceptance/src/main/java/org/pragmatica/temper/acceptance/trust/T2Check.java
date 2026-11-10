package org.pragmatica.temper.acceptance.trust;

import java.util.stream.Stream;

import org.pragmatica.temper.protocol.api.ResultRecordDto;


/// The T2 crashed-fork assertions (m1-design §8.2, §9.4), pure, shared by the docker-tier `CrashedForkIT` and the
/// live [T2Harness]: the verdict is `FAILED` with reason `missing-classes`; `manifest.missing` names the probe class;
/// no `<testcase>` row names it; some unit's rc is non-zero; the log tail shows the fork-crash line. The control is
/// separate ([#controlHolds]): the parent SHA's Gate is not `missing-classes`.
public record T2Check(boolean failedMissingClasses,
                      boolean probeMissing,
                      boolean noProbeRows,
                      boolean rcNonZero,
                      boolean forkCrashLogged) {
    public static final String FAILED = "FAILED";
    public static final String MISSING_CLASSES = "missing-classes";

    /// `probeClass` is the probe's binary name.
    public static T2Check t2Check(TemperSide crashed, String probeClass) {
        var result = crashed.result();

        return new T2Check(missingClasses(result),
                           crashed.manifest().missing().contains(probeClass),
                           crashed.rows().stream().noneMatch(row -> row.className()
                                                                       .equals(probeClass)),
                           result.units().stream().anyMatch(T2Check::nonZero),
                           TrustComparison.crashEvidence(crashed.logTail()).isPresent());
    }

    private static boolean nonZero(ResultRecordDto.UnitResultDto unit) {
        return unit.rc()
                   .filter(rc -> rc != 0)
                   .isPresent();
    }

    /// The control: a parent Gate that is not `FAILED(missing-classes)`.
    public static boolean controlHolds(ResultRecordDto parent) {
        return ! missingClasses(parent);
    }

    private static boolean missingClasses(ResultRecordDto result) {
        return FAILED.equals(result.verdict()) && result.reason()
                                                        .filter(MISSING_CLASSES::equals)
                                                        .isPresent();
    }

    public boolean passed() {
        return failedMissingClasses
               && probeMissing
               && noProbeRows
               && rcNonZero
               && forkCrashLogged;
    }

    /// One `- <check> ✔/✘` line per assertion.
    public Stream<String> lines() {
        return Stream.of(line("verdict FAILED(missing-classes)", failedMissingClasses),
                         line("manifest.missing names the probe class", probeMissing),
                         line("no <testcase> row for the probe class", noProbeRows),
                         line("a unit rc != 0", rcNonZero),
                         line("the log shows the fork-crash line", forkCrashLogged));
    }

    private static String line(String check, boolean passed) {
        return "- " + check + " " + (passed
                                     ? TrustReport.PASS
                                     : TrustReport.FAIL);
    }
}
