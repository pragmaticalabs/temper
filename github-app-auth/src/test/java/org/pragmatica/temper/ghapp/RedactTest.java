package org.pragmatica.temper.ghapp;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// What may be stored as a mint failure's reason (M2). Mutation: return the exception's full text.
class RedactTest {
    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJ4In0.c2lnbmF0dXJl";

    @Test
    void reason_isTheClassAndAtMostTwoHundredCharacters() {
        var reason = Redact.reason(new CompletionException(new ConnectException("refused " + "x".repeat(5000))));

        assertThat(reason).startsWith("ConnectException: refused").hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void reason_ofAnyOtherException_isTheClassNameAlone_soServerBytesStayOut() {
        assertThat(Redact.reason(new IOException("invalid status line: " + JWT))).isEqualTo("IOException");
    }

    @Test
    void reason_ofAClientSideException_keepsOnlyTheFirstLine_andMasksSecrets() {
        var reason = Redact.reason(new ConnectException("down " + JWT
                                                       + " ghs_abc123 github_pat_AbC_1 ghp_xyz\nat Foo.bar(Foo.java:1)"));

        assertThat(reason).isEqualTo("ConnectException: down *** *** *** ***");
    }

    @Test
    void text_masksBearerAndTokenShapes_thenCaps() {
        assertThat(Redact.text("Authorization: Bearer " + JWT, 100)).doesNotContain("eyJ").doesNotContain(JWT);
        assertThat(Redact.text("a ghs_secret b", 100)).isEqualTo("a *** b");
        assertThat(Redact.text("y".repeat(500), 300)).hasSize(300);
    }
}
