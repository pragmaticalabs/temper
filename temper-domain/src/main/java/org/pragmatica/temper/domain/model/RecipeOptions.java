package org.pragmatica.temper.domain.model;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.some;


/// Per-submit recipe options, all part of the dedup key because each can change the verdict. Q-6: the top-level
/// `timeout` override. m4-design §9.3: `dedicatedCpu` (`--dedicated-cpu`, never granted to a `sharedCpu` host), since
/// timing-sensitive tests can judge differently on a shared CPU. Neither is in the supersede key, which is the recipe
/// alone (R-M4-16, Q-M4-9).
public record RecipeOptions(Option<UnitTimeout> timeout, boolean dedicatedCpu) {
    public static final RecipeOptions NONE = new RecipeOptions(Option.none(), false);

    public static RecipeOptions recipeOptions(Option<UnitTimeout> timeout) {
        return new RecipeOptions(timeout, false);
    }

    public static RecipeOptions recipeOptions(Option<UnitTimeout> timeout, boolean dedicatedCpu) {
        return new RecipeOptions(timeout, dedicatedCpu);
    }

    /// Canonical JSON for the dedup key; an absent option is omitted, never written as `null`, and `dedicatedCpu` is
    /// written only when true, so a job without it keys exactly as before M4.
    public String canonical() {
        return CanonicalJson.object(members());
    }

    private Map<String, String> members() {
        return Stream.of(timeout.map(value -> Map.entry("timeout",
                                                        CanonicalJson.string(value.value().toString()))),
                         some(Map.entry("dedicatedCpu", "true")).filter(_ -> dedicatedCpu))
                     .flatMap(Option::stream)
                     .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
