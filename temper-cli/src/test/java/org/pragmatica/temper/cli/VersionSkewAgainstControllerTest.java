package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.cli.config.CliVersion;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.api.RefusalMatcherDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.cli.FakeController.Reply;

/// D-6, whole invocations against the fake controller's `Temper-Version` header: a judged CLI refuses a controller of
/// another judged build with exit 13 naming both versions (a `-dirty` build counts as judged); an unjudged side, or a
/// controller without the header, only warns, once.
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VersionSkewAgainstControllerTest {
    private static final String JUDGED = "0.1.0-SNAPSHOT+111111111111";
    private static final String OTHER = "0.1.0-SNAPSHOT+222222222222";

    @TempDir
    Path home;

    private FakeController controller;
    private CliHarness cli;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN);
        controller.on("GET /refusals", Reply.json(200, "[]"));
        cli = CliHarness.against(home, controller);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    @Test
    void sameBuild_passesSilently() {
        var result = cli.run("refusal", "list");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.err()).isEmpty();
    }

    @Test
    void judgedMismatch_isRefusedWithExit13_namingBothVersions() {
        controller.temperVersion(Optional.of(OTHER));

        var result = cli.version(JUDGED)
                        .run("refusal", "list");

        assertThat(result.exit()).isEqualTo(ExitCode.VERSION_SKEW.code());
        assertThat(result.err()).contains(JUDGED, OTHER);
        assertThat(result.out()).isEmpty();
    }

    /// A `-dirty` build names its commit, so it is judged: dirty against judged refuses (it must not be read as unjudged).
    @Test
    void dirtyAgainstJudged_isRefused_inBothDirections() {
        controller.temperVersion(Optional.of(OTHER + "-dirty"));

        var dirtyController = cli.version(JUDGED)
                                 .run("refusal", "list");

        assertThat(dirtyController.exit()).isEqualTo(ExitCode.VERSION_SKEW.code());

        controller.temperVersion(Optional.of(OTHER));

        var dirtyCli = cli.version(JUDGED + "-dirty")
                          .run("refusal", "list");

        assertThat(dirtyCli.exit()).isEqualTo(ExitCode.VERSION_SKEW.code());
        assertThat(dirtyCli.err()).contains(JUDGED + "-dirty", OTHER);
    }

    /// The same commit, one side built clean and one dirty, is two builds.
    @Test
    void cleanAgainstDirtyOfTheSameCommit_isRefused() {
        controller.temperVersion(Optional.of(JUDGED + "-dirty"));

        assertThat(cli.version(JUDGED)
                      .run("refusal", "list")
                      .exit()).isEqualTo(ExitCode.VERSION_SKEW.code());
    }

    @Test
    void unjudgedController_warnsOnce_andAnswers() {
        controller.temperVersion(Optional.of("0.1.0-SNAPSHOT+unjudged"));

        var result = cli.version(JUDGED)
                        .run("refusal", "list");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.err()).contains("warning", JUDGED, "0.1.0-SNAPSHOT+unjudged");
        assertThat(result.err()
                         .lines()
                         .count()).isEqualTo(1);
    }

    @Test
    void unjudgedCli_warns_andAnswers() {
        controller.temperVersion(Optional.of(OTHER));

        var result = cli.version("0.1.0-SNAPSHOT+unjudged")
                        .run("refusal", "list");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.err()).contains("warning", OTHER);
    }

    /// G1, transition rule: a controller without the header predates build identity; the answer passes with exactly
    /// the ruled warning. When the rule becomes a refusal, this test flips.
    @Test
    void controllerWithoutTheHeader_warnsPredates_andAnswers() {
        controller.temperVersion(Optional.empty());

        var result = cli.version(JUDGED)
                        .run("refusal", "list");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.err()
                         .lines()).containsExactly("temper: controller predates build identity; skew not checked");
    }

    /// G2, transition rule: a controller version without `+` (before D-6 the bare `project.version`) predates build
    /// identity; the same warning. When the rule becomes a refusal, this test flips.
    @Test
    void controllerVersionWithoutPlus_warnsPredates_andAnswers() {
        controller.temperVersion(Optional.of("0.1.0-SNAPSHOT"));

        var result = cli.version(JUDGED)
                        .run("refusal", "list");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.err()
                         .lines()).containsExactly("temper: controller predates build identity; skew not checked");
    }

    /// M1: the CLI names its build on every request, reads and writes alike, so the controller can refuse a skewed
    /// write before it acts.
    @Test
    void everyRequest_carriesTheCliVersionHeader() {
        controller.temperVersion(Optional.of(JUDGED))
                  .onJson("POST /refusals/R-9/expire",
                          Fixtures.refusal("R-9",
                                           new RefusalMatcherDto.ItTestRequired("forge-tests"),
                                           some(Instant.parse("2026-10-13T12:00:00Z"))));

        var listed = cli.version(JUDGED)
                        .run("refusal", "list");
        var expired = cli.version(JUDGED)
                         .run("refusal", "expire", "R-9");

        assertThat(listed.exit()).as(listed.err())
                                 .isZero();
        assertThat(expired.exit()).as(expired.err())
                                  .isZero();
        assertThat(controller.received()).extracting(FakeController.Received::method)
                                         .containsExactly("GET", "POST");
        assertThat(controller.sentVersions()).containsExactly(Optional.of(JUDGED), Optional.of(JUDGED));
    }

    /// M1: the controller's 412 (a skewed CLI's write, refused before it acted) is exit 13. The 412 here carries no
    /// version header, and then this CLI's own version, so only the status mapping can give 13 (the header comparison
    /// alone would pass the answer).
    @Test
    void refusedWrite412_isExit13_fromTheStatusAlone() {
        controller.temperVersion(Optional.empty())
                  .on("POST /refusals/R-9/expire",
                      Reply.json(412, "{\"error\":\"version skew\"}"));

        var headerless = cli.version(JUDGED)
                            .run("refusal", "expire", "R-9");

        assertThat(headerless.exit()).isEqualTo(ExitCode.VERSION_SKEW.code());
        assertThat(headerless.err()).contains(JUDGED);
        assertThat(headerless.out()).isEmpty();

        controller.temperVersion(Optional.of(JUDGED));

        var matching = cli.version(JUDGED)
                          .run("refusal", "expire", "R-9");

        assertThat(matching.exit()).isEqualTo(ExitCode.VERSION_SKEW.code());
        assertThat(matching.out()).isEmpty();
    }

    /// `alarm-check` reads the controller through the same check: a skewed controller is not evaluated, and says why.
    /// The deliberate exception to exit 13 (lead's ruling, D-6 verify vB): a skew is one more way the alarm is blind,
    /// so `check-blind` FIRES (an issue, which nobody can miss) and the exit is 64, as for every undetermined check;
    /// an exit 13 from a cron could go unnoticed.
    @Test
    void alarmCheck_judgedMismatch_isCheckBlindFiring_exit64_notExit13() throws IOException {
        controller.temperVersion(Optional.of(OTHER))
                  .onJson("GET /maintenance", Fixtures.maintenance(Fixtures.AT.minusSeconds(3600), true, some(Fixtures.AT.minusSeconds(3600))))
                  .onJson("GET /hosts", Fixtures.hosts("UP"));
        Files.writeString(cli.home()
                             .resolve(".config/temper/config"),
                          "[alarm]\n" + Fixtures.dumpDir(cli.home()) + "host = \"bigboy\"\nmin-free-gib = 1\npaths = [\"" + cli.home()
                          + "\"]\nsink = \"stdout\"\n");

        var result = cli.version(JUDGED)
                        .run("alarm-check");

        assertThat(result.err()).contains("does not match", JUDGED, OTHER);
        assertThat(result.exit()).isEqualTo(ExitCode.USAGE.code());
        assertThat(result.out()).anyMatch(line -> line.startsWith("alarm check-blind FIRING printed: not evaluated: ")
                                                  && line.contains("does not match"));
    }

    /// The jar's own version is this build's: `<project.version>+<judge12>`, or `+unjudged` for a plain build
    /// (surefire passes both properties).
    @Test
    void bundled_isTheBuildVersionOfThisBuild() {
        var judge = System.getProperty("temper.judge");
        var suffix = judge.equals("unjudged")
                     ? "unjudged"
                     : judge.substring(0, 12) + (judge.endsWith("-dirty") ? "-dirty" : "");

        assertThat(CliVersion.bundled()
                             .unwrap()).isEqualTo(System.getProperty("project.version") + "+" + suffix);
    }
}
