package org.pragmatica.temper.controller.http;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.Headers;
import org.pragmatica.temper.protocol.digest.Digests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The bearer token's id (a fleet subscriber's binding), the scheme check, and a `toString` that prints nothing.
class BearerTokenTest {
    @Test
    void id_isTheFirst16HexOfTheSha256() {
        var token = BearerToken.bearerToken("read-token").unwrap();

        assertThat(token.id()).isEqualTo(Digests.sha256Hex("read-token").unwrap().substring(0, 16));
    }

    @Test
    void presented_onlyTheBearerScheme_caseInsensitive() {
        assertThat(BearerToken.presented(Headers.fromSingleValueMap(Map.of("Authorization", "bearer  abc ")))).isEqualTo(some("abc"));
        assertThat(BearerToken.presented(Headers.fromSingleValueMap(Map.of("Authorization", "Basic abc")))).isEqualTo(none());
        assertThat(BearerToken.presented(Headers.fromSingleValueMap(Map.of()))).isEqualTo(none());
    }

    @Test
    void toString_printsNoDigest() {
        assertThat(BearerToken.bearerToken("t").unwrap()).hasToString("BearerToken[***]");
    }
}
