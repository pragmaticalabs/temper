package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.host.UnitAttempt;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.domain.gate.GateCandidate;
import org.pragmatica.temper.domain.gate.GateEvidence;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.protocol.api.GateView;
import org.pragmatica.temper.protocol.api.RerunView;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.ResultRecord;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.temper.controller.http.Route.readRoute;


/// `GET /api/v1/gate/{sha}` (m2-design §1.4): the merge question. `{sha}` is 7 to 40 hex digits; 200 `GateView`, a
/// NONE answer when no Gate is at a SHA starting with it; 400 when it is not hex of that length, or when the Gates
/// it matches are at more than one SHA (the answer lists them, so the caller can pick one).
///
/// One store read ([JobStore#gateCandidates], one snapshot), then the pure selection ([GateEvidence#select]). Every
/// candidate's state is the one a job view shows ([Views#jobState]): the unrecorded starts are snapshotted BEFORE the
/// store reads, as for every job view ([ApiRoutes]).
///
/// A READ route (D3): the read token may call it; it writes nothing.
public record GateRoutes(Responses responses,
                         JobStore jobs,
                         HostLinks links,
                         Supplier<Instant> clock,
                         Duration startBound) {
    public static GateRoutes gateRoutes(Responses responses,
                                        JobStore jobs,
                                        HostLinks links,
                                        Supplier<Instant> clock,
                                        Duration startBound) {
        return new GateRoutes(responses, jobs, links, clock, startBound);
    }

    public List<Route> routes() {
        return List.of(readRoute("gate/{sha}", this::getGate));
    }

    private Promise<ApiResponse> getGate(ApiRequest request) {
        var unrecorded = links.unrecordedStarts();

        return ShaPrefix.shaPrefix(request.param("sha").or(""))
                        .async()
                        .flatMap(prefix -> answered(prefix, unrecorded))
                        .map(responses::ok)
                        .mapError(ApiRoutes::classify);
    }

    /// The evidence at the one SHA a prefix names, as `gate` answers it: `sha` is that SHA, or the prefix itself when no
    /// Gate starts with it.
    record AtSha(String sha, GateEvidence evidence) {
        static AtSha atSha(String sha, GateEvidence evidence) {
            return new AtSha(sha, evidence);
        }
    }

    /// The one store read, the pure selection, and the answering Gate's reruns from its own row (m2-design §5.3), all from
    /// the same snapshot. `unrecorded` is snapshotted by the caller before this read.
    private Promise<GateView> answered(ShaPrefix prefix, Set<UnitAttempt> unrecorded) {
        return jobs.gateCandidates(prefix)
                   .flatMap(rows -> viewOf(prefix, rows, unrecorded).async());
    }

    private Result<GateView> viewOf(ShaPrefix prefix, List<GateRow> rows, Set<UnitAttempt> unrecorded) {
        return atNow(prefix, rows, unrecorded).map(atSha -> GateViews.gateView(atSha.sha(),
                                                                               atSha.evidence(),
                                                                               answeringReruns(rows, atSha.evidence())));
    }

    private static List<RerunView> answeringReruns(List<GateRow> rows, GateEvidence evidence) {
        return evidence.answering()
                       .flatMap(answering -> rowOf(rows,
                                                   answering.job()))
                       .map(row -> Views.rerunViews(row.job().reruns()))
                       .or(List.of());
    }

    private static Option<GateRow> rowOf(List<GateRow> rows, JobId job) {
        return Option.from(rows.stream().filter(row -> row.job()
                                                          .id()
                                                          .equals(job)).findFirst());
    }

    /// The pure selection over candidate rows, each state shown as at `now`. Also `diff --base <sha>`'s ([DiffRoutes]),
    /// applied to the rows its own snapshot read, so the two verbs never disagree about what "the gate at S" is
    /// (m2-design §3.1). A prefix of Gates at two SHAs fails as a 400.
    static Result<AtSha> atSha(ShaPrefix prefix,
                               List<GateRow> rows,
                               Instant now,
                               Duration startBound,
                               Set<UnitAttempt> unrecorded) {
        return atOneSha(prefix, rows).map(sha -> AtSha.atSha(sha,
                                                             GateEvidence.select(candidates(rows,
                                                                                            now,
                                                                                            startBound,
                                                                                            unrecorded))));
    }

    /// Each state shown as at the time of the answer, after the read.
    private Result<AtSha> atNow(ShaPrefix prefix, List<GateRow> rows, Set<UnitAttempt> unrecorded) {
        return atSha(prefix, rows, clock.get(), startBound, unrecorded);
    }

    /// The SHA the rows are at, the prefix itself when there are none; rows at two SHAs are a 400 naming both.
    private static Result<String> atOneSha(ShaPrefix prefix, List<GateRow> rows) {
        var shas = rows.stream()
                       .flatMap(row -> row.job()
                                          .sha()
                                          .stream())
                       .map(Sha::value)
                       .collect(Collectors.toCollection(TreeSet::new));

        return shas.size() <= 1
               ? success(shas.stream().findFirst().orElse(prefix.value()))
               : ApiError.BadRequest.badRequest(cause("SHA prefix " + prefix + " matches " + String.join(",", shas))).result();
    }

    private static List<GateCandidate> candidates(List<GateRow> rows,
                                                  Instant now,
                                                  Duration startBound,
                                                  Set<UnitAttempt> unrecorded) {
        return rows.stream()
                   .map(row -> candidate(row, now, startBound, unrecorded))
                   .toList();
    }

    private static GateCandidate candidate(GateRow row, Instant now, Duration startBound, Set<UnitAttempt> unrecorded) {
        return GateCandidate.gateCandidate(row.job().id(),
                                           Views.jobState(row.job(), now, startBound, unrecorded),
                                           row.submitJudge(),
                                           row.grantJudges(),
                                           row.resultJudge(),
                                           row.record().map(ResultRecord::counts),
                                           row.record().map(ResultRecord::annotations).or(List.of()),
                                           row.record().map(record -> record.evidence()
                                                                            .stored()).or(false));
    }
}
