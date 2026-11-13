package org.pragmatica.temper.controller.it;

import java.time.Instant;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.TaskView;
import org.pragmatica.temper.protocol.json.TemperJson;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The lane registry end to end (WP1 + the schema of WP2): a real controller over Postgres and HTTP. A lane outlives its
/// agent: its checkpoints and its last SELF-REPORTED tree are kept on the task, a successor's claim returns the
/// latest checkpoint, and the context size is the PARENT's measurement, written only with the full token.
@Tag("pg")
class LaneRegistryIT {
    private static final JsonMapper JSON = TemperJson.mapper();
    private static final String WORKER = "Bearer " + ControllerHarness.WORKER_TOKEN;
    private static final String FULL = "Bearer " + ControllerHarness.TOKEN;
    private static final String PUSHED = "c".repeat(40);
    private static ControllerHarness harness;

    @BeforeAll
    static void start() throws Exception {
        harness = new ControllerHarness(false,
                                        TimeSpan.timeSpan(500).millis());
    }

    @AfterAll
    static void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    private static TaskView inCode(String slug) throws Exception {
        var created = harness.post("/tasks",
                                   "{\"slug\":\"" + slug
                                  + "\",\"title\":\"A task\",\"brief\":{\"path\":\"briefs/a.md\"},"
                                  + "\"repo\":\"pragmaticalabs/pragmatica\",\"baseRef\":\"main\",\"branch\":\"agent/" + slug
                                  + "\",\"createdBy\":\"cto\"}");

        assertThat(created.status()).as(created.body()).isEqualTo(201);

        return view(harness.advanced(JSON.readString(created.body(), TaskView.class).unwrap().id(),
                                     Instant::now));
    }

    private static TaskView view(ControllerHarness.Reply reply) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);

        return JSON.readString(reply.body(),
                               TaskView.class)
                   .unwrap();
    }

    private static ControllerHarness.Reply claimReply(long task) throws Exception {
        return harness.callAs("POST", "/tasks/" + task + "/claim", "{\"worker\":\"w-1\"}", WORKER);
    }

    private static ClaimResponse claimed(long task) throws Exception {
        var reply = claimReply(task);

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);

        return JSON.readString(reply.body(),
                               ClaimResponse.class)
                   .unwrap();
    }

    private static String lease(ClaimResponse claim) {
        return "Lease " + claim.leaseToken();
    }

    private static String checkpointBody(long epoch, String sha, String note) {
        return "{\"epoch\":" + epoch
             + ",\"pushedSha\":\"" + sha
             + "\",\"checklist\":[{\"item\":\"V20\",\"done\":true},{\"item\":\"routes\",\"done\":false}],"
             + "\"readMap\":[{\"path\":\"docs/m3-design.md\",\"lines\":\"315-414\"}],\"note\":\"" + note
             + "\"}";
    }

    private static ControllerHarness.Reply checkpoint(long task, String authorization, String body) throws Exception {
        return harness.callAs("POST", "/tasks/" + task + "/checkpoint", body, authorization);
    }

    private static void pushed(TaskView task, String sha) {
        harness.onBranch.computeIfAbsent(task.branch(),
                                         _ -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                        .add(sha);
    }

    private static void release(long task, ClaimResponse claim, long epoch) throws Exception {
        assertThat(harness.callAs("POST",
                                  "/tasks/" + task + "/release",
                                  "{\"epoch\":" + epoch + ",\"reason\":\"handoff\"}",
                                  lease(claim))
                          .status()).isEqualTo(204);
    }

    /// Mutation: drop the lease fence (`tasks.holds`) from `checkpointed` and the atomic insert's `lease_epoch = $2` →
    /// the stale-epoch case stores a checkpoint (201) instead of 409.
    @Test
    void checkpoint_isFenced_byEpochAndToken_beforeAnythingIsJudged() throws Exception {
        var task = inCode("lr-fence");
        var claim = claimed(task.id());

        pushed(task, PUSHED);
        var staleEpoch = checkpoint(task.id(), lease(claim), checkpointBody(0, PUSHED, "n"));
        var otherToken = checkpoint(task.id(), "Lease not-the-token", checkpointBody(1, PUSHED, "n"));
        var staleAndOffBranch = checkpoint(task.id(), lease(claim), checkpointBody(0, "d".repeat(40), "n"));
        var fullToken = checkpoint(task.id(), FULL, checkpointBody(1, PUSHED, "n"));

        assertThat(staleEpoch.status()).as(staleEpoch.body()).isEqualTo(409);
        assertThat(staleEpoch.body()).contains("lease-lost");
        assertThat(otherToken.status()).isEqualTo(409);
        assertThat(staleAndOffBranch.status()).as("fenced before the sha is judged").isEqualTo(409);
        assertThat(fullToken.status()).as("a lease verb: the full token is refused").isEqualTo(403);
        assertThat(harness.callAs("GET", "/tasks/" + task.id(), "", FULL).body()).doesNotContain("\"seq\"");
    }

    /// Mutation: raise `MAX_CHECKPOINT_BYTES` to 1 MB → the oversize case is stored (201) instead of 422.
    @Test
    void checkpoint_bodyOver16KB_is422_andNothingIsStored() throws Exception {
        var task = inCode("lr-size");
        var claim = claimed(task.id());

        pushed(task, PUSHED);
        var exactlyFits = checkpointBody(1,
                                         PUSHED,
                                         "x".repeat(16 * 1024 - checkpointBody(1, PUSHED, "").length()));
        var oneOver = checkpointBody(1,
                                     PUSHED,
                                     "x".repeat(16 * 1024 - checkpointBody(1, PUSHED, "").length() + 1));

        assertThat(exactlyFits.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(16 * 1024);
        var tooLarge = checkpoint(task.id(), lease(claim), oneOver);
        var fits = checkpoint(task.id(), lease(claim), exactlyFits);

        assertThat(tooLarge.status()).as(tooLarge.body()).isEqualTo(422);
        assertThat(tooLarge.body()).contains("checkpoint-too-large");
        assertThat(fits.status()).as("16 KB exactly is accepted: " + fits.body()).isEqualTo(201);
        assertThat(fits.body()).contains("\"seq\":1");
    }

    /// Fail closed (ruling d): a mirror that cannot answer (origin unreachable, broken mirror) is a 503 and stores
    /// nothing; the same body is accepted as version 1 once the mirror answers. Mutation: `.recover(_ -> true)` on
    /// `branchContains` or in the route, or treat the failure as "not on branch" (422) → the 503 assertion is red.
    @Test
    void checkpoint_mirrorCannotAnswer_is503_andNothingIsStored() throws Exception {
        var task = inCode("lr-mirror-down");
        var claim = claimed(task.id());

        pushed(task, PUSHED);
        harness.mirrorDown = true;
        try {
            var down = checkpoint(task.id(), lease(claim), checkpointBody(1, PUSHED, "while down"));

            assertThat(down.status()).as(down.body()).isEqualTo(503);
        } finally {
            harness.mirrorDown = false;
        }

        var up = checkpoint(task.id(), lease(claim), checkpointBody(1, PUSHED, "after"));

        assertThat(up.status()).as(up.body()).isEqualTo(201);
        assertThat(up.body()).as("the failed call burned no version").contains("\"seq\":1");
    }

    /// Mutation: have `checkpointed` skip `mirror.branchContains` → the off-branch case answers 201.
    @Test
    void checkpoint_shaNotOnTheTaskBranch_is422_aShaOnIt_isStoredAsTheNextVersion() throws Exception {
        var task = inCode("lr-branch");
        var claim = claimed(task.id());
        var offBranch = checkpoint(task.id(), lease(claim), checkpointBody(1, PUSHED, "first"));

        pushed(task, PUSHED);
        var first = checkpoint(task.id(), lease(claim), checkpointBody(1, PUSHED, "first"));
        var second = checkpoint(task.id(), lease(claim), checkpointBody(1, PUSHED, "second"));
        var notASha = checkpoint(task.id(), lease(claim), checkpointBody(1, "nope", "x"));

        assertThat(offBranch.status()).as(offBranch.body()).isEqualTo(422);
        assertThat(offBranch.body()).contains("pushed-sha-not-on-branch");
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        assertThat(first.body()).contains("\"seq\":1").contains("\"epoch\":1").contains(PUSHED).contains("315-414");
        assertThat(second.body()).contains("\"seq\":2");
        assertThat(notASha.status()).isEqualTo(400);
    }

    /// The point of the registry: a successor's first call yields the state file. Mutation: have the claim skip
    /// `TaskLane.latest` → the second claim carries no checkpoint.
    @Test
    void claim_afterARelease_carriesTheLatestCheckpoint_andAFirstClaimCarriesNoNewKeys() throws Exception {
        var task = inCode("lr-claim");
        var firstRaw = claimReply(task.id());
        var first = JSON.readString(firstRaw.body(), ClaimResponse.class).unwrap();

        assertThat(firstRaw.body()).as("a claim with nothing to add reads as before, for an old strict client too")
                  .doesNotContain("\"checkpoint\"")
                  .doesNotContain("\"warnings\"");
        pushed(task, PUSHED);
        assertThat(checkpoint(task.id(), lease(first), checkpointBody(1, PUSHED, "one")).status()).isEqualTo(201);
        assertThat(checkpoint(task.id(), lease(first), checkpointBody(1, PUSHED, "two")).status()).isEqualTo(201);
        release(task.id(), first, 1);
        var second = claimed(task.id());

        assertThat(second.epoch()).isGreaterThan(first.epoch());
        assertThat(second.checkpoint()).isNotNull();
        assertThat(second.checkpoint().seq()).isEqualTo(2);
        assertThat(second.checkpoint().note()).isEqualTo("two");
        assertThat(second.checkpoint().epoch()).as("written by the predecessor's lease").isEqualTo(1);
        assertThat(second.checkpoint().pushedSha()).isEqualTo(PUSHED);
        assertThat(second.checkpoint().checklist()).extracting(item -> item.item() + ":" + item.done())
                  .containsExactly("V20:true", "routes:false");
        assertThat(second.checkpoint().readMap()).extracting(entry -> entry.path() + "@" + entry.lines())
                  .containsExactly("docs/m3-design.md@315-414");
        assertThat(second.warnings()).isEmpty();
        assertThat(checkpoint(task.id(),
                              lease(first),
                              checkpointBody(1, PUSHED, "zombie")).status()).as("the old lease is fenced out")
                  .isEqualTo(409);
    }

    /// Mutation: have `heartbeat` ignore `body.report()` → the stored-report case finds no report.
    @Test
    void heartbeat_withoutAReport_isUnchanged_withOne_itIsStoredAndShown() throws Exception {
        var task = inCode("lr-hb");
        var claim = claimed(task.id());
        var path = "/tasks/" + task.id() + "/heartbeat";
        var untouched = view(harness.get("/tasks/" + task.id()));

        assertThat(untouched.report().isEmpty()).as("nothing reported yet").isTrue();
        assertThat(untouched.hbAge().isPresent()).as("the claim started the heartbeat clock").isTrue();
        assertThat(harness.callAs("POST", path, "{\"epoch\":1}", lease(claim)).status()).isEqualTo(204);
        assertThat(view(harness.get("/tasks/" + task.id())).report().isEmpty()).as("a bare heartbeat reports nothing")
                  .isTrue();
        var reported = harness.callAs("POST",
                                      path,
                                      "{\"epoch\":1,\"report\":{\"head\":\"" + PUSHED
                                     + "\",\"dirty\":3,\"unpushed\":2}}",
                                      lease(claim));
        var shown = view(harness.get("/tasks/" + task.id())).report().unwrap();
        var barely = harness.callAs("POST", path, "{\"epoch\":1}", lease(claim));
        var kept = view(harness.get("/tasks/" + task.id()));

        assertThat(reported.status()).as(reported.body()).isEqualTo(204);
        assertThat(shown.head()).isEqualTo(PUSHED);
        assertThat(shown.dirty()).isEqualTo(3);
        assertThat(shown.unpushed()).isEqualTo(2);
        assertThat(shown.epoch()).isEqualTo(1);
        assertThat(barely.status()).isEqualTo(204);
        assertThat(kept.report().unwrap().dirty()).as("a later bare heartbeat keeps the last report").isEqualTo(3);
        assertThat(kept.stale()).isFalse();
    }

    @Test
    void heartbeat_withAnInvalidReport_is400_andAStaleOneIsStillFenced() throws Exception {
        var task = inCode("lr-hb-bad");
        var claim = claimed(task.id());
        var path = "/tasks/" + task.id() + "/heartbeat";

        assertThat(harness.callAs("POST",
                                  path,
                                  "{\"epoch\":1,\"report\":{\"head\":\"" + PUSHED + "\",\"dirty\":-1,\"unpushed\":0}}",
                                  lease(claim))
                          .status()).isEqualTo(400);
        assertThat(harness.callAs("POST",
                                  path,
                                  "{\"epoch\":1,\"report\":{\"head\":\"zz\",\"dirty\":0,\"unpushed\":0}}",
                                  lease(claim))
                          .status()).isEqualTo(400);
        assertThat(harness.callAs("POST",
                                  path,
                                  "{\"epoch\":0,\"report\":{\"head\":\"" + PUSHED + "\",\"dirty\":9,\"unpushed\":9}}",
                                  lease(claim))
                          .status()).isEqualTo(409);
        assertThat(view(harness.get("/tasks/" + task.id())).report().isEmpty()).as("a refused heartbeat stores nothing")
                  .isTrue();
    }

    /// The CTO rule: context is measured by the parent, never self-reported. Mutation: register `observe` as
    /// `scopedRoute(.., LEASE, ..)` → the lease-token case is accepted (204).
    @Test
    void observe_isFullTokenOnly_theLeaseTokenCannotWriteIt() throws Exception {
        var task = inCode("lr-observe");
        var claim = claimed(task.id());
        var path = "/tasks/" + task.id() + "/observe";
        var body = "{\"ctxK\":152,\"turns\":141}";
        var byLease = harness.callAs("POST", path, body, lease(claim));
        var byWorker = harness.callAs("POST", path, body, WORKER);

        assertThat(byLease.status()).as(byLease.body()).isIn(401, 403);
        assertThat(byWorker.status()).isEqualTo(403);
        assertThat(view(harness.get("/tasks/" + task.id())).observed().isEmpty()).as("the refused writes stored nothing")
                  .isTrue();
        var byParent = harness.post(path, body);
        var shown = view(harness.get("/tasks/" + task.id())).observed().unwrap();

        assertThat(byParent.status()).as(byParent.body()).isEqualTo(204);
        assertThat(shown.ctxK()).isEqualTo(152);
        assertThat(shown.turns()).isEqualTo(141);
        assertThat(harness.post("/tasks/999999/observe", body).status()).isEqualTo(404);
        assertThat(harness.post(path, "{\"ctxK\":-1,\"turns\":1}").status()).isEqualTo(400);
    }

    /// Mutation: drop the `warnings` mapping in `Claimed.claimed` → the dirty case has no warning.
    @Test
    void claim_afterADirtyReport_warnsDirtyPredecessor_stillAnswers200_andACleanOneDoesNot() throws Exception {
        var dirty = inCode("lr-dirty");
        var first = claimed(dirty.id());

        assertThat(harness.callAs("POST",
                                  "/tasks/" + dirty.id() + "/heartbeat",
                                  "{\"epoch\":1,\"report\":{\"head\":\"" + PUSHED + "\",\"dirty\":0,\"unpushed\":4}}",
                                  lease(first))
                          .status()).isEqualTo(204);
        release(dirty.id(), first, 1);
        var warned = claimReply(dirty.id());

        assertThat(warned.status()).isEqualTo(200);
        assertThat(JSON.readString(warned.body(), ClaimResponse.class).unwrap().warnings()).containsExactly("dirty-predecessor");
        var clean = inCode("lr-clean");
        var cleanFirst = claimed(clean.id());

        assertThat(harness.callAs("POST",
                                  "/tasks/" + clean.id() + "/heartbeat",
                                  "{\"epoch\":1,\"report\":{\"head\":\"" + PUSHED + "\",\"dirty\":0,\"unpushed\":0}}",
                                  lease(cleanFirst))
                          .status()).isEqualTo(204);
        release(clean.id(), cleanFirst, 1);
        assertThat(claimReply(clean.id()).body()).doesNotContain("dirty-predecessor");
        var silent = inCode("lr-silent");
        var silentFirst = claimed(silent.id());

        release(silent.id(), silentFirst, 1);
        assertThat(claimReply(silent.id()).body()).as("no report at all is not a dirty one")
                  .doesNotContain("dirty-predecessor");
    }

    @Test
    void list_showsHbAgeAndStale_onlyForALeasedTask() throws Exception {
        var ready = inCode("lr-list-ready");
        var leased = inCode("lr-list-leased");

        claimed(leased.id());
        var readyView = view(harness.get("/tasks/" + ready.id()));
        var leasedView = view(harness.get("/tasks/" + leased.id()));

        assertThat(readyView.hbAge().isEmpty()).isTrue();
        assertThat(readyView.stale()).isFalse();
        assertThat(leasedView.hbAge().unwrap()).isBetween(0L, 60L);
        assertThat(leasedView.stale()).isFalse();
        assertThat(harness.get("/tasks?limit=50").body()).contains("\"hbAge\"").contains("\"stale\"");
    }
}
