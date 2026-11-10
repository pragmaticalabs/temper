package org.pragmatica.temper.controller.http;

import java.security.SecureRandom;
import java.util.Base64;

import org.pragmatica.http.Headers;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.task.ClaimTask;
import org.pragmatica.temper.protocol.digest.Digests;


/// The lease token (m3-design §6.4): 32 random bytes minted at a claim, returned once as unpadded base64url (43
/// header-safe characters) and stored only as its sha256 (`tasks.lease_hash`). A lease-token call presents it as
/// `Authorization: Lease <token>`; the store compares the presented token's sha256 with the row's in its conditional
/// UPDATE, so no comparison of the token itself happens in the controller.
public sealed interface LeaseToken {
    String SCHEME = "Lease ";
    int BYTES = 32;

    /// The token a request presents: the `Authorization` value after a case-insensitive `Lease ` scheme, trimmed.
    static Option<String> presented(Headers headers) {
        return headers.get("Authorization")
                      .filter(value -> value.regionMatches(true,
                                                           0,
                                                           SCHEME,
                                                           0,
                                                           SCHEME.length()))
                      .map(value -> value.substring(SCHEME.length())
                                         .trim());
    }

    /// The sha256 the store keeps for `token`.
    static Result<String> hash(String token) {
        return Digests.sha256Hex(token);
    }

    /// A fresh token from the platform's strong random source.
    static Result<ClaimTask.Minted> mint(SecureRandom random) {
        var bytes = new byte[BYTES];

        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        return hash(token).map(hash -> ClaimTask.Minted.minted(token, hash));
    }

    record unused() implements LeaseToken {}
}
