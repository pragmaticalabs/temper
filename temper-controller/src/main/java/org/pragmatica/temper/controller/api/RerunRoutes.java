package org.pragmatica.temper.controller.api;

import java.util.List;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.controller.submit.RerunJob;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Priority;
import org.pragmatica.temper.domain.model.UnitTimeout;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.protocol.api.RerunRequest;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.http.Route.route;


/// `POST /api/v1/jobs/{id}/rerun` (m2-design §5.5), body `RerunRequest{submittedBy, priority?, fresh?, timeout?}`: the rerun is
/// submitted through the one submit path ([RerunJob]) and answered as `POST jobs` answers ([ApiRoutes#submitted]):
/// 200 `SubmitResponse` (accepted, or `deduplicated` onto an equal rerun) · 422 `RefusedResponse` (`REFUSED(rerun)`,
/// with the REFUSED job's id) · 404 (no such parent) · 400 (a body or id that does not parse). WRITE (D3).
public record RerunRoutes(ApiRoutes jobs, JsonMapper mapper, RerunJob rerun) {
    public static RerunRoutes rerunRoutes(ApiRoutes jobs, JsonMapper mapper, RerunJob rerun) {
        return new RerunRoutes(jobs, mapper, rerun);
    }

    public List<Route> routes() {
        return List.of(route(HttpMethod.POST, "jobs/{id}/rerun", this::rerunJob));
    }

    private Promise<ApiResponse> rerunJob(ApiRequest request) {
        return rerunRequest(request).async()
                           .flatMap(rerun::execute)
                           .flatMap(jobs::submitted)
                           .mapError(ApiRoutes::classify);
    }

    /// The id and every body field through its domain factory; failures are collected (400).
    private Result<RerunJob.Request> rerunRequest(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                RerunRequest.class)
                     .flatMap(body -> parsed(request, body));
    }

    private static Result<RerunJob.Request> parsed(ApiRequest request, RerunRequest body) {
        return Result.all(ApiRoutes.jobId(request),
                          OwnerTag.ownerTag(body.submittedBy()),
                          Values.optional(body.priority(),
                                          raw -> Priority.priority(some(raw))),
                          Values.optional(body.timeout(),
                                          UnitTimeout::unitTimeout))
                     .map((parent, owner, priority, timeout) -> RerunJob.Request.request(parent,
                                                                                         owner,
                                                                                         priority,
                                                                                         body.fresh().or(false),
                                                                                         timeout,
                                                                                         body.dedicatedCpu().or(false)));
    }
}
