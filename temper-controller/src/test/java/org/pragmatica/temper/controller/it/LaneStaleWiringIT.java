package org.pragmatica.temper.controller.it;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

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


/// `[fleet] laneStaleMin` reaches the `stale` flag through the real Controller wiring (Controller's task routes), not
/// only through the view: a controller configured with `laneStaleMin = 1` flips `stale` after more than 60 s without a
/// heartbeat, on a clock the test moves. Mutation: hard-code `Duration.ofMinutes(20)` where the Controller builds the
/// task routes → the 61 s case is red (the 20 min default has not elapsed).
@Tag("pg")
class LaneStaleWiringIT {
    private static final JsonMapper JSON = TemperJson.mapper();
    private static final String WORKER = "Bearer " + ControllerHarness.WORKER_TOKEN;

    private static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-11-12T10:00:00Z"));

    private static ControllerHarness harness;

    @BeforeAll
    static void start() throws Exception {
        harness = new ControllerHarness(TimeSpan.timeSpan(500).millis(),
                                        NOW::get,
                                        1);
    }

    @AfterAll
    static void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    private static TaskView view(long id) throws Exception {
        var reply = harness.get("/tasks/" + id);

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);

        return JSON.readString(reply.body(),
                               TaskView.class)
                   .unwrap();
    }

    @Test
    void stale_followsTheConfiguredLaneStaleMin_throughTheControllerWiring() throws Exception {
        var created = harness.post("/tasks",
                                   "{\"slug\":\"stale-wire\",\"title\":\"A task\",\"brief\":{\"path\":\"briefs/a.md\"},"
                                  + "\"repo\":\"pragmaticalabs/pragmatica\",\"baseRef\":\"main\",\"branch\":\"agent/stale-wire\","
                                  + "\"createdBy\":\"cto\"}");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        var id = JSON.readString(created.body(), TaskView.class).unwrap().id();

        harness.store.tasks().sweep(NOW.get()).await(Pg.WAIT).unwrap();
        var claimReply = harness.callAs("POST", "/tasks/" + id + "/claim", "{\"worker\":\"w-1\"}", WORKER);

        assertThat(claimReply.status()).as(claimReply.body()).isEqualTo(200);
        assertThat(JSON.readString(claimReply.body(), ClaimResponse.class).unwrap().epoch()).isEqualTo(1);
        NOW.set(NOW.get().plus(Duration.ofSeconds(59)));
        var before = view(id);

        NOW.set(NOW.get().plus(Duration.ofSeconds(2)));
        var after = view(id);

        assertThat(before.hbAge().unwrap()).isEqualTo(59L);
        assertThat(before.stale()).as("59 s is inside laneStaleMin = 1").isFalse();
        assertThat(after.hbAge().unwrap()).isEqualTo(61L);
        assertThat(after.stale()).as("61 s is past laneStaleMin = 1 (the default 20 min would say false)").isTrue();
    }
}
