package org.pragmatica.temper.controller.it;

import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.TaskView;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;

/// The task API end to end (m3-design §6.4, §11.1): a real controller over Postgres and HTTP. Creation refuses a protected
/// branch and an unknown base (422); the worker token claims (and nothing else it may not), the lease token is the only
/// credential of the lease verbs and is fenced (`409 lease-lost` at another epoch or under another token); a submit
/// with `task` links the job under the lease and is 400 without the lease; park, rule and close go through the maintainer's
/// full token.
@Tag("pg")
class TaskApiIT {
    private static final JsonMapper JSON = TemperJson.mapper();
    private static final String WORKER = "Bearer " + ControllerHarness.WORKER_TOKEN;
    private static final String FULL = "Bearer " + ControllerHarness.TOKEN;
    private static ControllerHarness harness;

    @BeforeAll
    static void start() throws Exception {
        harness = new ControllerHarness(false, TimeSpan.timeSpan(500).millis());
    }

    @AfterAll
    static void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    private static String createBody(String slug, String branch, String base) {
        return "{\"slug\":\"" + slug + "\",\"title\":\"A task\",\"brief\":{\"path\":\"briefs/a.md\"},\"repo\":\"pragmaticalabs/pragmatica\","
               + "\"baseRef\":\"" + base + "\",\"branch\":\"" + branch + "\",\"createdBy\":\"cto\"}";
    }

    /// A task created and advanced to CODE by a sweep.
    private static TaskView inCode(String slug) throws Exception {
        var created = harness.post("/tasks", createBody(slug, "agent/" + slug, "main"));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        harness.store.tasks().sweep(Instant.now()).await(Pg.WAIT).unwrap();
        return view(harness.get("/tasks/" + JSON.readString(created.body(), TaskView.class).unwrap().id()));
    }

    private static TaskView view(ControllerHarness.Reply reply) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        return JSON.readString(reply.body(), TaskView.class).unwrap();
    }

    private static ClaimResponse claimed(long task) throws Exception {
        var reply = harness.callAs("POST", "/tasks/" + task + "/claim", "{\"worker\":\"w-1\"}", WORKER);

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        return JSON.readString(reply.body(), ClaimResponse.class).unwrap();
    }

    private static String lease(ClaimResponse claim) {
        return "Lease " + claim.leaseToken();
    }

    @Test
    void create_protectedBranch_orUnknownBase_is422() throws Exception {
        var protectedBranch = harness.post("/tasks", createBody("p-1", "main", "main"));
        var unknownBase = harness.post("/tasks", createBody("p-2", "agent/p-2", "no-such-base"));

        assertThat(protectedBranch.status()).isEqualTo(422);
        assertThat(protectedBranch.body()).contains("protected-branch");
        assertThat(unknownBase.status()).isEqualTo(422);
        assertThat(unknownBase.body()).contains("base-unknown");
    }

    @Test
    void claim_byTheWorkerToken_issuesTheBaseTip_andATokenNeverStored() throws Exception {
        var task = inCode("c-1");
        var claim = claimed(task.id());

        assertThat(claim.stage()).isEqualTo("CODE");
        assertThat(claim.epoch()).isEqualTo(1);
        assertThat(claim.readPoint()).isEqualTo(ControllerHarness.SHA_A);
        assertThat(claim.cloneUrl()).isEqualTo("git@example:repo.git");
        assertThat(claim.leaseToken()).hasSize(43);
        assertThat(harness.get("/tasks/" + task.id()).body()).doesNotContain(claim.leaseToken());
        assertThat(harness.callAs("POST", "/tasks/" + task.id() + "/claim", "", WORKER).status()).isEqualTo(409);
    }

    @Test
    void claimByStage_withNothingReady_is204() throws Exception {
        assertThat(harness.callAs("POST", "/tasks/claim?stage=GATE", "", WORKER).status()).isEqualTo(204);
    }

    @Test
    void scopes_workerTokenCannotRule_fullTokenCannotHeartbeat_leaseCannotRead() throws Exception {
        var task = inCode("s-1");
        var claim = claimed(task.id());

        assertThat(harness.callAs("POST", "/tasks/" + task.id() + "/rule", "{\"option\":\"a\",\"text\":\"t\"}", WORKER).status()).isEqualTo(403);
        assertThat(harness.callAs("POST", "/tasks/" + task.id() + "/heartbeat", "{\"epoch\":1}", FULL).status()).isEqualTo(403);
        assertThat(harness.callAs("GET", "/tasks/" + task.id(), "", lease(claim)).status()).isEqualTo(403);
        assertThat(harness.callAs("POST", "/tasks/" + task.id() + "/heartbeat", "{\"epoch\":1}", "Lease ").status()).isEqualTo(401);
    }

    @Test
    void heartbeat_isFenced_byEpochAndToken() throws Exception {
        var task = inCode("h-1");
        var claim = claimed(task.id());
        var path = "/tasks/" + task.id() + "/heartbeat";

        assertThat(harness.callAs("POST", path, "{\"epoch\":1}", lease(claim)).status()).isEqualTo(204);
        var staleEpoch = harness.callAs("POST", path, "{\"epoch\":0}", lease(claim));
        var otherToken = harness.callAs("POST", path, "{\"epoch\":1}", "Lease not-the-token");

        assertThat(staleEpoch.status()).isEqualTo(409);
        assertThat(staleEpoch.body()).contains("lease-lost");
        assertThat(otherToken.status()).isEqualTo(409);
        assertThat(otherToken.body()).contains("lease-lost");
    }

    @Test
    void submit_withTask_linksTheJob_underTheLease_only() throws Exception {
        var task = inCode("j-1");
        var claim = claimed(task.id());
        var job = ControllerHarness.mvnJob("{\"pushed\":{\"sha\":\"" + ControllerHarness.SHA_A + "\"}}", "-pl", "core", "verify");
        var linked = job.substring(0, job.length() - 1) + ",\"task\":{\"id\":" + task.id() + ",\"epoch\":1}}";

        var underLease = harness.callAs("POST", "/jobs", linked, lease(claim));
        var fullWithTask = harness.post("/jobs", linked);
        var leaseWithoutTask = harness.callAs("POST", "/jobs", job, lease(claim));
        var staleEpoch = harness.callAs("POST", "/jobs", linked.replace("\"epoch\":1", "\"epoch\":0"), lease(claim));

        assertThat(underLease.status()).as(underLease.body()).isEqualTo(200);
        assertThat(fullWithTask.status()).isEqualTo(400);
        assertThat(leaseWithoutTask.status()).isEqualTo(400);
        assertThat(staleEpoch.status()).isEqualTo(409);
        assertThat(view(harness.get("/tasks/" + task.id())).detail().unwrap().jobs()).hasSize(1)
                                                                                      .allMatch(linkedJob -> linkedJob.job() == ControllerHarness.jobOf(underLease)
                                                                                                              && linkedJob.epoch() == 1);
    }

    @Test
    void park_byTheLease_thenRule_thenCloseAbandoned() throws Exception {
        var task = inCode("r-1");
        var claim = claimed(task.id());
        var request = "{\"kind\":\"design\",\"question\":\"Which?\",\"options\":[{\"id\":\"a\",\"text\":\"A\"},{\"id\":\"b\",\"text\":\"B\"}],"
                      + "\"recommendation\":{\"option\":\"a\",\"why\":\"smaller\"},\"blocked\":{\"tasks\":[],\"text\":\"none\"}}";

        var parked = view(harness.callAs("POST", "/tasks/" + task.id() + "/park", "{\"epoch\":1,\"request\":" + request + "}", lease(claim)));
        var notOffered = harness.post("/tasks/" + task.id() + "/rule", "{\"option\":\"z\",\"text\":\"go\"}");
        var ruled = view(harness.post("/tasks/" + task.id() + "/rule", "{\"option\":\"a\",\"text\":\"go\",\"extraRounds\":1}"));
        var closed = view(harness.post("/tasks/" + task.id() + "/close", "{\"how\":\"abandoned\"}"));

        assertThat(parked.stage()).isEqualTo("NEEDS_RULING");
        assertThat(parked.raisedFrom().unwrap()).isEqualTo("CODE");
        assertThat(notOffered.status()).isEqualTo(422);
        assertThat(ruled.stage()).isEqualTo("CODE");
        assertThat(ruled.roundCap()).isEqualTo(4);
        assertThat(closed.stage()).isEqualTo("ABANDONED");
        assertThat(harness.post("/tasks/" + task.id() + "/close", "{\"how\":\"abandoned\"}").status()).isEqualTo(409);
    }

    @Test
    void park_temperRaisedKind_is422() throws Exception {
        var task = inCode("k-1");
        var request = "{\"kind\":\"stuck-lease\",\"question\":\"Q\",\"options\":[{\"id\":\"a\",\"text\":\"A\"},{\"id\":\"b\",\"text\":\"B\"}],"
                      + "\"recommendation\":{\"option\":\"a\",\"why\":\"w\"},\"blocked\":{\"tasks\":[],\"text\":\"none\"}}";
        var reply = harness.post("/tasks/" + task.id() + "/park", "{\"request\":" + request + "}");

        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.body()).contains("park-request-invalid");
    }

    @Test
    void next_andEvents_areReadable() throws Exception {
        var task = inCode("n-1");
        var next = harness.callAs("GET", "/tasks/next?stage=CODE", "", WORKER);
        var events = harness.get("/tasks/" + task.id() + "/events");

        assertThat(next.status()).isEqualTo(200);
        assertThat(next.body()).contains("\"stage\":\"CODE\"");
        assertThat(events.status()).isEqualTo(200);
        assertThat(events.body()).contains("\"kind\":\"created\"").contains("\"kind\":\"advanced\"");
        assertThat(harness.get("/tasks/999999").status()).isEqualTo(404);
    }
}
