package org.pragmatica.kiln.core.feed;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.github.CheckRun;
import org.pragmatica.kiln.core.github.CommitStatus;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


final class FeedFixtures {
    static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    static final long APP = 15368;
    static final PrNumber PR = PrNumber.prNumber(7).unwrap();
    static final String PULL_REQUEST = "pull_request";
    static final Sha HEAD = Sha.sha("a".repeat(40)).unwrap();

    private FeedFixtures() {}

    static RequiredCheck pinned(String context) {
        return new RequiredCheck(CheckContext.checkContext(context).unwrap(), some(AppId.appId(APP).unwrap()));
    }

    static RequiredCheck unpinned(String context) {
        return new RequiredCheck(CheckContext.checkContext(context).unwrap(), none());
    }

    static RequiredChecks.Required requiring(RequiredCheck... checks) {
        return new RequiredChecks.Required(List.of(checks), "non_admins");
    }

    static CheckRun checkRun(long id, String name, String status, String conclusion, Instant started, long suite) {
        return new CheckRun(id, name, some(AppId.appId(APP).unwrap()), status, Option.option(conclusion), some(started), some(suite));
    }

    /// A run of workflow `CI` (no `workflow_id`, no `run_number`); a `pull_request` run lists [#PR] (G4).
    static WorkflowRun run(long id, String event, String status, String conclusion, long attempt, long suite, Instant created, Instant updated) {
        return new WorkflowRun(id, "CI", status, Option.option(conclusion), "x", "https://x/" + id, created, some(event), some("feat"),
                               attempt, some(suite), some(updated), none(), none(), PULL_REQUEST.equals(event)
                                                                                      ? List.of(PR.value())
                                                                                      : List.of());
    }

    /// A completed `pull_request` run listing [#PR], of workflow `workflow` and run number `number` (R2-3).
    static WorkflowRun numbered(long id, long workflow, long number, long attempt, String conclusion, long suite) {
        return new WorkflowRun(id, "CI", "completed", some(conclusion), "x", "https://x/" + id, T0, some(PULL_REQUEST), some("feat"),
                               attempt, some(suite), some(T0), some(workflow), some(number), List.of(PR.value()));
    }

    /// A completed `pull_request` run listing `prs` (G4: a fork PR's run lists none).
    static WorkflowRun listing(long id, String conclusion, long suite, List<Long> prs) {
        return new WorkflowRun(id, "CI", "completed", some(conclusion), "x", "https://x/" + id, T0, some(PULL_REQUEST), some("feat"),
                               1, some(suite), some(T0), none(), none(), prs);
    }

    static CommitStatus status(long id, String context, String state, String description) {
        return new CommitStatus(id, context, state, Option.option(description), T0);
    }
}
