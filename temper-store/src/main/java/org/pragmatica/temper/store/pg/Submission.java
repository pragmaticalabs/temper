package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.function.Predicate;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.dedup.DedupKey;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.SourceDto;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A [NewJob] that `submit` accepts: at least one unit, a dedup key, a resolved source (checked in that order,
/// fail-fast, so the caller sees the typed `StoreError.General` constant).
record Submission(NewJob job, PushedSource source, DedupKey key) {
    static Result<Submission> submission(NewJob job) {
        return Verify.ensure(job.units(),
                             Predicate.not(List::isEmpty),
                             StoreError.General.UNITS_REQUIRED)
                     .flatMap(_ -> job.dedupKey()
                                      .toResult(StoreError.General.DEDUP_KEY_REQUIRED))
                     .flatMap(key -> withSource(job, key));
    }

    private static Result<Submission> withSource(NewJob job, DedupKey key) {
        return job.source()
                  .toResult(StoreError.General.SOURCE_REQUIRED)
                  .map(source -> new Submission(job, source, key));
    }

    /// `jobs.ref`: the resolved ref, or the requested one while unresolved. Absent for a job submitted by SHA, which
    /// is why such a job is never superseded (spec §2.4).
    static Option<Ref> ref(NewJob job) {
        return job.source()
                  .map(PushedSource::ref)
                  .or(() -> requestedRef(job.request()));
    }

    /// `jobs.source_sha`: the resolved SHA, or the requested one while unresolved.
    static Option<Sha> sha(NewJob job) {
        return job.source()
                  .map(PushedSource::sha)
                  .orElse(() -> requestedSha(job.request()));
    }

    /// `jobs.rerun_of`: the parent of a rerun job (m2-design §5.4), refused request included; absent for every other recipe.
    static Option<JobId> rerunOf(NewJob job) {
        return switch (job.recipe()) {
            case Recipe.Rerun rerun -> some(rerun.of());
            case Recipe.Mvn _, Recipe.ModuleTest _, Recipe.Gate _, Recipe.Unsupported _ -> none();
        };
    }

    /// `jobs.source` and `jobs.recipe` as written.
    static Result<JobDocuments> documents(NewJob job) {
        return Result.all(Json.write(sourceDto(job.request())),
                          Json.write(RecipeDto.from(job.recipe())))
                     .map(JobDocuments::new);
    }

    record JobDocuments(String source, String recipe) {}

    private static Option<Ref> requestedRef(SourceRequest request) {
        return switch (request) {
            case SourceRequest.ByRef byRef -> some(byRef.ref());
            case SourceRequest.BySha _, SourceRequest.Unsupported _, SourceRequest.Inherited _ -> none();
        };
    }

    private static Option<Sha> requestedSha(SourceRequest request) {
        return switch (request) {
            case SourceRequest.BySha bySha -> some(bySha.sha());
            case SourceRequest.Inherited inherited -> inherited.sha();
            case SourceRequest.ByRef _, SourceRequest.Unsupported _ -> none();
        };
    }

    private static SourceDto sourceDto(SourceRequest request) {
        return switch (request) {
            case SourceRequest.ByRef byRef -> new SourceDto.Pushed(some(byRef.ref().value()),
                                                                   none());
            case SourceRequest.BySha bySha -> new SourceDto.Pushed(none(),
                                                                   some(bySha.sha().value()));
            case SourceRequest.Unsupported unsupported -> unsupportedDto(unsupported.kind());
            case SourceRequest.Inherited inherited -> new SourceDto.Pushed(none(),
                                                                           inherited.sha().map(Sha::value));
        };
    }

    private static SourceDto unsupportedDto(String kind) {
        return kind.equals("patch")
               ? new SourceDto.Patch(none(), none())
               : new SourceDto.Bundle(none(), none());
    }
}
