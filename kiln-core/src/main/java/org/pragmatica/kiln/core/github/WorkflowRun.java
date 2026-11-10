package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.instant;
import static org.pragmatica.kiln.core.github.GitHubJson.list;
import static org.pragmatica.kiln.core.github.GitHubJson.number;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalInstant;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalList;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalNumber;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalText;
import static org.pragmatica.kiln.core.github.GitHubJson.text;
import static org.pragmatica.lang.Option.none;


/// One workflow run of `GET /actions/runs?branch=<b>` or `?event=schedule` (spec §4.2): `id, name, status, conclusion,
/// head_sha, html_url, created_at`. `conclusion` is absent until the run completes. The change feed (CI interface T1
/// WP2) also reads `event`, `head_branch`, `run_attempt` (1 when absent), `check_suite_id`, `updated_at` (the
/// completion time of a completed run), `workflow_id` and `run_number` (design §4.2 R2-3: the latest run per distinct
/// workflow) and the numbers of `pull_requests[]` (G4: empty when absent, as GitHub sends it for a fork PR's run), each
/// optional so an older fixture or answer still decodes.
public record WorkflowRun(long id,
                          String name,
                          String status,
                          Option<String> conclusion,
                          String headSha,
                          String htmlUrl,
                          Instant createdAt,
                          Option<String> event,
                          Option<String> headBranch,
                          long attempt,
                          Option<Long> suiteId,
                          Option<Instant> updatedAt,
                          Option<Long> workflowId,
                          Option<Long> runNumber,
                          List<Long> pullRequests) {
    /// The `status` of a run that has finished; only such a run has a conclusion.
    public static final String COMPLETED = "completed";

    public WorkflowRun {
        pullRequests = List.copyOf(pullRequests);
    }

    /// A run with only the base read's fields: event, branch, suite, update time, workflow and run number unknown,
    /// attempt 1, no pull request listed.
    public WorkflowRun(long id,
                       String name,
                       String status,
                       Option<String> conclusion,
                       String headSha,
                       String htmlUrl,
                       Instant createdAt) {
        this(id,
             name,
             status,
             conclusion,
             headSha,
             htmlUrl,
             createdAt,
             none(),
             none(),
             1,
             none(),
             none(),
             none(),
             none(),
             List.of());
    }

    /// One run of `GET /actions/runs/{id}`.
    public static Result<WorkflowRun> decodeOne(String json) {
        return GitHubJson.tree(json).flatMap(WorkflowRun::workflowRun);
    }

    /// One page of runs. Lenient: unknown fields are ignored; a run missing a field fails the page, so a set is never
    /// read shorter than GitHub sent it.
    public static Result<List<WorkflowRun>> decode(String json) {
        return GitHubJson.tree(json).flatMap(root -> list(root, "workflow_runs", WorkflowRun::workflowRun));
    }

    static Result<WorkflowRun> workflowRun(JsonNode node) {
        return Result.all(number(node, "id"),
                          text(node, "name"),
                          text(node, "status"),
                          optionalText(node, "conclusion"),
                          text(node, "head_sha"),
                          text(node, "html_url"),
                          instant(node, "created_at"),
                          optionalText(node, "event"),
                          optionalText(node, "head_branch"),
                          optionalNumber(node, "run_attempt"),
                          optionalNumber(node, "check_suite_id"),
                          optionalInstant(node, "updated_at"),
                          optionalNumber(node, "workflow_id"),
                          optionalNumber(node, "run_number"),
                          optionalList(node, "pull_requests", WorkflowRun::pullRequest)).map((id, name, status, conclusion, headSha, htmlUrl, createdAt, event, branch, attempt, suite, updated, workflow, number, pulls) -> new WorkflowRun(id,
                                                                                                                                                                                                                                             name,
                                                                                                                                                                                                                                             status,
                                                                                                                                                                                                                                             conclusion,
                                                                                                                                                                                                                                             headSha,
                                                                                                                                                                                                                                             htmlUrl,
                                                                                                                                                                                                                                             createdAt,
                                                                                                                                                                                                                                             event,
                                                                                                                                                                                                                                             branch,
                                                                                                                                                                                                                                             attempt.or(1L),
                                                                                                                                                                                                                                             suite,
                                                                                                                                                                                                                                             updated,
                                                                                                                                                                                                                                             workflow,
                                                                                                                                                                                                                                             number,
                                                                                                                                                                                                                                             pulls));
    }

    private static Result<Long> pullRequest(JsonNode node) {
        return number(node, "number");
    }

    /// Whether `pull_requests[]` lists PR `number` (G4: the run is that PR's).
    public boolean lists(long number) {
        return pullRequests.contains(number);
    }

    /// A run that has finished; only such a run has a conclusion.
    public boolean completed() {
        return COMPLETED.equals(status);
    }
}
