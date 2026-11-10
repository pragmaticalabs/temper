package org.pragmatica.temper.protocol.digest;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;
import org.pragmatica.temper.domain.model.UnitTimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A9 (digest part): the dedup key is a stable SHA-256 that changes with every input that changes what runs.
class DigestsTest {
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";

    private static <T> T ok(Result<T> result) {
        return result.fold(cause -> fail("expected success, got: " + cause.message()), value -> value);
    }

    private static final Recipe VERIFY = ok(Recipe.mvn(List.of("-pl", "core", "verify")));

    private static String key(String identity, Recipe recipe, RecipeOptions options, String version) {
        return ok(Digests.dedupKey(identity, recipe, options, version)).value();
    }

    @Test
    void sha256Hex_matchesKnownVector() {
        assertThat(ok(Digests.sha256Hex("abc"))).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void dedupKey_isStable64Hex() {
        assertThat(key(SHA, VERIFY, RecipeOptions.NONE, "0.1.0")).matches("^[0-9a-f]{64}$")
                                                               .isEqualTo(key(SHA,
                                                                              ok(Recipe.mvn(List.of("-pl", "core", "verify"))),
                                                                              RecipeOptions.NONE,
                                                                              "0.1.0"));
    }

    @Test
    void dedupKey_changesWithEveryInput() {
        var base = key(SHA, VERIFY, RecipeOptions.NONE, "0.1.0");
        var timeout = RecipeOptions.recipeOptions(Option.some(ok(UnitTimeout.unitTimeout("PT2M"))));

        assertThat(key(SHA.replace('0', '1'), VERIFY, RecipeOptions.NONE, "0.1.0")).isNotEqualTo(base);
        assertThat(key(SHA, ok(Recipe.mvn(List.of("verify", "-pl", "core"))), RecipeOptions.NONE, "0.1.0")).isNotEqualTo(base);
        assertThat(key(SHA, VERIFY, timeout, "0.1.0")).isNotEqualTo(base);
        assertThat(key(SHA, VERIFY, RecipeOptions.NONE, "0.1.1")).isNotEqualTo(base);
    }
}
