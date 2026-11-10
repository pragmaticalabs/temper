package org.pragmatica.temper.controller.it;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.http.Route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// D-6 over HTTP on the assembled controller (it-pg tier): the version gate in front of every mutating route (M1), and
/// the dedup key's whole-build-version runner component across builds (A1).
@Tag("pg")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildIdentityIT {
    private static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";
    /// A judged build of the same `project.version` as [ControllerHarness#VERSION]: skewed.
    private static final String SKEWED = "0.1.0+fedcba987654";
    private static final String R_9 = "{\"code\":\"R-9\",\"matcher\":{\"excludeTestClass\":{\"simpleClassName\":\"FlakyIT\",\"disabledMarker\":\"#9\"}},"
                                      + "\"issue\":9,\"reason\":\"flaky until #9\"}";
    private ControllerHarness harness;

    @BeforeAll
    void start() throws Exception {
        harness = new ControllerHarness(true, TimeSpan.timeSpan(500).millis());
    }

    @AfterAll
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    /// The census guard: every route the controller registers with a method other than GET is registered as WRITES,
    /// routes added later included, so none can slip past the gate by its registration.
    @Test
    void census_everyNonGetRoute_isRegisteredAsWrites() {
        assertThat(harness.running.routes()
                                  .stream()
                                  .filter(route -> route.method() != HttpMethod.GET)
                                  .filter(route -> route.effect() != Route.Effect.WRITES)
                                  .map(route -> route.method() + " " + pathOf(route))
                                  .toList()).isEmpty();
    }

    /// The census: every route the controller registers as WRITES (every non-GET route and `GET subscribers/{name}/next`,
    /// which creates and skip-acks its subscriber), routes added later included, answers a skewed CLI 412 naming both
    /// versions, WITHOUT a token: the gate runs before authentication (which would answer 401) and so before any handler.
    @Test
    void census_everyWritesRoute_refusesASkewedCli_beforeAuthentication() throws Exception {
        var mutating = harness.running.routes()
                                      .stream()
                                      .filter(route -> route.effect() == Route.Effect.WRITES)
                                      .toList();

        assertThat(mutating).extracting(route -> route.method() + " " + pathOf(route))
                            .contains("POST /jobs", "PUT /subscribers/1/selectors", "GET /subscribers/1/next");
        for (var route : mutating) {
            var path = pathOf(route);
            var body = route.method() == HttpMethod.GET
                       ? ""
                       : "{}";
            var reply = harness.callWith(route.method().name(), path, body, false, Map.of("Temper-Version", SKEWED));

            assertThat(reply.status()).as(route.method() + " " + path).isEqualTo(412);
            assertThat(reply.body()).as(route.method() + " " + path).contains(SKEWED, ControllerHarness.VERSION);
            assertThat(reply.temperVersion()).as(route.method() + " " + path).isEqualTo(ControllerHarness.VERSION);
        }
    }

    /// The one GET that writes, refused, did not write: a skewed `next` does not skip-ack an existing subscriber past
    /// events its selectors do not match.
    @Test
    void skewedNext_isRefused_andDoesNotAdvanceASubscriber() throws Exception {
        var watched = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "watched", "verify")));
        harness.waitForState(watched, "PASSED");
        assertThat(harness.callWith("PUT", "/subscribers/skewwatch/selectors", "{\"add\":[\"job:" + watched + "\"],\"remove\":[]}",
                                    true, Map.of("Temper-Version", ControllerHarness.VERSION))
                          .status()).isEqualTo(200);
        var unwatched = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "unwatched", "verify")));
        harness.waitForState(unwatched, "PASSED");
        var cursor = cursorOf("skewwatch");

        var refused = harness.callWith("GET", "/subscribers/skewwatch/next?terminal=false", "", true, Map.of("Temper-Version", SKEWED));

        assertThat(refused.status()).isEqualTo(412);
        assertThat(cursorOf("skewwatch")).as("not skip-acked past job:" + unwatched + "'s events").isEqualTo(cursor);
    }

    /// The one GET that writes, refused, did not write: a skewed `next` creates no subscriber (one created later starts
    /// at the end of the stream as of then, like any other created then).
    @Test
    void skewedNext_isRefused_andCreatesNoSubscriber() throws Exception {
        var refused = harness.callWith("GET", "/subscribers/skewfresh/next?terminal=false", "", true, Map.of("Temper-Version", SKEWED));

        assertThat(refused.status()).isEqualTo(412);

        var later = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "later", "verify")));
        harness.waitForState(later, "PASSED");

        assertThat(cursorOf("skewfresh")).as("created only now, not by the refused poll")
                                         .isEqualTo(cursorOf("skewcontrol"));
    }

    /// The subscriber's cursor, read from the store (which creates an absent subscriber at the end of the stream).
    private long cursorOf(String subscriber) {
        return harness.store.events()
                            .subscriber(subscriber)
                            .await(Pg.WAIT)
                            .unwrap()
                            .cursor();
    }

    /// Reads keep the reply-side check: a skewed CLI's GET is answered (the CLI refuses it on the header).
    @Test
    void aRead_ofASkewedCli_isAnswered() throws Exception {
        var reply = harness.callWith("GET", "/hosts", "", true, Map.of("Temper-Version", SKEWED));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.temperVersion()).isEqualTo(ControllerHarness.VERSION);
    }

    /// The refused write did not happen: `refusal expire` from a skewed CLI leaves the refusal active; the same
    /// request from this build's CLI expires it.
    @Test
    void skewedRefusalExpire_isRefused_andTheRefusalStaysActive() throws Exception {
        assertThat(harness.callWith("POST", "/refusals", R_9, true, Map.of("Temper-Version", ControllerHarness.VERSION))
                          .status()).isEqualTo(201);

        var refused = harness.callWith("POST", "/refusals/R-9/expire", "", true, Map.of("Temper-Version", SKEWED));

        assertThat(refused.status()).isEqualTo(412);
        assertThat(expiredAtOfR9()).isEqualTo("null");

        var expired = harness.callWith("POST", "/refusals/R-9/expire", "", true, Map.of("Temper-Version", ControllerHarness.VERSION));

        assertThat(expired.status()).isEqualTo(200);
        assertThat(expiredAtOfR9()).isNotEqualTo("null");
    }

    /// A skewed submit creates no job.
    @Test
    void skewedSubmit_isRefused_andNoJobIsCreated() throws Exception {
        var before = jobCount();

        var refused = harness.callWith("POST", "/jobs", mvnJob(BY_MAIN, "-pl", "skewed", "verify"), true, Map.of("Temper-Version", SKEWED));

        assertThat(refused.status()).isEqualTo(412);
        assertThat(jobCount()).isEqualTo(before);
    }

    /// The raw `expiredAt` of refusal R-9 in the list (`null` while active).
    private String expiredAtOfR9() throws Exception {
        var body = harness.get("/refusals?all=true")
                          .body();
        var r9 = body.substring(body.indexOf("\"code\":\"R-9\""));
        var value = r9.substring(r9.indexOf("\"expiredAt\":") + "\"expiredAt\":".length());
        return value.substring(0, value.indexOf('}'));
    }

    private long jobCount() throws Exception {
        return harness.get("/jobs?limit=1000")
                      .body()
                      .split("\"submittedBy\"", -1).length - 1;
    }

    /// G1 on the controller (transition rule): a CLI that sends no header predates build identity; its write proceeds.
    @Test
    void writeWithoutTheHeader_proceeds() throws Exception {
        var before = jobCount();

        var submitted = harness.call("POST", "/jobs", mvnJob(BY_MAIN, "-pl", "headerless", "verify"), true);

        assertThat(submitted.status()).isEqualTo(200);
        assertThat(jobCount()).isEqualTo(before + 1);
    }

    /// D-6 A1 (owner ruling): the dedup key's runner component is the whole build version. The same commit and recipe
    /// deduplicate within one build, and run again under a build of another judge, another `project.version`, or the
    /// same commit `-dirty` or `+unjudged`.
    @Test
    void dedup_holdsWithinOneBuild_butNotAcrossBuilds() throws Exception {
        var job = mvnJob(BY_MAIN, "-pl", "judges", "verify");
        var first = jobOf(harness.post("/jobs", job));
        harness.waitForState(first, "PASSED");

        try (var sameBuild = new ControllerHarness(harness.store, ControllerHarness.VERSION, TimeSpan.timeSpan(500).millis())) {
            assertThat(sameBuild.post("/jobs", job)
                                .body()).isEqualTo("{\"job\":" + first + ",\"state\":\"PASSED\",\"deduplicated\":true}");
        }
        for (var other : List.of(SKEWED, "0.2.0+0123456789ab", ControllerHarness.VERSION + "-dirty", "0.1.0+unjudged")) {
            try (var otherBuild = new ControllerHarness(harness.store, other, TimeSpan.timeSpan(500).millis())) {
                var reply = otherBuild.post("/jobs", job);

                assertThat(reply.body()).as(other).contains("\"deduplicated\":false");
                assertThat(jobOf(reply)).as(other).isNotEqualTo(first);
            }
        }
    }

    /// D-6 A1 leaves the supersede key `(ref, recipe)` as it was: a by-ref submit from a build of another judge, at a
    /// newer SHA of the ref, supersedes the earlier build's still-queued job of the same recipe.
    @Test
    void supersede_spansBuilds_itsKeyIsRefAndRecipeOnly() throws Exception {
        try (var store = Pg.freshStore();
             var thisBuild = new ControllerHarness(store, ControllerHarness.VERSION, TimeSpan.timeSpan(500).millis());
             var otherJudge = new ControllerHarness(store, SKEWED, TimeSpan.timeSpan(500).millis())) {
            var job = mvnJob(BY_MAIN, "-pl", "superseded", "verify");
            var first = jobOf(thisBuild.post("/jobs", job));
            otherJudge.refs.put("main", ControllerHarness.SHA_B);

            var second = jobOf(otherJudge.post("/jobs", job));

            assertThat(second).isNotEqualTo(first);
            assertThat(thisBuild.get("/jobs/" + first)
                                .body()).contains("\"superseded\":true");
        }
    }

    /// The route's pattern with every `{name}` bound to `1`.
    private static String pathOf(Route route) {
        return route.pattern()
                    .stream()
                    .map(segment -> segment.startsWith("{")
                                    ? "1"
                                    : segment)
                    .collect(Collectors.joining("/", "/", ""));
    }
}
