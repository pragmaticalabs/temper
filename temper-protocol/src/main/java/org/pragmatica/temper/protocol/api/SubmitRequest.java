package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.JobRequest;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Priority;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;
import org.pragmatica.temper.domain.model.UnitTimeout;
import org.pragmatica.temper.domain.model.Values;

import static org.pragmatica.temper.domain.DomainError.invalid;
import static org.pragmatica.lang.Option.option;


/// `POST /api/v1/jobs` body (spec §2.1). `timeout` is the Q-6 top-level ISO-8601 override. `env` and
/// `environment` are declared ONLY so their presence can be refused as `REFUSED(R-ENV)` (Q-7); any other
/// unknown field fails JSON parsing (400). A `rerun` recipe is a 400: a rerun is asked for through
/// `POST /jobs/{id}/rerun`, which checks its preconditions and builds the recipe from the parent (m2-design §5.5).
/// `task` (`submit --task`, m3-design §6.4) links the job to a task at the epoch its caller holds; such a submit
/// authenticates with the lease token, and the controller refuses it `409 lease-lost` when that epoch is gone.
/// `dedicatedCpu` (`--dedicated-cpu`, m4-design §9.2) keeps the job off `sharedCpu` hosts; absent is false.
public record SubmitRequest(String submittedBy,
                            Option<Integer> priority,
                            Option<Boolean> keep,
                            Option<Boolean> fresh,
                            SourceDto source,
                            RecipeDto recipe,
                            Option<String> timeout,
                            Option<Object> env,
                            Option<Object> environment,
                            Option<TaskLink> task,
                            Option<Boolean> dedicatedCpu) {
    /// The task a job is submitted for, and the lease epoch its caller holds.
    public record TaskLink(long id, long epoch) {}

    public static SubmitRequest submitRequest(String submittedBy,
                                              Option<Integer> priority,
                                              Option<Boolean> keep,
                                              Option<Boolean> fresh,
                                              SourceDto source,
                                              RecipeDto recipe,
                                              Option<String> timeout,
                                              Option<Object> env,
                                              Option<Object> environment,
                                              Option<Boolean> dedicatedCpu) {
        return new SubmitRequest(submittedBy,
                                 priority,
                                 keep,
                                 fresh,
                                 source,
                                 recipe,
                                 timeout,
                                 env,
                                 environment,
                                 Option.none(),
                                 dedicatedCpu);
    }

    /// The same request linked to `task` at `epoch`.
    public SubmitRequest forTask(long id, long epoch) {
        return new SubmitRequest(submittedBy,
                                 priority,
                                 keep,
                                 fresh,
                                 source,
                                 recipe,
                                 timeout,
                                 env,
                                 environment,
                                 Option.some(new TaskLink(id, epoch)),
                                 dedicatedCpu);
    }

    /// Parse, don't validate: every field through its domain factory; all failures are collected.
    public Result<JobRequest> toDomain() {
        return Result.all(OwnerTag.ownerTag(submittedBy),
                          Priority.priority(priority),
                          option(source).toResult(invalid("source").apply("missing")).flatMap(SourceDto::toDomain),
                          option(recipe).toResult(invalid("recipe").apply("missing"))
                                .flatMap(SubmitRequest::submittable),
                          Values.optional(timeout, UnitTimeout::unitTimeout))
                     .map((owner, prio, parsedSource, parsedRecipe, parsedTimeout) -> JobRequest.jobRequest(owner,
                                                                                                            prio,
                                                                                                            keep.or(false),
                                                                                                            fresh.or(false),
                                                                                                            parsedSource,
                                                                                                            parsedRecipe,
                                                                                                            RecipeOptions.recipeOptions(parsedTimeout,
                                                                                                                                        dedicatedCpu.or(false)),
                                                                                                            envFieldPresent()));
    }

    private static Result<Recipe> submittable(RecipeDto recipe) {
        return Verify.ensure(recipe,
                             present -> !(present instanceof RecipeDto.Rerun),
                             invalid("recipe").apply("rerun (POST jobs/{id}/rerun)"))
                     .flatMap(RecipeDto::toDomain);
    }

    /// Whether the request carried `env` or `environment` (R-ENV).
    public boolean envFieldPresent() {
        return env.isPresent() || environment.isPresent();
    }
}
