package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.pragmatica.temper.cli.config.CliVersion;
import org.pragmatica.temper.protocol.api.CheckpointDto;
import org.pragmatica.temper.protocol.api.ClaimResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.temper.cli.FakeController.Reply;
import static org.assertj.core.api.Assertions.assertThat;


/// Lane registry WP3: `task checkpoint`, `task observe`, the claim's checkpoint and warnings, and the lane columns of
/// `task list`/`show`, against the scripted controller. Exit codes: 0 stored; 12 lease lost (409); 14 body over 16 KB
/// (422 checkpoint-too-large); 15 sha off the branch (422 pushed-sha-not-on-branch); 16 mirror unavailable (503); 2 any
/// other 422; 64 a bad sha, a bad file or negative counts, with no request sent.
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TaskLaneVerbsTest {
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";

    private static final String STORED = "{\"seq\":3,\"epoch\":7,\"pushedSha\":\"" + SHA
                                       + "\",\"checklist\":[],\"readMap\":[],"
                                       + "\"note\":\"n\",\"at\":\"2026-11-13T10:00:00Z\"}";

    private static final String VIEW_HEAD = "{\"id\":42,\"slug\":\"s\",\"title\":\"T\",\"issues\":[],\"brief\":{\"path\":\"b.md\"},"
                                          + "\"repo\":\"a/b\",\"baseRef\":\"main\",\"branch\":\"agent/s\",\"critical\":false,\"priority\":0,"
                                          + "\"after\":[],\"stage\":\"CODE\",\"status\":\"LEASED\",\"round\":0,\"roundCap\":3,\"ciRounds\":0,"
                                          + "\"visit\":1,\"visitExpiries\":0,\"visitFailures\":0,\"epoch\":7,\"leaseHolder\":\"wp3a\","
                                          + "\"waitJobs\":[],\"waitCi\":false,\"createdBy\":\"cto\",\"createdAt\":\"2026-10-20T10:00:00Z\"";

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

    private List<FakeController.Received> posted(String suffix) {
        return controller.received()
                         .stream()
                         .filter(request -> request.target()
                                                   .endsWith(suffix))
                         .toList();
    }

    private CliHarness.Run checkpoint(String... extra) {
        var args = new java.util.ArrayList<>(List.of("task", "checkpoint", "42", "--pushed-sha", SHA));

        args.addAll(List.of(extra));

        return cli.run(args.toArray(String[]::new));
    }

    // ---- checkpoint ----------------------------------------------------------------------------------------------
    /// 201 is success (a controller answers 201 to every accepted checkpoint); probe: treat 201 as a failure -> red.
    @Test
    void checkpoint_201IsSuccess_underTheLease_withTheFilesAndTheNote() throws IOException {
        leaseFile(7);
        var checklist = Files.writeString(home.resolve("checklist.json"),
                                          "[{\"item\":\"wire route\",\"done\":true},{\"item\":\"docs\",\"done\":false}]");
        var readMap = Files.writeString(home.resolve("readmap.json"), "[{\"path\":\"a/B.java\",\"lines\":\"10-40\"}]");

        controller.on("POST /tasks/42/checkpoint", Reply.json(201, STORED));
        var run = checkpoint("--checklist-file",
                             checklist.toString(),
                             "--read-map-file",
                             readMap.toString(),
                             "--note",
                             "next: docs");

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly("task 42 CHECKPOINT epoch=7 seq=3 pushedSha=0cf5631");
        var sent = posted("/tasks/42/checkpoint").getLast();

        assertThat(sent.authorization()).isEqualTo("Lease lease-token-7");
        assertThat(sent.body()).contains("\"epoch\":7")
                  .contains("\"pushedSha\":\"" + SHA + "\"")
                  .contains("\"item\":\"wire route\",\"done\":true")
                  .contains("\"path\":\"a/B.java\",\"lines\":\"10-40\"")
                  .contains("\"note\":\"next: docs\"");
    }

    @Test
    void checkpoint_200IsOutsideTheContract() throws IOException {
        leaseFile(7);
        controller.on("POST /tasks/42/checkpoint", Reply.json(200, STORED));
        assertThat(checkpoint().exit()).isEqualTo(69);
    }

    @Test
    void checkpoint_leaseLost_is12_andDeletesTheLeaseFile() throws IOException {
        var file = leaseFile(7);

        controller.on("POST /tasks/42/checkpoint", Reply.json(409, "{\"error\":\"lease-lost\"}"));
        assertThat(checkpoint().exit()).isEqualTo(12);
        assertThat(file).doesNotExist();
    }

    @Test
    void checkpoint_tooLarge_is14() throws IOException {
        leaseFile(7);
        controller.on("POST /tasks/42/checkpoint", Reply.json(422, "{\"error\":\"checkpoint-too-large\"}"));
        var run = checkpoint();

        assertThat(run.exit()).isEqualTo(14);
        assertThat(run.err()).contains("checkpoint too large");
    }

    @Test
    void checkpoint_shaOffTheBranch_is15() throws IOException {
        leaseFile(7);
        controller.on("POST /tasks/42/checkpoint", Reply.json(422, "{\"error\":\"pushed-sha-not-on-branch\"}"));
        var run = checkpoint();

        assertThat(run.exit()).isEqualTo(15);
        assertThat(run.err()).contains("push it first");
    }

    @Test
    void checkpoint_mirrorUnavailable_is16() throws IOException {
        var file = leaseFile(7);

        controller.on("POST /tasks/42/checkpoint", Reply.json(503, "{\"error\":\"git unavailable\"}"));
        assertThat(checkpoint().exit()).isEqualTo(16);
        assertThat(file).exists();
    }

    @Test
    void checkpoint_anotherRefusal_isTheGeneric2() throws IOException {
        leaseFile(7);
        controller.on("POST /tasks/42/checkpoint", Reply.json(422, "{\"error\":\"something-else\"}"));
        assertThat(checkpoint().exit()).isEqualTo(2);
    }

    @Test
    void checkpoint_aShortShaAnAbsentFileOrNoLease_is64_andSendsNothing() throws IOException {
        leaseFile(7);
        assertThat(cli.run("task", "checkpoint", "42", "--pushed-sha", "0cf5631").exit()).isEqualTo(64);
        assertThat(checkpoint("--checklist-file",
                              home.resolve("nope.json").toString()).exit()).isEqualTo(64);
        Files.writeString(home.resolve("bad.json"), "{not an array");
        assertThat(checkpoint("--read-map-file",
                              home.resolve("bad.json").toString()).exit()).isEqualTo(64);
        assertThat(cli.run("task", "checkpoint", "42").exit()).isEqualTo(64);
        Files.delete(home.resolve(".config/temper/leases/42-7"));
        assertThat(checkpoint().exit()).isEqualTo(64);
        assertThat(posted("/tasks/42/checkpoint")).isEmpty();
    }

    // ---- observe -------------------------------------------------------------------------------------------------
    /// The parent's measurement goes out with the FULL token and the CLI's version header, never the lease token even when a
    /// lease file is there. Probe: send it under the lease -> red.
    @Test
    void observe_usesTheFullToken_notTheLease_andSendsTheVersionHeader() throws IOException {
        leaseFile(7);
        controller.on("POST /tasks/42/observe", Reply.json(204, ""));
        var run = cli.run("task", "observe", "42", "--ctx-k", "148", "--turns", "97");

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly("task 42 OBSERVED ctxK=148 turns=97");
        var sent = posted("/tasks/42/observe").getLast();

        assertThat(sent.authorization()).isEqualTo("Bearer " + CliHarness.TOKEN);
        assertThat(sent.body()).contains("\"ctxK\":148").contains("\"turns\":97");
        assertThat(controller.sentVersions().getLast()).isEqualTo(Optional.of(CliVersion.bundled().unwrap()));
    }

    @Test
    void observe_needsNoLeaseFile_andRefusesNegativesLocally() {
        controller.on("POST /tasks/42/observe", Reply.json(204, ""));
        assertThat(cli.run("task", "observe", "42", "--ctx-k", "1", "--turns", "2").exit()).isZero();
        assertThat(cli.run("task", "observe", "42", "--ctx-k", "-1", "--turns", "2").exit()).isEqualTo(64);
        assertThat(cli.run("task", "observe", "42", "--ctx-k", "1").exit()).isEqualTo(64);
        assertThat(posted("/tasks/42/observe")).hasSize(1);
    }

    @Test
    void observe_unknownTaskIs64_andALeaseTokenRefusalIs77() {
        controller.on("POST /tasks/404/observe", Reply.json(404, "{\"error\":\"task 404\"}"));
        assertThat(cli.run("task", "observe", "404", "--ctx-k", "1", "--turns", "2").exit()).isEqualTo(64);
        controller.on("POST /tasks/42/observe", Reply.json(403, "{\"error\":\"forbidden\"}"));
        assertThat(cli.run("task", "observe", "42", "--ctx-k", "1", "--turns", "2").exit()).isEqualTo(77);
    }

    // ---- claim ---------------------------------------------------------------------------------------------------
    private static ClaimResponse claim(CheckpointDto checkpoint, List<String> warnings) {
        return new ClaimResponse(42,
                                 "s",
                                 "CODE",
                                 1,
                                 8,
                                 "lease-token-8",
                                 SHA,
                                 0,
                                 Instant.parse("2026-11-13T10:30:00Z"),
                                 Instant.parse("2026-11-13T16:00:00Z"),
                                 "a/b",
                                 "file:///nowhere",
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 checkpoint,
                                 warnings);
    }

    /// A successor's first call yields the predecessor's state; the warning comes straight after the claim line.
    @Test
    void claim_printsTheCheckpointAndTheDirtyPredecessorWarning() {
        var checkpoint = new CheckpointDto(3,
                                           7,
                                           SHA,
                                           List.of(new CheckpointDto.Item("wire route", true),
                                                   new CheckpointDto.Item("docs", false)),
                                           List.of(new CheckpointDto.Entry("a/B.java", "10-40")),
                                           "next: docs",
                                           Instant.parse("2026-11-13T10:00:00Z"));

        controller.on("POST /tasks/42/claim",
                      Reply.json(200,
                                 controller.write(claim(checkpoint, List.of("dirty-predecessor")))));
        var run = cli.run("task", "claim", "42");

        assertThat(run.exit()).isZero();
        assertThat(run.out().get(0)).startsWith("task 42 CODE LEASED epoch=8");
        assertThat(run.out().get(1)).startsWith("WARNING dirty-predecessor:").contains("inspect the tree");
        assertThat(run.out()).contains("checkpoint seq=3 epoch=7 at=2026-11-13T10:00:00Z pushedSha=" + SHA
                                      + " note=\"next: docs\"",
                                       "checkpoint item done \"wire route\"",
                                       "checkpoint item todo \"docs\"",
                                       "checkpoint read \"a/B.java\" lines=\"10-40\"");
        assertThat(run.out().stream().filter(line -> line.contains("lease-token"))).isEmpty();
    }

    @Test
    void claim_withoutACheckpointOrWarnings_printsTheClaimLineOnly() {
        controller.on("POST /tasks/42/claim",
                      Reply.json(200,
                                 controller.write(claim(null, List.of()))));
        assertThat(cli.run("task", "claim", "42").out()).hasSize(1);
    }

    @Test
    void claimJson_carriesTheCheckpointAndWarnings_withoutTheToken() {
        var checkpoint = new CheckpointDto(1, 7, SHA, List.of(), List.of(), "", Instant.parse("2026-11-13T10:00:00Z"));

        controller.on("POST /tasks/42/claim",
                      Reply.json(200,
                                 controller.write(claim(checkpoint, List.of("dirty-predecessor")))));
        var text = String.join("\n",
                               cli.run("task", "claim", "42", "--json").out());

        assertThat(text).contains("\"warnings\":[\"dirty-predecessor\"]")
                  .contains("\"checkpoint\"")
                  .doesNotContain("lease-token");
    }

    // ---- list / show ---------------------------------------------------------------------------------------------
    private static String view(String lane) {
        return VIEW_HEAD + lane + "}";
    }

    @Test
    void show_rendersHbAgeStaleDirtyUnpushedAndTheObservedSize() {
        controller.on("GET /tasks/42",
                      Reply.json(200,
                                 view(",\"hbAge\":1500,\"stale\":true,\"report\":{\"head\":\"" + SHA
                                     + "\",\"dirty\":2,\"unpushed\":1,\"epoch\":7,\"at\":\"2026-11-13T10:00:00Z\"},"
                                     + "\"observed\":{\"ctxK\":148,\"turns\":97,\"at\":\"2026-11-13T10:01:00Z\"}")));
        var line = cli.run("task", "show", "42").out().getFirst();

        assertThat(line).endsWith(" holder=wp3a hb-age=1500s STALE head=0cf5631 dirty=2 unpushed=1 ctxK=148 turns=97");
    }

    /// vt39 F3: a report from an earlier lease epoch than the task's current one is marked `(prev)`. Probe: drop the
    /// marking -> red.
    @Test
    void list_marksAReportOfAnotherEpochAsPrev() {
        controller.on("GET /tasks",
                      Reply.json(200,
                                 "[" + view(",\"hbAge\":30,\"stale\":false,\"report\":{\"head\":\"" + SHA
                                           + "\",\"dirty\":2,\"unpushed\":0,\"epoch\":6,\"at\":\"2026-11-13T10:00:00Z\"}")
                                + "]"));
        var line = cli.run("task", "list").out().getFirst();

        assertThat(line).contains(" hb-age=30s head=0cf5631 dirty=2(prev) unpushed=0(prev)").doesNotContain("STALE");
    }

    @Test
    void list_ofATaskWithoutLaneState_isUnchanged() {
        controller.on("GET /tasks/42", Reply.json(200, view("")));
        assertThat(cli.run("task", "show", "42").out().getFirst()).endsWith(" epoch=7 holder=wp3a");
    }
}
