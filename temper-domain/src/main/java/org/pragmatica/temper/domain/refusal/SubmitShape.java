package org.pragmatica.temper.domain.refusal;

import java.util.List;

import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.UnitSpec;


/// What the submit-time refusals judge: whether the request carried an `env` field, the user's own `Mvn` arguments
/// (before the planner adds its tokens; none for the typed recipes, whose `-D` tokens the planner writes), and the
/// planned units.
public record SubmitShape(boolean envFieldPresent, List<String> userArgs, List<UnitSpec> units) {
    public SubmitShape {
        userArgs = List.copyOf(userArgs);
        units = List.copyOf(units);
    }

    public static SubmitShape submitShape(boolean envFieldPresent, Recipe recipe, List<UnitSpec> units) {
        return new SubmitShape(envFieldPresent, userArgs(recipe), units);
    }

    /// The arguments the user wrote. A new recipe kind decides here whether it carries raw user arguments.
    private static List<String> userArgs(Recipe recipe) {
        return switch (recipe) {
            case Recipe.Mvn mvn -> mvn.values();
            case Recipe.ModuleTest _, Recipe.Gate _, Recipe.Rerun _, Recipe.Unsupported _ -> List.of();
        };
    }
}
