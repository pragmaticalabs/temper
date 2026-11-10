package org.pragmatica.temper.controller.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.pragmatica.http.Headers;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.digest.Digests;


/// A bearer token (spec §7): every route requires `Authorization: Bearer <token>` (Q-1), the full token or, on a READ
/// route, the read token ([Credentials]). Only the token's SHA-256 is held, and only [#bearerToken] can build one (a
/// class, not a record, so no public canonical constructor can be handed a raw token by mistake). A presented token is
/// hashed and the digests are compared in constant time (`MessageDigest.isEqual`), so timing leaks neither a prefix
/// nor the length. `toString` never prints the digest.
public final class BearerToken {
    private static final String SCHEME = "Bearer ";
    private static final int ID_HEX = 16;

    private final byte[] sha256;

    private BearerToken(String sha256Hex) {
        this.sha256 = sha256Hex.getBytes(StandardCharsets.US_ASCII);
    }

    public static Result<BearerToken> bearerToken(String token) {
        return Digests.sha256Hex(token).map(BearerToken::new);
    }

    /// The token a request presents: the `Authorization` value after a case-insensitive `Bearer ` scheme, trimmed.
    public static Option<String> presented(Headers headers) {
        return headers.get("Authorization")
                      .filter(value -> value.regionMatches(true,
                                                           0,
                                                           SCHEME,
                                                           0,
                                                           SCHEME.length()))
                      .map(value -> value.substring(SCHEME.length())
                                         .trim());
    }

    /// The token's id, the first 16 hex digits of its SHA-256: what a fleet subscriber is bound to (R2-f, the same id
    /// `FleetRoutes.tokenId` takes from a presented token). A prefix of the digest, never the token.
    public String id() {
        return new String(sha256, StandardCharsets.US_ASCII).substring(0, ID_HEX);
    }

    /// The constant-time check of a presented token against this one.
    public boolean matches(String presented) {
        return Digests.sha256Hex(presented)
                      .map(this::sameDigest)
                      .or(false);
    }

    private boolean sameDigest(String presentedSha256) {
        return MessageDigest.isEqual(sha256, presentedSha256.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public String toString() {
        return "BearerToken[***]";
    }
}
