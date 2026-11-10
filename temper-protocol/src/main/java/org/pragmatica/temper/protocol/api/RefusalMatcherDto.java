package org.pragmatica.temper.protocol.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.refusal.RefusalRule;
import org.pragmatica.temper.domain.refusal.TestRunner;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// The `refusals.matcher` jsonb (spec §2.5, m0-design §5.2). The row's `code` column is passed to
/// [#toDomain]. Seed rows:
///
/// ```
/// {"excludeTestClass":{"simpleClassName":"StreamPublishReshuffleTest","disabledMarker":"#1553","runner":"failsafe"}}
/// {"itTestRequired":{"moduleSegment":"forge-tests"}}
/// ```
///
/// `runner` (`surefire` | `failsafe`, matched exactly) names the plugin that runs the class, so only that
/// plugin's selection property is judged; without it both are judged (conservative). [#from] is the inverse of
/// [#toDomain]; it is named like [RecipeDto#from], the other sealed DTO family built from a domain sum.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes({@JsonSubTypes.Type(value = RefusalMatcherDto.ExcludeTestClass.class, name = "excludeTestClass"), @JsonSubTypes.Type(value = RefusalMatcherDto.ItTestRequired.class, name = "itTestRequired")})
public sealed interface RefusalMatcherDto {
    Result<RefusalRule> toDomain(String code);

    /// The matcher of `rule`, as stored and listed; `toDomain(rule.code())` gives `rule` back.
    static RefusalMatcherDto from(RefusalRule rule) {
        return switch (rule) {
            case RefusalRule.ExcludeTestClass exclude -> new ExcludeTestClass(exclude.simpleClassName(),
                                                                              exclude.disabledMarker(),
                                                                              exclude.runner().map(RefusalMatcherDto::runnerWire));
            case RefusalRule.ItTestRequired itTest -> new ItTestRequired(itTest.moduleSegment());
        };
    }

    record ExcludeTestClass(String simpleClassName, String disabledMarker, Option<String> runner) implements RefusalMatcherDto {
        private static final Pattern CLASS_NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
        private static final Pattern MARKER = Pattern.compile("^[^\"\\\\\\n]+$");

        @Override
        public Result<RefusalRule> toDomain(String code) {
            return Result.all(Values.matching(simpleClassName, CLASS_NAME, "simpleClassName"),
                              Values.matching(disabledMarker, MARKER, "disabledMarker"),
                              Values.optional(runner, ExcludeTestClass::testRunner))
                         .map((className, marker, parsedRunner) -> RefusalRule.ExcludeTestClass.excludeTestClass(code,
                                                                                                                 className,
                                                                                                                 marker,
                                                                                                                 parsedRunner));
        }

        private static Result<TestRunner> testRunner(String raw) {
            return Option.from(Arrays.stream(TestRunner.values())
                                     .filter(candidate -> runnerWire(candidate).equals(raw))
                                     .findFirst()).toResult(invalid("runner").apply(raw));
        }
    }

    record ItTestRequired(String moduleSegment) implements RefusalMatcherDto {
        private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9._-]+$");

        @Override
        public Result<RefusalRule> toDomain(String code) {
            return Values.matching(moduleSegment, SEGMENT, "moduleSegment").map(segment -> RefusalRule.ItTestRequired.itTestRequired(code,
                                                                                                                                     segment));
        }
    }

    /// The wire form of a runner: its lower-case name.
    private static String runnerWire(TestRunner runner) {
        return runner.name()
                     .toLowerCase(Locale.ROOT);
    }
}
