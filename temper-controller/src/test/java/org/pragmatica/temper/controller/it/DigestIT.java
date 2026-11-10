package org.pragmatica.temper.controller.it;

import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// `GET /api/v1/digest` end to end (it-pg, dashboard design §2): the route is wired, it reads the store and the host
/// links, answers JSON or text, rejects what does not parse, and leaves no subscriber behind. The harness's fake
/// process runner fails the `pg_dump` the controller runs at start, so the digest shows that failed dump.
@Tag("pg")
class DigestIT {
    private static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";
    private static final Pattern CURSOR = Pattern.compile("\"cursor\":\"(\\d+)\\.([0-9-]+)\"");
    private ControllerHarness harness;

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void digest_afterAJobRan_showsItFinishedAndStarted_jsonAndText() throws Exception {
        harness = new ControllerHarness(true, TimeSpan.timeSpan(300).millis());
        var first = harness.get("/digest");

        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(first.body()).contains("\"finished\":[]", "\"more\":false", "\"gap\":null");
        var since = cursor(first.body());
        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "digest", "verify")));

        harness.waitForState(job, "PASSED");
        var json = harness.get("/digest?since=" + since + "&max=100");

        assertThat(json.status()).as(json.body()).isEqualTo(200);
        assertThat(json.body()).contains("\"finished\":[{\"seq\":",
                                         "\"job\":" + job + ",\"kind\":\"finished\",\"verdict\":\"PASSED\"",
                                         "\"owner\":\"v2174\"",
                                         "\"started\":[{\"seq\":",
                                         "\"kind\":\"queued\"",
                                         "\"name\":\"bb\",\"link\":\"UP\"",
                                         "\"queue\":[]",
                                         "\"queued\":0");
        assertThat(seq(cursor(json.body()))).isGreaterThan(seq(since));
        var text = harness.get("/digest?since=" + since + "&max=100&format=text");

        assertThat(text.status()).isEqualTo(200);
        assertThat(text.body().lines().toList()).first().asString().startsWith("digest cursor=" + cursor(json.body()) + " more=false");
        assertThat(text.body()).contains("finished job=" + job + " PASSED", "started job=" + job + " unit=main", "queue empty",
                                         "host bb link=UP state=UP cap=5", "dump latest FAILED");
        assertThat(harness.store.events().subscriber("probe").await(Pg.WAIT).unwrap().cursor())
            .as("the digest created no subscriber and moved no cursor").isEqualTo(seq(cursor(json.body())));
    }

    @Test
    void digest_withoutAHost_listsTheQueuedJob() throws Exception {
        harness = new ControllerHarness(false, TimeSpan.timeSpan(300).millis());
        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "queued", "verify")));
        var json = harness.get("/digest?since=0.-");

        assertThat(json.body()).contains("\"queue\":[{\"pos\":1,\"job\":" + job + ",\"state\":\"QUEUED\",\"prio\":",
                                         "\"queued\":1",
                                         "\"hosts\":[]");
        assertThat(harness.get("/digest?since=0.-&format=text").body()).contains("queue 1/1 job=" + job + " prio=");
    }

    @Test
    void digest_badParameters_are400_foreignCursorIs409() throws Exception {
        harness = new ControllerHarness(false, TimeSpan.timeSpan(300).millis());
        jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "params", "verify")));
        var head = cursor(harness.get("/digest").body());

        for (var query : new String[] {"since=-1.-", "since=x", "since=" + seq(head), "max=0", "max=1001", "max=y", "format=xml",
                                       "since=" + (seq(head) + 1) + ".-"}) {
            var reply = harness.get("/digest?" + query);

            assertThat(reply.status()).as(query + " -> " + reply.body()).isEqualTo(400);
        }
        assertThat(harness.get("/digest?since=" + (seq(head) + 1) + ".-").body()).contains("ahead of the event log");
        var foreign = harness.get("/digest?since=" + seq(head) + ".1");

        assertThat(foreign.status()).as("the event at the cursor's seq is another one").isEqualTo(409);
        assertThat(foreign.body()).contains("CURSOR_FOREIGN", "restart without since");
        assertThat(harness.get("/digest?since=" + head + "&max=1000&format=json").status()).isEqualTo(200);
    }

    private static String cursor(String body) {
        var matcher = CURSOR.matcher(body);

        assertThat(matcher.find()).as("a cursor in " + body).isTrue();
        return matcher.group(1) + "." + matcher.group(2);
    }

    private static long seq(String cursor) {
        return Long.parseLong(cursor.substring(0, cursor.indexOf('.')));
    }
}
