package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.ClaimResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.FakeController.Reply;

/// Review #4: a lease file lives exactly as long as its lease. Release, wait and a park under the lease delete it; a
/// `409 lease-lost` (to a lease call or `submit --task`) deletes it; a heartbeat keeps it; a new claim replaces the task's older files; and a maintainer park goes
/// out with the bearer token even while a lease file for the task is there, `--lease` alone parking under the lease.
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TaskLeaseFilesTest {
    private static final String VIEW = "{\"id\":42,\"slug\":\"s\",\"title\":\"T\",\"issues\":[],\"brief\":{\"path\":\"b.md\"},\"repo\":\"a/b\","
                                       + "\"baseRef\":\"main\",\"branch\":\"agent/s\",\"critical\":false,\"priority\":0,\"after\":[],"
                                       + "\"stage\":\"NEEDS_RULING\",\"status\":\"HELD\",\"raisedFrom\":\"CODE\",\"round\":0,\"roundCap\":3,"
                                       + "\"ciRounds\":0,\"visit\":1,\"visitExpiries\":0,\"visitFailures\":0,\"epoch\":8,\"waitJobs\":[],"
                                       + "\"waitCi\":false,\"createdBy\":\"cto\",\"createdAt\":\"2026-10-20T10:00:00Z\"}";
    private static final String REQUEST = "{\"kind\":\"design\",\"question\":\"Q\",\"options\":[{\"id\":\"a\",\"text\":\"A\"},"
                                          + "{\"id\":\"b\",\"text\":\"B\"}],\"recommendation\":{\"option\":\"a\",\"why\":\"w\"},"
                                          + "\"blocked\":{\"tasks\":[],\"text\":\"none\"}}";
    private static final String SUBMIT_UNDER_LEASE = "submit module-test --pl core --test Foo* --sha " + Fixtures.SHA + " --task 42";

    @TempDir
    Path home;

    private FakeController controller;
    private CliHarness cli;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN);
        cli = CliHarness.against(home, controller);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private Path leaseFile(long epoch) throws IOException {
        var dir = Files.createDirectories(home.resolve(".config/temper/leases"));
        var file = Files.writeString(dir.resolve("42-" + epoch), "lease-token-" + epoch + "\n");

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private String lastAuthorization() {
        return controller.received().getLast().authorization();
    }

    @Test
    void release_deletesTheLeaseFile() throws IOException {
        var file = leaseFile(7);

        controller.on("POST /tasks/42/release", Reply.json(204, ""));
        assertThat(cli.run("task", "release", "42", "--reason", "crash").exit()).isZero();
        assertThat(file).doesNotExist();
    }

    @Test
    void wait_deletesTheLeaseFile() throws IOException {
        var file = leaseFile(7);

        controller.on("POST /tasks/42/wait", Reply.json(204, ""));
        assertThat(cli.run("task", "wait", "42", "--job", "9").exit()).isZero();
        assertThat(file).doesNotExist();
    }

    @Test
    void heartbeat_keepsTheLeaseFile_butALostLeaseDeletesIt() throws IOException {
        var file = leaseFile(7);

        controller.on("POST /tasks/42/heartbeat", Reply.json(204, ""), Reply.json(409, "{\"error\":\"lease-lost\"}"));
        assertThat(cli.run("task", "heartbeat", "42").exit()).isZero();
        assertThat(file).exists();
        assertThat(cli.run("task", "heartbeat", "42").exit()).isEqualTo(12);
        assertThat(file).doesNotExist();
    }

    /// Review D3: `submit --task` under a lost lease (`409 lease-lost`) deletes the lease file; an accepted one keeps it.
    @Test
    void submitUnderTheLease_keepsTheLeaseFile_butALostLeaseDeletesIt() throws IOException {
        var file = leaseFile(7);

        controller.onJson("POST /jobs", Fixtures.submitted(41, JobState.QUEUED, false))
                  .onJson("GET /jobs/41", Fixtures.job(41, JobState.QUEUED));
        assertThat(cli.run(SUBMIT_UNDER_LEASE.split(" ")).exit()).isZero();
        assertThat(controller.received().stream().filter(request -> request.target().endsWith("/jobs")).map(FakeController.Received::authorization))
            .containsExactly("Lease lease-token-7");
        assertThat(file).exists();

        controller.on("POST /jobs", Reply.json(409, "{\"error\":\"lease-lost\"}"));
        assertThat(cli.run(SUBMIT_UNDER_LEASE.split(" ")).exit()).isEqualTo(12);
        assertThat(file).doesNotExist();
    }

    @Test
    void ctoPark_usesTheBearerToken_evenWithALeaseFileThere() throws IOException {
        var file = leaseFile(7);
        var request = Files.writeString(home.resolve("request.json"), REQUEST);

        controller.on("POST /tasks/42/park", Reply.json(200, VIEW));
        assertThat(cli.run("task", "park", "42", request.toString()).exit()).isZero();
        assertThat(lastAuthorization()).isEqualTo("Bearer " + CliHarness.TOKEN);
        assertThat(file).exists();
    }

    @Test
    void parkUnderTheLease_presentsIt_andDeletesTheFile() throws IOException {
        var file = leaseFile(7);
        var request = Files.writeString(home.resolve("request.json"), REQUEST);

        controller.on("POST /tasks/42/park", Reply.json(200, VIEW));
        assertThat(cli.run("task", "park", "42", request.toString(), "--lease").exit()).isZero();
        assertThat(lastAuthorization()).isEqualTo("Lease lease-token-7");
        assertThat(controller.received().getLast().body()).contains("\"epoch\":7");
        assertThat(file).doesNotExist();
    }

    /// Verifier G3: `rule --extra-rounds K` reaches the request body, with the option, the text and the `know:` SHA.
    @Test
    void rule_sendsExtraRoundsAndKnow() {
        controller.on("POST /tasks/42/rule", Reply.json(200, VIEW));
        assertThat(cli.run("task", "rule", "42", "--option", "retry", "--text", "go on", "--extra-rounds", "2", "--know", "f".repeat(40))
                      .exit()).isZero();
        assertThat(controller.received().getLast().body()).contains("\"extraRounds\":2")
                                                          .contains("\"option\":\"retry\"")
                                                          .contains("\"text\":\"go on\"")
                                                          .contains("\"know\":\"" + "f".repeat(40) + "\"");
    }

    @Test
    void claim_replacesTheTasksOlderLeaseFiles() throws IOException {
        var older = leaseFile(3);
        var claim = new ClaimResponse(42, "s", "CODE", 1, 7, "lease-token-42", "0cf563192a0cf563192a0cf563192a0cf563192a", 0,
                                      Instant.parse("2026-10-20T10:30:00Z"), Instant.parse("2026-10-20T16:00:00Z"), "a/b",
                                      "file:///nowhere", List.of(), List.of(), List.of(), List.of());

        controller.on("POST /tasks/42/claim", Reply.json(200, controller.write(claim)));
        assertThat(cli.run("task", "claim", "42").exit()).isZero();
        assertThat(older).doesNotExist();
        assertThat(Files.readString(home.resolve(".config/temper/leases/42-7")).strip()).isEqualTo("lease-token-42");
    }
}
