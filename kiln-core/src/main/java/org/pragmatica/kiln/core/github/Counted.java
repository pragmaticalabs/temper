package org.pragmatica.kiln.core.github;

import java.util.List;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.list;
import static org.pragmatica.kiln.core.github.GitHubJson.number;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalText;


/// One page of a listing GitHub counts (`total_count` beside `check_runs[]` or `workflow_runs[]`), or of the PR files
/// (`filename`, `previous_filename` for a rename). The merge gate compares the distinct ids it read with the first
/// page's `total_count`: a short read is never judged as complete.
public record Counted<T>(long total, List<T> items) {
    public Counted {
        items = List.copyOf(items);
    }

    /// One file of `GET /pulls/{n}/files`: its path and, for a rename, the path it had.
    public record File(String filename, Option<String> previous) {}

    /// One page of `GET /commits/{sha}/check-runs`.
    public static Result<Counted<CheckRun>> checkRuns(String json) {
        return counted(json, "check_runs", CheckRun::checkRun);
    }

    /// One page of `GET /actions/runs`.
    public static Result<Counted<WorkflowRun>> workflowRuns(String json) {
        return counted(json, "workflow_runs", WorkflowRun::workflowRun);
    }

    /// One page of `GET /pulls/{n}/files` (a bare array: no count).
    public static Result<List<File>> files(String json) {
        return GitHubJson.tree(json).flatMap(root -> GitHubJson.each(root, GitHubJson.ROOT, Counted::file));
    }

    private static <T> Result<Counted<T>> counted(String json, String field, Fn1<Result<T>, JsonNode> decoder) {
        return GitHubJson.tree(json).flatMap(root -> Result.all(number(root, "total_count"), list(root, field, decoder)).map(Counted::new));
    }

    private static Result<File> file(JsonNode node) {
        return Result.all(GitHubJson.text(node, "filename"),
                          optionalText(node, "previous_filename"))
                     .map(File::new);
    }
}
