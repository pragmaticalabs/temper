package org.pragmatica.temper.controller.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.temper.store.api.KnownListing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;

/// `KnownRoutes` (m2-design §4.4): the registry over the API, the refusal surface's shape. An entry that does not parse
/// is 400, a second active entry for one test is 409, an unknown id is 404, and an expiry is a POST that keeps the
/// first one.
class KnownRoutesTest {
    private static final String ENTRY = "{\"testId\":\"org.x.EmberAmnesiacRestartTest\",\"issue\":1679,\"reason\":\"flaky\","
                                        + "\"until\":\"#1679 closes\",\"createdBy\":\"cto\"}";

    private final ApiFixture fixture = new ApiFixture(List.of());

    @Test
    void add_answers201_withTheStoredEntry_andItIsListed() {
        var added = fixture.authed(HttpMethod.POST, "/api/v1/known", ENTRY);

        assertThat(added.status()).as(added.bodyText()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.bodyText()).contains("\"id\":1",
                                              "\"testId\":\"org.x.EmberAmnesiacRestartTest\"",
                                              "\"issue\":1679",
                                              "\"until\":\"#1679 closes\"",
                                              "\"createdBy\":\"cto\"",
                                              "\"createdAt\":\"2026-10-19T10:00:00Z\"",
                                              "\"expiredAt\":null");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/known", "").bodyText()).contains("\"id\":1");
    }

    @Test
    void add_answers409_whileAnEntryForTheTestIsActive_butNotAfterItsExpiry() {
        fixture.authed(HttpMethod.POST, "/api/v1/known", ENTRY);

        var again = fixture.authed(HttpMethod.POST, "/api/v1/known", ENTRY);

        assertThat(again.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.bodyText()).contains("org.x.EmberAmnesiacRestartTest");

        fixture.authed(HttpMethod.POST, "/api/v1/known/1/expire", "");
        assertThat(fixture.authed(HttpMethod.POST, "/api/v1/known", ENTRY).status()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void add_answers400_forEveryFieldThatDoesNotParse() {
        for (var body : List.of(ENTRY.replace("org.x.EmberAmnesiacRestartTest", "org.x.#case"),
                                ENTRY.replace("org.x.EmberAmnesiacRestartTest", "FooTest#"),
                                ENTRY.replace("1679,", "0,"),
                                ENTRY.replace("\"reason\":\"flaky\"", "\"reason\":\" \""),
                                ENTRY.replace("\"until\":\"#1679 closes\"", "\"until\":\"\""),
                                ENTRY.replace(",\"until\":\"#1679 closes\"", ""),
                                ENTRY.replace(",\"issue\":1679", ""),
                                ENTRY.replace("\"cto\"", "\"c-to\""),
                                ENTRY.replace("}", ",\"extra\":1}"),
                                ENTRY.replace("EmberAmnesiacRestartTest", "FooTest#x\\u0000"),
                                ENTRY.replace("EmberAmnesiacRestartTest", "FooTest#x\\ud800"),
                                ENTRY.replace("\"reason\":\"flaky\"", "\"reason\":\"fl\\u0000aky\""),
                                ENTRY.replace("\"reason\":\"flaky\"", "\"reason\":\"flaky\\udc00\""),
                                ENTRY.replace("\"until\":\"#1679 closes\"", "\"until\":\"#1679\\u0000\""),
                                ENTRY.replace("\"until\":\"#1679 closes\"", "\"until\":\"\\ud83d#1679\""),
                                "not json")) {
            var response = fixture.authed(HttpMethod.POST, "/api/v1/known", body);

            assertThat(response.status()).as(body + " -> " + response.bodyText()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(fixture.known.rows).isEmpty();
    }

    @Test
    void list_hidesExpired_unlessAll() {
        fixture.authed(HttpMethod.POST, "/api/v1/known", ENTRY);
        fixture.authed(HttpMethod.POST, "/api/v1/known", ENTRY.replace("EmberAmnesiacRestartTest", "OtherTest#one"));
        fixture.authed(HttpMethod.POST, "/api/v1/known/1/expire", "");

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/known", "").bodyText()).contains("OtherTest#one")
                                                                                .doesNotContain("EmberAmnesiacRestartTest");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/known?all=false", "").bodyText()).doesNotContain("EmberAmnesiacRestartTest");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/known?all=true", "").bodyText()).contains("OtherTest#one",
                                                                                                    "EmberAmnesiacRestartTest");
    }

    @Test
    void expire_answers200_keepingTheFirstExpiry_or404_or400() {
        fixture.authed(HttpMethod.POST, "/api/v1/known", ENTRY);

        var first = fixture.authed(HttpMethod.POST, "/api/v1/known/1/expire", "");
        var second = fixture.authed(HttpMethod.POST, "/api/v1/known/1/expire", "");

        assertThat(first.status()).isEqualTo(HttpStatus.OK);
        assertThat(first.bodyText()).contains("\"id\":1", "\"expiredAt\":\"2026-10-19T11:00:00Z\"");
        assertThat(second.bodyText()).isEqualTo(first.bodyText());
        assertThat(fixture.authed(HttpMethod.POST, "/api/v1/known/404/expire", "").status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fixture.authed(HttpMethod.POST, "/api/v1/known/x/expire", "").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixture.authed(HttpMethod.POST, "/api/v1/known/0/expire", "").status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /// Ruling R-M2-15: a row the store could not read is listed with `unreadable` (its id, stored values and why), never
    /// hidden, and expiring it answers 200.
    @Test
    void unreadableRow_isListed_andExpires200() {
        fixture.known.unreadable.add(KnownListing.Unreadable.unreadable(41, "org.x.UnreadableTest", 7, " ", "u", "cto",
                                                                        Instant.parse("2026-10-19T09:00:00Z"), none(),
                                                                        "Invalid reason: ' '"));

        var listed = fixture.authed(HttpMethod.GET, "/api/v1/known", "");
        var expired = fixture.authed(HttpMethod.POST, "/api/v1/known/41/expire", "");

        assertThat(listed.status()).isEqualTo(HttpStatus.OK);
        assertThat(listed.bodyText()).contains("\"id\":41", "\"testId\":\"org.x.UnreadableTest\"", "\"unreadable\":\"Invalid reason: ' '\"");
        assertThat(expired.status()).as(expired.bodyText()).isEqualTo(HttpStatus.OK);
        assertThat(expired.bodyText()).contains("\"id\":41", "\"expiredAt\":\"2026-10-19T11:00:00Z\"", "\"unreadable\":");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/known", "").bodyText()).as("expired: hidden unless all").doesNotContain("\"id\":41");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/known?all=true", "").bodyText()).contains("\"id\":41");
    }

    @Test
    void expire_isNotADelete() {
        fixture.authed(HttpMethod.POST, "/api/v1/known", ENTRY);

        assertThat(fixture.authed(HttpMethod.DELETE, "/api/v1/known/1", "").status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fixture.known.rows).hasSize(1);
    }

    @Test
    void routes_requireTheToken_andTheWriteRoutesTheFullOne() {
        assertThat(fixture.call(HttpMethod.GET, "/api/v1/known", "", Map.of()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(fixture.call(HttpMethod.POST, "/api/v1/known", ENTRY, Map.of()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(fixture.readAuthed(HttpMethod.GET, "/api/v1/known", "").status()).isEqualTo(HttpStatus.OK);
        assertThat(fixture.readAuthed(HttpMethod.POST, "/api/v1/known", ENTRY).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixture.readAuthed(HttpMethod.POST, "/api/v1/known/1/expire", "").status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixture.known.rows).isEmpty();
    }
}
