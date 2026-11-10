package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.mvn.UserProperties;
import org.pragmatica.temper.domain.mvn.UserProperties.TestEffect;


/// One `-D` property or reactor-narrowing option the user wrote into the job's arguments, as `JobView`,
/// `ResultRecordDto` and the digest's `finished` events carry it (m4-design §5.1, maintainer ruling Q-M4-11c):
/// `{"define": "-DskipTests=true", "tests": "skipped"}`, `{"define": "-pl core", "tests": "narrowed"}`, `tests` being
/// `skipped`, `narrowed`, `unknown` (an option temper does not list, maintainer ruling Q-M4-11d), or absent for a property that
/// does neither. `define` is the argument as shown (the field keeps
/// its name for a narrowing option too, so the wire shape does not change). Derived from the stored recipe
/// whenever a view is built, never stored as its own fact; only the `mvn` recipe carries user arguments (the typed recipes'
/// `-D` tokens are the planner's).
public record UserPropertyDto(String define, Option<String> tests) {
    public static UserPropertyDto userPropertyDto(String define, Option<String> tests) {
        return new UserPropertyDto(define, tests);
    }

    /// The user properties of `recipe`, in argv order.
    public static List<UserPropertyDto> of(RecipeDto recipe) {
        return switch (recipe) {
            case RecipeDto.Mvn mvn -> UserProperties.shown(mvn.args()).stream().map(shown -> userPropertyDto(shown.argument(),
                                                                                                             shown.effect()
                                                                                                                  .map(TestEffect::wire))).toList();
            case RecipeDto.ModuleTest _, RecipeDto.Gate _, RecipeDto.Mutate _, RecipeDto.Rerun _ -> List.of();
        };
    }

    /// The text field of a one-line view: `tests-skipped`, `tests-narrowed`, `tests-unknown`, or `property`.
    public String field() {
        return tests.map(effect -> "tests-" + effect)
                    .or("property");
    }

    /// The one-line token every text surface prints (CLI lines, digest text, event lines): `tests-skipped="-DskipTests=true"`,
    /// the value always quoted by [LineText#quoted], so no stored value, however written, can break or forge a line.
    public String token() {
        return field() + "=" + LineText.quoted(define);
    }
}
