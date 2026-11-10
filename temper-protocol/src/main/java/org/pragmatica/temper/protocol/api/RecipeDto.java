package org.pragmatica.temper.protocol.api;

import java.time.Duration;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.ModulePath;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.TestSelection;
import org.pragmatica.temper.domain.model.Values;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Submit `recipe` (spec §2.1): `mvn`, `moduleTest`, `gate` (M1, `Recipe.Gate`), and `mutate`, which parses into
/// `Recipe.Unsupported` so submit refuses it as `REFUSED(unsupported)` until M3. Also the `jobs.recipe` jsonb, where
/// `rerun` (M2, m2-design §5.2) is the recipe of a rerun job; `POST /jobs` does not take it ([SubmitRequest]).
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes({@JsonSubTypes.Type(value = RecipeDto.Mvn.class, name = "mvn"), @JsonSubTypes.Type(value = RecipeDto.ModuleTest.class, name = "moduleTest"), @JsonSubTypes.Type(value = RecipeDto.Gate.class, name = "gate"), @JsonSubTypes.Type(value = RecipeDto.Mutate.class, name = "mutate"), @JsonSubTypes.Type(value = RecipeDto.Rerun.class, name = "rerun")})
public sealed interface RecipeDto {
    Result<Recipe> toDomain();

    record Mvn(List<String> args) implements RecipeDto {
        @Override
        public Result<Recipe> toDomain() {
            return Recipe.mvn(args);
        }
    }

    record ModuleTest(List<String> pl, Option<String> test, Option<String> itTest) implements RecipeDto {
        @Override
        public Result<Recipe> toDomain() {
            return Recipe.moduleTest(pl, test, itTest);
        }
    }

    record Gate() implements RecipeDto {
        @Override
        public Result<Recipe> toDomain() {
            return success(Recipe.gate());
        }
    }

    /// `spec` is bb-mutate's format, kept opaque until M3.
    record Mutate(Option<Object> spec) implements RecipeDto {
        @Override
        public Result<Recipe> toDomain() {
            return success(Recipe.unsupported("mutate"));
        }
    }

    /// `{"rerun": {"of": 4711, "classes": [{"class", "module", "runner"}], "original": {"job", "verdict", "reason",
    /// "failed": [{"class", "name", "known"}]}, "bound": {"millis", "derivation"}}}`; a refused rerun request has no
    /// classes, no original and no bound.
    record Rerun(long of, List<TargetDto> classes, Option<OriginalDto> original, Option<BoundDto> bound) implements RecipeDto {
        @Override
        public Result<Recipe> toDomain() {
            return Result.all(JobId.jobId(of),
                              Result.allOf(option(classes).or(List.of()).stream().map(TargetDto::toDomain).toList()),
                              Values.optional(original, OriginalDto::toDomain),
                              Values.optional(bound, BoundDto::toDomain))
                         .map(Recipe::storedRerun);
        }

        /// The rerun unit's bound decided at submit: milliseconds and the derivation the record prints. Absent for a
        /// refused request and for a rerun stored before the derived bound.
        public record BoundDto(long millis, String derivation) {
            public static BoundDto boundDto(long millis, String derivation) {
                return new BoundDto(millis, derivation);
            }

            Result<Recipe.Rerun.Bound> toDomain() {
                return Result.all(Verify.ensure(millis,
                                                Verify.Is::positive,
                                                invalid("bound.millis")),
                                  Verify.ensure(derivation,
                                                Verify.Is::present,
                                                invalid("bound.derivation")))
                             .map((value, text) -> Recipe.Rerun.Bound.bound(Duration.ofMillis(value),
                                                                            text));
            }
        }

        /// One target class: `runner` is `surefire` or `failsafe`.
        public record TargetDto(@JsonProperty("class") String className, String module, String runner) {
            public static TargetDto targetDto(String className, String module, String runner) {
                return new TargetDto(className, module, runner);
            }

            Result<Recipe.Rerun.Target> toDomain() {
                return Recipe.Rerun.Target.target(className, module, runner);
            }
        }

        /// The parent's outcome as its record had it, for the target classes.
        public record OriginalDto(long job,
                                  String verdict,
                                  Option<String> reason,
                                  List<ResultRecordDto.FailedDto> failed) {
            public static OriginalDto originalDto(long job,
                                                  String verdict,
                                                  Option<String> reason,
                                                  List<ResultRecordDto.FailedDto> failed) {
                return new OriginalDto(job, verdict, reason, failed);
            }

            Result<Recipe.Rerun.Original> toDomain() {
                return Result.all(JobId.jobId(job),
                                  Verify.ensure(verdict,
                                                Verify.Is::present,
                                                invalid("original.verdict")),
                                  failures())
                             .map((parent, parentVerdict, parentFailures) -> Recipe.Rerun.Original.original(parent,
                                                                                                            parentVerdict,
                                                                                                            reason,
                                                                                                            parentFailures));
            }

            /// An absent `failed` reads as none, as an absent list does elsewhere on the wire; each row needs its class
            /// and name, as the canonical form writes both.
            private Result<List<Recipe.Rerun.Failure>> failures() {
                return Result.allOf(option(failed).or(List.of()).stream().map(OriginalDto::failure).toList());
            }

            private static Result<Recipe.Rerun.Failure> failure(ResultRecordDto.FailedDto dto) {
                return Result.all(Verify.ensure(dto.className(),
                                                Verify.Is::present,
                                                invalid("original.failed.class")),
                                  Verify.ensure(dto.name(),
                                                Verify.Is::present,
                                                invalid("original.failed.name")))
                             .map((className, name) -> Recipe.Rerun.Failure.failure(className,
                                                                                    name,
                                                                                    dto.known()));
            }
        }
    }

    static RecipeDto from(Recipe recipe) {
        return switch (recipe) {
            case Recipe.Mvn mvn -> new Mvn(mvn.values());
            case Recipe.ModuleTest moduleTest -> new ModuleTest(moduleTest.pl().stream().map(ModulePath::value).toList(),
                                                                moduleTest.test().map(TestSelection::value),
                                                                moduleTest.itTest().map(TestSelection::value));
            case Recipe.Gate _ -> new Gate();
            case Recipe.Unsupported _ -> new Mutate(none());
            case Recipe.Rerun rerun -> new Rerun(rerun.of().value(),
                                                 rerun.targets().stream().map(RecipeDto::targetDto).toList(),
                                                 rerun.original().map(RecipeDto::originalDto),
                                                 rerun.bound().map(RecipeDto::boundDto));
        };
    }

    private static Rerun.TargetDto targetDto(Recipe.Rerun.Target target) {
        return Rerun.TargetDto.targetDto(target.className(),
                                         target.module().value(),
                                         target.runnerName());
    }

    private static Rerun.OriginalDto originalDto(Recipe.Rerun.Original parentOutcome) {
        return Rerun.OriginalDto.originalDto(parentOutcome.job().value(),
                                             parentOutcome.verdict(),
                                             parentOutcome.reason(),
                                             parentOutcome.failed().stream().map(RecipeDto::failedDto).toList());
    }

    private static ResultRecordDto.FailedDto failedDto(Recipe.Rerun.Failure failure) {
        return new ResultRecordDto.FailedDto(failure.className(), failure.name(), failure.known());
    }

    private static Rerun.BoundDto boundDto(Recipe.Rerun.Bound bound) {
        return Rerun.BoundDto.boundDto(bound.value().toMillis(),
                                       bound.derivation());
    }
}
