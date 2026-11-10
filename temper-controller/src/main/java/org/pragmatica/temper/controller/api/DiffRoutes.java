package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.host.UnitAttempt;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.diff.DiffBase;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.protocol.api.DiffAnswer;
import org.pragmatica.temper.store.api.BaseRead;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.ResultStore;
import org.pragmatica.temper.store.api.StoredSides;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.temper.controller.http.Route.readRoute;


/// `GET /api/v1/diff?a=<job>&b=<job>` or `?a=<job>&baseSha=<sha>` (m2-design §3.3): 200 `DiffAnswer`, a `DiffView`
/// or a typed `DiffRefusal`; 404 for an unknown job; 400 for a query that is not exactly one of the two forms.
///
/// - `a` and `b`: side A is job `a`, side B job `b`; both are read in one snapshot ([ResultStore#diffSides]).
/// - `a` and `baseSha` (`temper diff <job> --base <sha>`): side B is job `a`, and side A is the Gate that `gate <sha>`
///   answers about ([DiffBase] over [GateRoutes#atSha], the same candidates read and the same selection, so the verbs
///   never disagree). The job must exist whatever the base (404 first). A NONE or CONFLICT gate answer is refused
///   without a diff.
///
/// The `--base` form is ONE store transaction (maintainer s44; [ResultStore#diffBase]): side B, the Gate candidates, the
/// selection (handed to the store as a pure function) and side A all see one snapshot. What stays outside it is only
/// the shown-state overlay's inputs: the unrecorded starts (this controller's memory) and `now`, both taken once before
/// the read and used for the selection and the answer alike. They can turn a QUEUED candidate into UNKNOWN, never a
/// terminal one into anything else; both are non-terminal and neither votes, so they cannot change the base.
///
/// The unrecorded starts are snapshotted BEFORE the store reads, as for every job view ([ApiRoutes]): a side's state
/// is the one a job view shows ([Views#jobState]). A READ route (D3): the read token may call it; it writes nothing.
public record DiffRoutes(Responses responses,
                         ResultStore results,
                         HostLinks links,
                         Supplier<Instant> clock,
                         Duration startBound) {
    public static DiffRoutes diffRoutes(Responses responses,
                                        ResultStore results,
                                        HostLinks links,
                                        Supplier<Instant> clock,
                                        Duration startBound) {
        return new DiffRoutes(responses, results, links, clock, startBound);
    }

    private static final String FORMS = "diff takes a=<job> with exactly one of b=<job> and baseSha=<sha>";

    public List<Route> routes() {
        return List.of(readRoute("diff", this::getDiff));
    }

    /// The two forms of the query.
    sealed interface Query {
        record Jobs(JobId a, JobId b) implements Query {
            static Jobs jobs(JobId a, JobId b) {
                return new Jobs(a, b);
            }
        }

        /// `job` is side B; side A is the base at `sha`.
        record Base(JobId job, ShaPrefix sha) implements Query {
            static Base base(JobId job, ShaPrefix sha) {
                return new Base(job, sha);
            }
        }
    }

    private Promise<ApiResponse> getDiff(ApiRequest request) {
        var unrecorded = links.unrecordedStarts();

        return query(request).async()
                    .flatMap(query -> answered(query, unrecorded))
                    .map(responses::ok)
                    .mapError(ApiRoutes::classify);
    }

    private Promise<DiffAnswer> answered(Query query, Set<UnitAttempt> unrecorded) {
        return switch (query) {
            case Query.Jobs pair -> compared(pair.a(), pair.b(), none(), unrecorded);
            case Query.Base base -> based(base, unrecorded);
        };
    }

    private Promise<DiffAnswer> based(Query.Base base, Set<UnitAttempt> unrecorded) {
        var now = clock.get();

        return results.diffBase(base.sha(),
                                base.job(),
                                rows -> chosen(base.sha(),
                                               rows,
                                               now,
                                               unrecorded))
                      .flatMap(read -> againstBase(base, read, now, unrecorded).async());
    }

    /// The base the gate answer names, over the candidates the store's snapshot read.
    private Result<Option<JobId>> chosen(ShaPrefix sha, List<GateRow> rows, Instant now, Set<UnitAttempt> unrecorded) {
        return GateRoutes.atSha(sha, rows, now, startBound, unrecorded).map(atSha -> baseJob(DiffBase.choose(atSha.evidence())));
    }

    private static Option<JobId> baseJob(DiffBase base) {
        return switch (base) {
            case DiffBase.Found found -> some(found.gate().job());
            case DiffBase.Missing _, DiffBase.Conflicting _ -> none();
        };
    }

    /// The same selection over the same rows as [#chosen], now for the answer: a found base comes with its sides.
    private Result<DiffAnswer> againstBase(Query.Base base, BaseRead read, Instant now, Set<UnitAttempt> unrecorded) {
        return GateRoutes.atSha(base.sha(),
                                read.candidates(),
                                now,
                                startBound,
                                unrecorded)
                         .flatMap(atSha -> answerFor(base, atSha, read, now, unrecorded));
    }

    /// The store read side A because the same selection over the same rows found this base, so the sides are present.
    private Result<DiffAnswer> foundAnswer(DiffBase.Found found,
                                           BaseRead read,
                                           Instant now,
                                           Set<UnitAttempt> unrecorded) {
        var job = found.gate().job();

        return read.sides()
                   .toResult(cause("diff --base read no sides for base " + job))
                   .map(sides -> DiffViews.answer(sides,
                                                  some(job),
                                                  now,
                                                  startBound,
                                                  unrecorded));
    }

    private Result<DiffAnswer> answerFor(Query.Base base,
                                         GateRoutes.AtSha atSha,
                                         BaseRead read,
                                         Instant now,
                                         Set<UnitAttempt> unrecorded) {
        return switch (DiffBase.choose(atSha.evidence())) {
            case DiffBase.Found found -> foundAnswer(found, read, now, unrecorded);
            case DiffBase.Missing _ -> success(DiffViews.noBase(base.job(), atSha.sha()));
            case DiffBase.Conflicting conflicting -> success(DiffViews.baseConflict(base.job(),
                                                                                    atSha.sha(),
                                                                                    conflicting.votes()));
        };
    }

    private Promise<DiffAnswer> compared(JobId a, JobId b, Option<JobId> base, Set<UnitAttempt> unrecorded) {
        return results.diffSides(a, b)
                      .map(sides -> answer(sides, base, unrecorded));
    }

    /// The shown states are derived at the time of the answer, after the read, as `gate`'s are.
    private DiffAnswer answer(StoredSides sides, Option<JobId> base, Set<UnitAttempt> unrecorded) {
        return DiffViews.answer(sides, base, clock.get(), startBound, unrecorded);
    }

    // ---- the query ---------------------------------------------------------------------------------------------
    /// `a` and exactly one of `b` and `baseSha`; anything else is a 400 naming the two forms.
    private static Result<Query> query(ApiRequest request) {
        var b = request.queryParam("b");
        var baseSha = request.queryParam("baseSha");

        return request.queryParam("a")
                      .filter(_ -> b.isPresent() != baseSha.isPresent())
                      .flatMap(a -> form(a, b, baseSha))
                      .or(() -> ApiError.BadRequest.badRequest(cause(FORMS)).result());
    }

    /// The form the one present parameter names.
    private static Option<Result<Query>> form(String a, Option<String> b, Option<String> baseSha) {
        return b.map(raw -> pair(a, raw))
                .orElse(() -> baseSha.map(raw -> base(a, raw)));
    }

    private static Result<Query> pair(String a, String b) {
        return Result.all(jobId("a", a), jobId("b", b)).map(Query.Jobs::jobs);
    }

    private static Result<Query> base(String a, String baseSha) {
        return Result.all(jobId("a", a),
                          ShaPrefix.shaPrefix(baseSha))
                     .map(Query.Base::base);
    }

    private static Result<JobId> jobId(String name, String raw) {
        return Number.parseLong(raw)
                     .mapError(DomainError.unparseable(name, raw))
                     .flatMap(JobId::jobId);
    }
}
