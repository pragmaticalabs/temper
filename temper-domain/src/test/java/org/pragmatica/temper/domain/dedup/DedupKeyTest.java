package org.pragmatica.temper.domain.dedup;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;
import org.pragmatica.temper.domain.model.UnitTimeout;
import org.pragmatica.temper.domain.state.JobState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// A9 (domain part): canonicalisation, the digest material and the reusable-state table. The digest
/// itself is pinned in temper-protocol's `DigestsTest`.
class DedupKeyTest {
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";
    private static final Recipe VERIFY = ok(Recipe.mvn(List.of("-pl", "core", "verify")));

    @Test
    void canonical_mvn_isSortedCompactJsonWithArgsInOrder() {
        assertThat(DedupKey.canonical(VERIFY, RecipeOptions.NONE)).isEqualTo(
            "{\"options\":{},\"recipe\":{\"mvn\":{\"args\":[\"-pl\",\"core\",\"verify\"]}}}");
    }

    @Test
    void canonical_moduleTest_sortsKeysAndOmitsAbsentOptions() {
        var recipe = ok(Recipe.moduleTest(List.of("aether/ember"), Option.some("Foo*"), Option.none()));
        var options = RecipeOptions.recipeOptions(Option.some(ok(UnitTimeout.unitTimeout("PT90M"))));

        assertThat(DedupKey.canonical(recipe, options)).isEqualTo(
            "{\"options\":{\"timeout\":\"PT1H30M\"},\"recipe\":{\"moduleTest\":{\"pl\":[\"aether/ember\"],\"test\":\"Foo*\"}}}");
    }

    /// m4-design §9.3 (probe P13): `--dedicated-cpu` changes the key, so a plain resubmit never reuses a dedicated
    /// job's verdict, nor the reverse.
    @Test
    void canonical_dedicatedCpu_changesTheKey() {
        var dedicated = RecipeOptions.recipeOptions(Option.none(), true);

        assertThat(DedupKey.canonical(VERIFY, dedicated)).isEqualTo(
            "{\"options\":{\"dedicatedCpu\":true},\"recipe\":{\"mvn\":{\"args\":[\"-pl\",\"core\",\"verify\"]}}}");
        assertThat(DedupKey.material(SHA, VERIFY, dedicated, "0.1.0")).isNotEqualTo(DedupKey.material(SHA,
                                                                                                    VERIFY,
                                                                                                    RecipeOptions.NONE,
                                                                                                    "0.1.0"));
    }

    /// `dedicatedCpu` false is omitted, never written `false`: a job without it keys exactly as before M4.
    @Test
    void canonical_dedicatedCpuFalse_equalsThePreM4Canonical() {
        var timeout = Option.some(ok(UnitTimeout.unitTimeout("PT90M")));

        assertThat(RecipeOptions.recipeOptions(Option.none(), false).canonical()).isEqualTo("{}");
        assertThat(DedupKey.canonical(VERIFY, RecipeOptions.recipeOptions(Option.none(), false))).isEqualTo(
            DedupKey.canonical(VERIFY, RecipeOptions.NONE));
        assertThat(RecipeOptions.recipeOptions(timeout, false).canonical()).isEqualTo("{\"timeout\":\"PT1H30M\"}");
        assertThat(RecipeOptions.recipeOptions(timeout, true).canonical()).isEqualTo(
            "{\"dedicatedCpu\":true,\"timeout\":\"PT1H30M\"}");
    }

    @Test
    void canonical_escapesStrings_soArgumentsCannotForgeStructure() {
        var forged = ok(Recipe.mvn(List.of("a\",\"b")));
        var split = ok(Recipe.mvn(List.of("a", "b")));

        assertThat(DedupKey.canonical(forged, RecipeOptions.NONE)).isEqualTo(
            "{\"options\":{},\"recipe\":{\"mvn\":{\"args\":[\"a\\\",\\\"b\"]}}}");
        assertThat(DedupKey.canonical(forged, RecipeOptions.NONE)).isNotEqualTo(DedupKey.canonical(split, RecipeOptions.NONE));
    }

    @Test
    void material_lengthPrefixesEveryField() {
        var canonical = DedupKey.canonical(VERIFY, RecipeOptions.NONE);

        assertThat(DedupKey.material(SHA, VERIFY, RecipeOptions.NONE, "0.1.0")).isEqualTo(
            "40:" + SHA + "\n" + canonical.length() + ":" + canonical + "\n5:0.1.0");
        assertThat(DedupKey.material("ab", VERIFY, RecipeOptions.NONE, "c")).isNotEqualTo(DedupKey.material("a",
                                                                                                           VERIFY,
                                                                                                           RecipeOptions.NONE,
                                                                                                           "bc"));
    }

    @Test
    void dedupKey_acceptsOnly64LowercaseHex() {
        var hex = "a".repeat(64);

        assertThat(ok(DedupKey.dedupKey(hex)).value()).isEqualTo(hex);
        assertThat(failure(DedupKey.dedupKey("A".repeat(64)))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(DedupKey.dedupKey("a".repeat(63)))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @ParameterizedTest
    @CsvSource({"QUEUED,true", "UNKNOWN,true", "RUNNING,true", "PASSED,true", "FAILED,true", "EMPTY,true", "CANCELLING,false",
                "SUPERSEDED,false", "VOID,false", "ERROR,false", "CANCELLED,false", "REFUSED,false"})
    void reusable_pinsSpecTable(JobState state, boolean reusable) {
        assertThat(DedupKey.reusable(state)).isEqualTo(reusable);
    }
}
