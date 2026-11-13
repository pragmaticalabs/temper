package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.temper.testsupport.HermeticProcess;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.ReadPointStamp;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.ReadPointStampDto;
import org.pragmatica.temper.protocol.json.TemperJson;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// m3-design §11.5 `ReadPointStampIT` (R-M3-24, lesson (d)): `temper task claim --checkout DIR` against a git fixture.
/// A non-empty `DIR` is refused before any claim; an absent one becomes a fresh clone detached at the issued read point,
/// with the stamp `{task, epoch, readPoint, at}` written beside it, and the token kept to the lease file. The stamp
/// read back passes the evidence check at its own epoch and read point; a stamp of another epoch is
/// `read-point-stale`, no stamp `read-point-unstamped`.
///
/// Probe: accept a stamp of any epoch (`ReadPointStamp` ignores the epoch): `stampOfAnotherEpoch_isStale` goes red.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ReadPointStampIT {
    @TempDir
    Path home;

    @TempDir
    Path work;

    private FakeController controller;
    private CliHarness cli;
    private Path repo;
    private String first;
    private String second;

    @BeforeEach
    void start() throws Exception {
        controller = FakeController.start(CliHarness.TOKEN);
        cli = CliHarness.against(home, controller);
        repo = Files.createDirectories(work.resolve("origin"));
        git(repo, "init", "--quiet", "--initial-branch=main");
        git(repo, "config", "user.email", "t@example.org");
        git(repo, "config", "user.name", "t");
        first = commit("one");
        second = commit("two");
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    @Test
    void checkout_intoANonEmptyDirectory_isRefused_beforeAnyClaim() throws Exception {
        var dir = Files.createDirectories(work.resolve("busy"));

        Files.writeString(dir.resolve("left.txt"), "x");
        var run = cli.run("task", "claim", "42", "--checkout", dir.toString());

        assertThat(run.exit()).isEqualTo(64);
        assertThat(controller.received()).noneMatch(request -> request.target()
                                                                      .contains("/claim"));
    }

    @Test
    void checkout_clonesAtTheReadPoint_stampsIt_andKeepsTheTokenToTheLeaseFile() throws Exception {
        var dir = work.resolve("clone");
        var run = claimInto(dir);

        assertThat(run.exit()).as(run.err()).isEqualTo(0);
        assertThat(run.out()).containsExactly("task 42 CODE LEASED epoch=7 readPoint=" + first.substring(0, 7)
                                             + " deadline=2026-10-20T16:00:00Z",
                                              "stamp 42 epoch=7 readPoint=" + first + " dir=" + dir);
        assertThat(git(dir, "rev-parse", "HEAD")).isEqualTo(first);
        // Review #10: the stamp is excluded in the clone, so its work tree is clean and `git add -A` would not take it.
        assertThat(git(dir, "status", "--porcelain")).isEmpty();
        assertThat(stamp(dir)).isEqualTo(ReadPointStamp.readPointStamp(42,
                                                                       7,
                                                                       Sha.sha(first).unwrap(),
                                                                       stamp(dir).at()));
        assertThat(String.join("\n", run.out()) + run.err()).doesNotContain("lease-token-42");
        assertThat(Files.readString(home.resolve(".config/temper/leases/42-7")).strip()).isEqualTo("lease-token-42");
    }

    @Test
    void stampAtItsOwnEpochAndReadPoint_passes() throws Exception {
        var dir = work.resolve("own");

        claimInto(dir);
        assertThat(ReadPointStamp.refusals(7,
                                           Sha.sha(first).unwrap(),
                                           some(stamp(dir)),
                                           some(Sha.sha(git(dir, "rev-parse", "HEAD")).unwrap()))).isEmpty();
    }

    @Test
    void stampOfAnotherEpoch_isStale() throws Exception {
        var dir = work.resolve("old");

        claimInto(dir);
        assertThat(ReadPointStamp.refusals(8,
                                           Sha.sha(first).unwrap(),
                                           some(stamp(dir)),
                                           some(Sha.sha(first).unwrap()))).containsExactly(ReadPointStamp.STALE);
    }

    @Test
    void checkoutMovedOffTheReadPoint_isStale() throws Exception {
        var dir = work.resolve("moved");

        claimInto(dir);
        git(dir, "checkout", "--quiet", "--detach", second);
        assertThat(ReadPointStamp.refusals(7,
                                           Sha.sha(first).unwrap(),
                                           some(stamp(dir)),
                                           some(Sha.sha(git(dir, "rev-parse", "HEAD")).unwrap()))).containsExactly(ReadPointStamp.STALE);
    }

    @Test
    void noStamp_isUnstamped() {
        assertThat(ReadPointStamp.refusals(7,
                                           Sha.sha(first).unwrap(),
                                           none(),
                                           some(Sha.sha(first).unwrap()))).containsExactly(ReadPointStamp.UNSTAMPED);
    }

    // ---- fixture -----------------------------------------------------------------------------------------------------
    private CliHarness.Run claimInto(Path dir) {
        controller.on("POST /tasks/42/claim",
                      FakeController.Reply.json(200, controller.write(claim(first))));

        return cli.run("task", "claim", "42", "--checkout", dir.toString());
    }

    private ClaimResponse claim(String readPoint) {
        return new ClaimResponse(42,
                                 "s",
                                 "CODE",
                                 1,
                                 7,
                                 "lease-token-42",
                                 readPoint,
                                 0,
                                 Instant.parse("2026-10-20T10:30:00Z"),
                                 Instant.parse("2026-10-20T16:00:00Z"),
                                 "a/b",
                                 repo.toUri().toString(),
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 null,
                                 List.of());
    }

    private static ReadPointStamp stamp(Path dir) throws IOException {
        return TemperJson.mapper()
                         .readString(Files.readString(dir.resolve(ReadPointStamp.FILE)),
                                     ReadPointStampDto.class)
                         .flatMap(ReadPointStampDto::toDomain)
                         .unwrap();
    }

    private String commit(String content) throws Exception {
        Files.writeString(repo.resolve("file.txt"), content);
        git(repo, "add", "file.txt");
        git(repo, "commit", "--quiet", "-m", content);

        return git(repo, "rev-parse", "HEAD");
    }

    private static String git(Path dir, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));

        command.addAll(List.of(args));
        var process = HermeticProcess.builder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8).strip();

        assertThat(process.waitFor()).as(String.join(" ", command) + ": " + output).isZero();

        return output;
    }
}
