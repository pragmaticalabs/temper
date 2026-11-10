package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// F3 + N1: a harness run never writes over an existing report, which may hold crash explanations a reviewer wrote,
/// nor over another run's evidence: each live run of a date claims its own number, evidence directory and report.
class T1HarnessReportTest {
    private static final String DATE = "2026-10-14";
    static final String PLANTED = "fx.includes.RedProbeTest";
    private static final String OTHER = "org.pragmatica.postgres.CrossTypeConversionTest";

    static T1Args live(Path out) {
        return args(out, List.of());
    }

    static T1Args rerun(Path out, String... more) {
        return args(out, List.of(more));
    }

    private static T1Args args(Path out, List<String> more) {
        var argv = new ArrayList<>(List.of("--controller", "http://127.0.0.1:1", "--out", out.toString(),
                                                     "--date", DATE, "--planted", PLANTED));
        argv.addAll(more);
        return T1Args.t1Args(argv, Map.of("HOME", out.toString()), LocalDate.parse(DATE)).expect("test args");
    }

    /// The re-run command line without the planted flag, with `more` added.
    private static List<String> rerunArgv(Path out, String... more) {
        var argv = new ArrayList<>(List.of("--rerun", "--controller", "http://127.0.0.1:1", "--out", out.toString(), "--date", DATE));
        argv.addAll(List.of(more));
        return argv;
    }

    private static T1Args parsed(List<String> argv, Path out) {
        return T1Args.t1Args(argv, Map.of("HOME", out.toString()), LocalDate.parse(DATE)).expect("test args");
    }

    @Test
    void evidence_isTheDatedDirectory_andTheReportTheDatedName_whenNothingExists(@TempDir Path out) {
        var evidence = T1Harness.evidence(live(out)).expect("claimed");

        assertThat(evidence).isEqualTo(out.resolve("T1-" + DATE));
        assertThat(evidence).isDirectory();
        assertThat(T1Harness.written(live(out), evidence, PLANTED, List.of()).expect("written").report())
            .isEqualTo(out.resolve("T1-" + DATE + ".md"));
    }

    @Test
    void secondLiveRun_ofTheSameDate_getsItsOwnEvidence_andLeavesTheFirstRunsJob(@TempDir Path out) throws Exception {
        var first = T1Harness.evidence(live(out)).expect("first claimed");
        var job = Files.writeString(Files.createDirectory(first.resolve("abc1234")).resolve(T1Steps.JOB_FILE), "41\n");

        var second = T1Harness.evidence(live(out)).expect("second claimed, the first still running");

        assertThat(second).isEqualTo(out.resolve("T1-" + DATE + ".2"));
        assertThat(T1Harness.written(live(out), first, PLANTED, List.of()).expect("first report").report())
            .isEqualTo(out.resolve("T1-" + DATE + ".md"));
        assertThat(T1Harness.written(live(out), second, PLANTED, List.of()).expect("second report").report())
            .isEqualTo(out.resolve("T1-" + DATE + ".2.md"));
        assertThat(T1Harness.evidence(live(out)).expect("third claimed")).isEqualTo(out.resolve("T1-" + DATE + ".3"));
        assertThat(Files.readString(job)).isEqualTo("41\n");
    }

    @Test
    void liveRun_skipsANumberWhoseReportExists_andLeavesItsExplanations(@TempDir Path out) throws Exception {
        var explained = Files.writeString(out.resolve("T1-" + DATE + ".md"), "- **explain** ...: the reviewer's explanation\n");
        Files.writeString(out.resolve("T1-" + DATE + ".2.md"), "an earlier run, its evidence moved aside\n");

        assertThat(T1Harness.evidence(live(out)).expect("claimed")).isEqualTo(out.resolve("T1-" + DATE + ".3"));
        assertThat(Files.readString(explained)).contains("the reviewer's explanation");
    }

    @Test
    void rerun_judgesExactlyTheNamedRun_andWritesBesideIt(@TempDir Path out) throws Exception {
        Files.createDirectories(out.resolve("T1-" + DATE));
        var second = Files.createDirectories(out.resolve("T1-" + DATE + ".2"));

        assertThat(T1Harness.evidence(rerun(out, "--rerun")).expect("run 1")).isEqualTo(out.resolve("T1-" + DATE));
        assertThat(T1Harness.evidence(rerun(out, "--rerun", "--run", "2")).expect("run 2")).isEqualTo(second);
        assertThat(T1Harness.written(rerun(out, "--rerun", "--run", "2"), second, PLANTED, List.of()).expect("re-run report").report())
            .isEqualTo(out.resolve("T1-" + DATE + ".2-rerun.md"));
        assertThat(T1Harness.written(rerun(out, "--rerun", "--run", "2"), second, PLANTED, List.of()).expect("again").report())
            .isEqualTo(out.resolve("T1-" + DATE + ".2-rerun.2.md"));
    }

    /// F1: the live run binds its planted class to its evidence before any SHA is judged.
    @Test
    void liveRun_storesItsPlantedClass_withItsEvidence(@TempDir Path out) throws Exception {
        var evidence = T1Harness.evidence(live(out)).expect("claimed");

        assertThat(T1Harness.planted(live(out), evidence).expect("stored")).isEqualTo(PLANTED);
        assertThat(Files.readString(evidence.resolve(T1Harness.PLANTED))).isEqualTo(PLANTED + "\n");
    }

    /// F1: a re-run judges against the live run's stored class, with `--planted` left out or naming the same class.
    @Test
    void rerun_judgesAgainstTheStoredClass(@TempDir Path out) {
        var evidence = T1Harness.evidence(live(out)).expect("claimed");
        T1Harness.planted(live(out), evidence).expect("stored");

        assertThat(T1Harness.planted(parsed(rerunArgv(out), out), evidence).expect("no flag")).isEqualTo(PLANTED);
        assertThat(T1Harness.planted(rerun(out, "--rerun"), evidence).expect("the same flag")).isEqualTo(PLANTED);
    }

    /// F1, the verifier's probe: the live run planted `PLANTED`; a re-run naming the class that happened to fail is
    /// refused as a usage error (exit 64) and writes no report, so it cannot turn the live run's verdict around.
    @Test
    void rerun_withAnotherPlantedClass_isRefused_exit64_andWritesNoReport(@TempDir Path out) throws Exception {
        var evidence = T1Harness.evidence(live(out)).expect("claimed");
        T1Harness.planted(live(out), evidence).expect("stored");
        var token = Files.writeString(out.resolve("token"), "t\n");

        var refused = T1Harness.planted(parsed(rerunArgv(out, "--planted", OTHER), out), evidence);
        var exit = T1Harness.run(rerunArgv(out, "--planted", OTHER, "--token-file", token.toString()), Map.of("HOME", out.toString()));

        assertThat(refused.<Boolean>fold(cause -> cause instanceof TrustError.Usage, _ -> false)).as(refused.toString()).isTrue();
        assertThat(exit).isEqualTo(T1Harness.EXIT_USAGE);
        assertThat(out.resolve("T1-" + DATE + "-rerun.md")).doesNotExist();
    }

    /// F1: evidence with no stored class (a run before F1, or a hand-made directory) is not judged against a class
    /// chosen now: the re-run fails before any report.
    @Test
    void rerun_ofEvidenceWithoutAStoredClass_fails(@TempDir Path out) throws Exception {
        var evidence = Files.createDirectories(out.resolve("T1-" + DATE));

        assertThat(T1Harness.planted(rerun(out, "--rerun"), evidence).isFailure()).isTrue();
    }
}
