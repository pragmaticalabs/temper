package org.pragmatica.temper.ghapp;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.pragmatica.lang.Result;


/// The App's JWT (F6): RS256, `iat` a minute in the past against clock drift, `exp` nine minutes ahead
/// (GitHub allows ten), `iss` the client id. It travels only in the `Authorization` header of one mint request.
sealed interface Jwt {
    Duration BACKDATE = Duration.ofSeconds(60);
    Duration LIFETIME = Duration.ofMinutes(9);
    String HEADER = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
    Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    /// The signed JWT for `clientId` at `now`. `clientId` is restricted to `[A-Za-z0-9_.-]` by [AppAuthConfig], so it
    /// needs no JSON escaping.
    static Result<String> sign(String clientId, PrivateKey key, Instant now) {
        var payload = "{\"iat\":" + now.minus(BACKDATE).getEpochSecond()
                    + ",\"exp\":" + now.plus(LIFETIME).getEpochSecond()
                    + ",\"iss\":\"" + clientId
                    + "\"}";
        var unsigned = encode(HEADER) + "." + encode(payload);

        return signature(key, unsigned).map(signed -> unsigned + "." + signed);
    }

    private static String encode(String text) {
        return BASE64URL.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Result<String> signature(PrivateKey key, String unsigned) {
        return Result.lift(thrown -> AppAuthError.SigningFailed.FACTORY.apply(thrown.getClass().getSimpleName()),
                           () -> {
                               var signer = Signature.getInstance("SHA256withRSA");

                               signer.initSign(key);
                               signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));

                               return BASE64URL.encodeToString(signer.sign());
                           });
    }

    record unused() implements Jwt {}
}
