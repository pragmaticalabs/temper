package org.pragmatica.temper.controller.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;

import static org.assertj.core.api.Assertions.assertThat;

/// `PUT subscribers/{name}/selectors` (m2-design §2.3) through the real [SubscriberRoutes] over the fake stores: the
/// `SubscriberView` wire names, the set algebra the store answers, a 404 for an added unknown job (and nothing changed),
/// and a 400 for a selector without its prefix.
class SubscriberRoutesTest {
    private static final String PATH = "/api/v1/subscribers/cto/selectors";
    private final ApiFixture fixture = new ApiFixture(List.of());

    private static String body(String add, String remove) {
        return "{\"add\":[" + add + "],\"remove\":[" + remove + "]}";
    }

    @Test
    void put_answers200_withTheResultingSetSortedAndTheCursor() {
        fixture.jobs.rows.put(JobId.jobId(4711).unwrap(), Rows.job(4711, JobState.RUNNING, List.of()));

        var response = fixture.authed(HttpMethod.PUT, PATH, body("\"ref:main\",\"job:4711\"", ""));

        assertThat(response.status()).as(response.bodyText()).isEqualTo(HttpStatus.OK);
        assertThat(response.bodyText()).isEqualTo("{\"name\":\"cto\",\"selectors\":[\"job:4711\",\"ref:main\"],\"cursor\":0}");

        var removed = fixture.authed(HttpMethod.PUT, PATH, body("", "\"job:4711\",\"ref:main\""));

        assertThat(removed.bodyText()).isEqualTo("{\"name\":\"cto\",\"selectors\":[],\"cursor\":0}");
    }

    @Test
    void put_absentLists_areEmpty() {
        assertThat(fixture.authed(HttpMethod.PUT, PATH, "{}").bodyText()).isEqualTo("{\"name\":\"cto\",\"selectors\":[],\"cursor\":0}");
    }

    @Test
    void put_answers404_forAnAddedUnknownJob_andChangesNothing() {
        var response = fixture.authed(HttpMethod.PUT, PATH, body("\"ref:main\",\"job:4711\"", ""));

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.bodyText()).contains("4711");
        assertThat(fixture.events.subscribers).doesNotContainKey("cto");
    }

    /// Every added job is looked up, not only the first.
    @Test
    void put_answers404_whenAnyAddedJobIsUnknown() {
        fixture.jobs.rows.put(JobId.jobId(4711).unwrap(), Rows.job(4711, JobState.RUNNING, List.of()));

        var response = fixture.authed(HttpMethod.PUT, PATH, body("\"job:4711\",\"job:4712\"", ""));

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.bodyText()).contains("4712");
        assertThat(fixture.events.subscribers).doesNotContainKey("cto");
    }

    /// The request is judged before any lookup: a bad name with an unknown job is the 400, not the 404.
    @Test
    void put_badName_withAnUnknownJob_is400() {
        assertThat(fixture.authed(HttpMethod.PUT, "/api/v1/subscribers/bad!name/selectors", body("\"job:4711\"", "")).status())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void put_nullSelector_is400_nullList_isEmpty() {
        assertThat(fixture.authed(HttpMethod.PUT, PATH, "{\"add\":[null]}").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixture.authed(HttpMethod.PUT, PATH, "{\"remove\":[null]}").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixture.events.subscribers).doesNotContainKey("cto");
        assertThat(fixture.authed(HttpMethod.PUT, PATH, "{\"add\":null,\"remove\":null}").bodyText())
            .isEqualTo("{\"name\":\"cto\",\"selectors\":[],\"cursor\":0}");
    }

    /// Every malformed body is a 400, never a failed handler (a 500).
    @Test
    void put_oddBodies_are400() {
        for (var odd : List.of("null", "[]", "\"x\"", "{\"add\":\"job:1\"}", "{\"add\":[1]}", "{\"add\":[{}]}", "{\"add\":[[]]}",
                               "{\"bogus\":[]}", "")) {
            assertThat(fixture.authed(HttpMethod.PUT, PATH, odd).status()).as(odd).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(fixture.events.subscribers).doesNotContainKey("cto");
    }

    /// A 400 names one reason, never nested `Composite:` headers (the name and both lists are judged together).
    @Test
    void put_badRequest_namesOneReason_withoutCompositeHeaders() {
        var selector = fixture.authed(HttpMethod.PUT, PATH, body("\"1\"", "\"main\""));
        var both = fixture.authed(HttpMethod.PUT, "/api/v1/subscribers/bad!name/selectors", body("\"1\"", ""));

        assertThat(selector.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(selector.bodyText()).contains("Invalid selector: '1'").doesNotContain("Composite").doesNotContain("main");
        assertThat(both.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(both.bodyText()).contains("Invalid subscriber: 'bad!name'").doesNotContain("Composite");
    }

    @Test
    void put_removingAnUnknownJob_isNotLookedUp() {
        assertThat(fixture.authed(HttpMethod.PUT, PATH, body("", "\"job:4711\"")).status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void put_answers400_forABareOrShortSelector_orABadName() {
        for (var selector : List.of("\"4711\"", "\"main\"", "\"sha:0cf5631\"", "\"job:0\"")) {
            assertThat(fixture.authed(HttpMethod.PUT, PATH, body(selector, "")).status()).as(selector).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(fixture.authed(HttpMethod.PUT, "/api/v1/subscribers/bad%20name/selectors", body("\"ref:main\"", "")).status())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixture.authed(HttpMethod.PUT, PATH, "not json").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixture.events.subscribers).doesNotContainKey("cto");
    }
}
