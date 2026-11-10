package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.state.JobState;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// `GET /jobs` filters; each present filter must match. `limit` is 1..[#MAX_LIMIT]. `before` is the keyset cursor of
/// the next page (only jobs with a smaller id): a reader that filters what the store answers pages through it.
public record JobQuery(Option<JobState> state,
                       Option<OwnerTag> submittedBy,
                       Option<Sha> sha,
                       Option<Ref> ref,
                       int limit,
                       Option<JobId> before) {
    public static final int MAX_LIMIT = 1000;

    public static Result<JobQuery> jobQuery(Option<JobState> state,
                                            Option<OwnerTag> submittedBy,
                                            Option<Sha> sha,
                                            Option<Ref> ref,
                                            int limit) {
        return Verify.ensure(limit, Verify.Is::between, 1, MAX_LIMIT, invalid("limit")).map(valid -> new JobQuery(state,
                                                                                                                  submittedBy,
                                                                                                                  sha,
                                                                                                                  ref,
                                                                                                                  valid,
                                                                                                                  none()));
    }

    /// The same filters with `state` in place of this one's.
    public JobQuery withState(JobState state) {
        return new JobQuery(some(state), submittedBy, sha, ref, limit, before);
    }

    /// The next page of the same filters: the jobs older than `last`.
    public JobQuery after(JobId last) {
        return new JobQuery(state, submittedBy, sha, ref, limit, some(last));
    }
}
