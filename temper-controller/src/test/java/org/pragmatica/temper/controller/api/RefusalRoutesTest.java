package org.pragmatica.temper.controller.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.temper.domain.refusal.RefusalRule;
import org.pragmatica.temper.store.api.RefusalRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `RefusalRoutesTest` (m1-design §6, §9.5): refusals as data over the API. A refusal no code can evaluate must not
/// exist, so an unknown matcher kind and a structural code are 400; a code that exists is 409, because codes are
/// never reused.
class RefusalRoutesTest {
    private final ApiFixture fixture = new ApiFixture(List.of());

    private static final String R_9 = "{\"code\":\"R-9\",\"matcher\":{\"excludeTestClass\":{\"simpleClassName\":\"FlakyIT\",\"disabledMarker\":\"#9\"}},"
                                      + "\"issue\":9,\"reason\":\"flaky until #9\"}";

    private static String add(String code, String matcher) {
        return "{\"code\":\"" + code + "\",\"matcher\":" + matcher + ",\"reason\":\"why\"}";
    }

    @Test
    void add_answers201_andTheRefusalIsListed() {
        var added = fixture.authed(HttpMethod.POST, "/api/v1/refusals", R_9);

        assertThat(added.status()).as(added.bodyText()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.bodyText()).contains("\"code\":\"R-9\"", "\"issue\":9", "\"expiredAt\":null");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/refusals", "").bodyText()).contains("\"code\":\"R-9\"");
    }

    @Test
    void add_answers409_codeExists() {
        fixture.authed(HttpMethod.POST, "/api/v1/refusals", R_9);

        var again = fixture.authed(HttpMethod.POST, "/api/v1/refusals", R_9);

        assertThat(again.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.bodyText()).contains("R-9");
    }

    @Test
    void add_answers400_structuralCode() {
        for (var code : List.of("R-ENV", "R-M2", "R-SHELL", "R-DISK")) {
            var response = fixture.authed(HttpMethod.POST, "/api/v1/refusals", R_9.replace("R-9", code));

            assertThat(response.status()).as(code).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(fixture.refusals.rows).isEmpty();
    }

    @Test
    void add_answers400_unknownMatcherKind_orBadCode() {
        assertThat(fixture.authed(HttpMethod.POST, "/api/v1/refusals", add("R-10", "{\"shellScript\":{\"run\":\"x\"}}")).status())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixture.authed(HttpMethod.POST, "/api/v1/refusals", R_9.replace("R-9", "r-lower")).status())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixture.refusals.rows).isEmpty();
    }

    @Test
    void list_hidesExpired_unlessAll() {
        var expired = RefusalRow.refusalRow(new RefusalRule.ItTestRequired("R-OLD", "forge-tests"), none(), "old", none(),
                                            some(Instant.parse("2026-10-01T00:00:00Z")));
        fixture.refusals.rows.add(expired);
        fixture.authed(HttpMethod.POST, "/api/v1/refusals", R_9);

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/refusals", "").bodyText()).contains("R-9").doesNotContain("R-OLD");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/refusals?all=true", "").bodyText()).contains("R-9", "R-OLD");
    }

    @Test
    void expire_answers200_withCode_or404() {
        fixture.authed(HttpMethod.POST, "/api/v1/refusals", R_9);

        var expired = fixture.authed(HttpMethod.POST, "/api/v1/refusals/R-9/expire", "");

        assertThat(expired.status()).isEqualTo(HttpStatus.OK);
        assertThat(expired.bodyText()).contains("\"code\":\"R-9\"", "\"expiredAt\"");
        assertThat(fixture.authed(HttpMethod.POST, "/api/v1/refusals/R-404/expire", "").status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void routes_requireTheToken() {
        assertThat(fixture.call(HttpMethod.GET, "/api/v1/refusals", "", Map.of()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(fixture.call(HttpMethod.POST, "/api/v1/refusals", R_9, Map.of()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
