package org.pragmatica.temper.controller.t2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The `freshToken`: `v1.<checkedAtMs>.<fleetSeq>.<base64url(HMAC-SHA256(key, "v1|"+repo+"|"+pr
/// +"|"+sha+"|"+base+"|"+baseTip+"|"+verdictId+"|"+fleetEpoch+"|"+fleetSeq+"|"+checkedAtMs))>`; `baseTip` is the empty
/// string when the reply served none. The key is 32 random bytes made when
/// the controller starts and held in memory only, so a restart voids every outstanding token (the gate is re-run) and
/// `pr-fresh` stays a READ route: nothing is stored.
public final class FreshToken {
    private static final String ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec key;

    private FreshToken(SecretKeySpec key) {
        this.key = key;
    }

    /// A new key from `random`.
    public static FreshToken freshToken(SecureRandom random) {
        var bytes = new byte[KEY_BYTES];

        random.nextBytes(bytes);

        return new FreshToken(new SecretKeySpec(bytes, ALGORITHM));
    }

    /// What a token binds: the gate's evaluation of (repo, pr, sha) at `base` with tip `baseTip`, on verdict row `verdictId`, after fleet
    /// head (`fleetEpoch`, `fleetSeq`), at `checkedAtMs`.
    public record Fields(String repo,
                         long pr,
                         String sha,
                         String base,
                         String baseTip,
                         long verdictId,
                         String fleetEpoch,
                         long fleetSeq,
                         long checkedAtMs) {
        public static Fields fields(String repo,
                                    long pr,
                                    String sha,
                                    String base,
                                    String baseTip,
                                    long verdictId,
                                    String fleetEpoch,
                                    long fleetSeq,
                                    long checkedAtMs) {
            return new Fields(repo, pr, sha, base, baseTip, verdictId, fleetEpoch, fleetSeq, checkedAtMs);
        }

        String message() {
            return String.join("|",
                               "v1",
                               repo,
                               Long.toString(pr),
                               sha,
                               base,
                               baseTip,
                               Long.toString(verdictId),
                               fleetEpoch,
                               Long.toString(fleetSeq),
                               Long.toString(checkedAtMs));
        }
    }

    /// The token for `fields`. HMAC-SHA256 is a JCA algorithm every Java platform must provide, so a failure here is a
    /// broken runtime, answered as the caller's 500.
    public Result<String> token(Fields fields) {
        return Result.lift(Causes::fromThrowable,
                           () -> Mac.getInstance(ALGORITHM))
                     .flatMap(this::keyed)
                     .map(mac -> mac.doFinal(fields.message().getBytes(StandardCharsets.UTF_8)))
                     .map(FreshToken::encoded)
                     .map(mac -> "v1." + fields.checkedAtMs() + "." + fields.fleetSeq() + "." + mac);
    }

    /// Whether `token` is the one [#token] makes for `fields`, compared in constant time.
    public Result<Boolean> valid(String token, Fields fields) {
        return token(fields).map(expected -> MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                                                   token.getBytes(StandardCharsets.UTF_8)));
    }

    private Result<Mac> keyed(Mac mac) {
        return Result.lift(Causes::fromThrowable,
                           () -> mac.init(key))
                     .map(_ -> mac);
    }

    private static String encoded(byte[] mac) {
        return Base64.getUrlEncoder()
                     .withoutPadding()
                     .encodeToString(mac);
    }
}
