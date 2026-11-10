package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/build-dist.sh` (m2-design §1.7, ruling R-M2-10), hermetic: a copy of the script in a temporary git repository,
/// a fake `mvn` on PATH that records its arguments. The judge is HEAD, `HEAD-dirty` for a tree with changes (an
/// untracked file counts, the ignored `target/` and `.m2-local/` do not), passed as the LAST argument so that no
/// definition of the user's can win over it (Maven keeps the last one), and a hand-typed judge in any spelling is
/// refused before Maven runs. The three real builds that read `judge=` out of the jars are the evidence of the PR.
class BuildDistIT {
    private static final Path SCRIPT = Path.of("..", "ops", "build-dist.sh").toAbsolutePath().normalize();
    private static final Path GITIGNORE = Path.of("..", ".gitignore").toAbsolutePath().normalize();

    @TempDir
    Path dir;
    private Path repo;
    private Path args;
    private String head;

    record Outcome(int rc, String output) {}

    @BeforeEach
    void repository() throws Exception {
        repo = Files.createDirectories(dir.resolve("repo"));
        Files.createDirectories(repo.resolve("ops"));
        Files.copy(SCRIPT, repo.resolve("ops/build-dist.sh"));
        Files.copy(GITIGNORE, repo.resolve(".gitignore"));
        git("init", "-q", "-b", "main");
        git("config", "user.email", "t@t");
        git("config", "user.name", "t");
        git("add", ".");
        git("commit", "-q", "-m", "one");
        head = git("rev-parse", "HEAD");
        args = dir.resolve("mvn-args");
        var bin = Files.createDirectories(dir.resolve("bin"));
        var mvn = Files.writeString(bin.resolve("mvn"), "#!/bin/sh\nfor a in \"$@\"; do echo \"$a\"; done > '" + args + "'\n");

        Files.setPosixFilePermissions(mvn, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void cleanTree_judgeIsHead_passedLast_afterTheGivenArguments() throws Exception {
        var outcome = buildDist("install", "-DskipTests", "-Dtemper.other=1");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("build-dist: judge=" + head);
        assertThat(mvnArgs()).containsExactly("-B", "install", "-DskipTests", "-Dtemper.other=1", "-Dtemper.judge=" + head);
    }

    @Test
    void noArguments_buildsInstall() throws Exception {
        assertThat(buildDist().rc()).isZero();
        assertThat(mvnArgs()).containsExactly("-B", "install", "-Dtemper.judge=" + head);
    }

    @Test
    void untrackedFile_makesTheJudgeDirty() throws Exception {
        Files.writeString(repo.resolve("probe-touch.txt"), "x\n");

        assertThat(buildDist("install").rc()).isZero();
        assertThat(mvnArgs()).last().isEqualTo("-Dtemper.judge=" + head + "-dirty");
    }

    @Test
    void trackedChange_makesTheJudgeDirty() throws Exception {
        Files.writeString(repo.resolve(".gitignore"), "changed\n", StandardCharsets.UTF_8);

        assertThat(buildDist("install").rc()).isZero();
        assertThat(mvnArgs()).last().isEqualTo("-Dtemper.judge=" + head + "-dirty");
    }

    /// `target/` and `.m2-local/` are ignored (the repository's own `.gitignore`), so a build's own output and the
    /// worktree's Maven repository never make a judge dirty.
    @Test
    void ignoredBuildOutput_keepsTheJudgeClean() throws Exception {
        Files.writeString(Files.createDirectories(repo.resolve("target")).resolve("x.jar"), "x");
        Files.writeString(Files.createDirectories(repo.resolve(".m2-local/org")).resolve("y.pom"), "y");

        assertThat(buildDist("install").rc()).isZero();
        assertThat(mvnArgs()).last().isEqualTo("-Dtemper.judge=" + head);
    }

    /// Every spelling of a hand-typed judge Maven accepts is refused (rc 2) before Maven runs.
    @ParameterizedTest
    @ValueSource(strings = {"-Dtemper.judge=0123456789abcdef0123456789abcdef01234567", "-Dtemper.judge",
                            "-D|temper.judge=0123456789abcdef0123456789abcdef01234567",
                            "--define=temper.judge=0123456789abcdef0123456789abcdef01234567",
                            "--define|temper.judge=0123456789abcdef0123456789abcdef01234567"})
    void handTypedJudge_isRefused_beforeMaven(String spelled) throws Exception {
        var given = new ArrayList<>(List.of("install"));

        given.addAll(List.of(spelled.split("\\|")));
        var outcome = buildDist(given.toArray(String[]::new));

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused").contains("the judge comes from git");
        assertThat(args).as("maven never ran").doesNotExist();
    }

    private Outcome buildDist(String... given) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("bash", repo.resolve("ops/build-dist.sh").toString()));

        command.addAll(List.of(given));
        var builder = HermeticProcess.builder(command).redirectErrorStream(true);
        builder.environment().put("PATH", dir.resolve("bin") + ":" + builder.environment().get("PATH"));
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        return new Outcome(process.waitFor(), output);
    }

    private List<String> mvnArgs() throws IOException {
        return Files.readAllLines(args);
    }

    private String git(String... argv) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git"));

        command.addAll(List.of(argv));
        var gitBuilder = HermeticProcess.builder(command).directory(repo.toFile()).redirectErrorStream(true);
        var process = gitBuilder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();

        assertThat(process.waitFor()).as(String.join(" ", command) + ": " + output).isZero();
        return output;
    }
}
