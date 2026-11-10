package org.pragmatica.temper.protocol.digest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.dedup.DedupKey;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;


/// SHA-256 over UTF-8 text, and the dedup key built from it (spec §2.3). SHA-256 is mandatory on every Java
/// platform, so the failure path (the JDK's checked `NoSuchAlgorithmException`) is unreachable in practice;
/// it stays a `Result` because the JDK API declares it.
public sealed interface Digests {
    /// Lowercase hex SHA-256 of `text`'s UTF-8 bytes.
    static Result<String> sha256Hex(String text) {
        return Result.lift(Causes::fromThrowable,
                           () -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))));
    }

    /// `sha256(DedupKey.material(...))` as a validated [DedupKey].
    static Result<DedupKey> dedupKey(String sourceIdentity,
                                     Recipe recipe,
                                     RecipeOptions options,
                                     String runnerVersion) {
        return sha256Hex(DedupKey.material(sourceIdentity, recipe, options, runnerVersion)).flatMap(DedupKey::dedupKey);
    }

    record unused() implements Digests {}
}
