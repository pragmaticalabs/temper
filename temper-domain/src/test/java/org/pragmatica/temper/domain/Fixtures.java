package org.pragmatica.temper.domain;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.plan.PlanDefaults;
import org.pragmatica.temper.domain.plan.Planner;

import static org.junit.jupiter.api.Assertions.fail;

/// Test helpers: unwrap results, plan units.
public final class Fixtures {
    private Fixtures() {}

    public static <T> T ok(Result<T> result) {
        return result.fold(cause -> fail("expected success, got: " + cause.message()), value -> value);
    }

    public static <T> Cause failure(Result<T> result) {
        return result.fold(cause -> cause, value -> fail("expected failure, got: " + value));
    }

    /// Plan defaults WITHOUT port-group modules: 6 GiB units, a 16 GiB Gate (Q-M1-13).
    public static PlanDefaults noPortGroup() {
        return PlanDefaults.planDefaults(ok(MemoryMb.memoryMb(6144)), ok(MemoryMb.memoryMb(16384)), List.of());
    }

    public static PlanDefaults portGroup(String... modules) {
        return PlanDefaults.planDefaults(ok(MemoryMb.memoryMb(6144)), ok(MemoryMb.memoryMb(16384)), List.of(modules));
    }

    /// The single unit `recipe` plans to with `defaults` and an unknown reactor.
    public static UnitSpec plannedUnit(Recipe recipe, PlanDefaults defaults) {
        return ok(Planner.plan(recipe, RecipeOptions.NONE, defaults, Option.none())).getFirst();
    }

    /// The single unit an `Mvn` recipe with `args` plans to (no port-group modules configured).
    public static UnitSpec mvnUnit(String... args) {
        return plannedUnit(ok(Recipe.mvn(List.of(args))), noPortGroup());
    }

    /// The single unit a `ModuleTest` recipe plans to (no port-group modules configured).
    public static UnitSpec moduleTestUnit(List<String> pl, Option<String> test, Option<String> itTest) {
        return plannedUnit(ok(Recipe.moduleTest(pl, test, itTest)), noPortGroup());
    }

    /// The single unit of a Gate (no port-group modules configured).
    public static UnitSpec gateUnit() {
        return plannedUnit(Recipe.gate(), noPortGroup());
    }
}
