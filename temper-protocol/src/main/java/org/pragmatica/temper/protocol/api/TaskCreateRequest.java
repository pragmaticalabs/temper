package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.task.NewTask;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// `POST /api/v1/tasks` body (m3-design §5.4): `{slug, title, issues?, brief, repo, baseRef, branch, pr?, critical?,
/// priority?, after?, roundCap?, createdBy}`. Parsing gives a [NewTask] or a 400; an unknown field is a 400 as well.
/// Whether the base resolves (`base-unknown`), the branch is protected (`protected-branch`) and every `after` task
/// exists (`after-unknown`) are the controller's 422.
public record TaskCreateRequest(String slug,
                                String title,
                                Option<List<Integer>> issues,
                                TaskBriefDto brief,
                                String repo,
                                String baseRef,
                                String branch,
                                Option<Integer> pr,
                                Option<Boolean> critical,
                                Option<Integer> priority,
                                Option<List<Long>> after,
                                Option<Integer> roundCap,
                                String createdBy) {
    public Result<NewTask> toDomain() {
        return option(brief).toResult(invalid("brief").apply("missing"))
                     .flatMap(TaskBriefDto::toDomain)
                     .flatMap(parsed -> NewTask.newTask(slug,
                                                        title,
                                                        issues.or(List.of()),
                                                        parsed,
                                                        repo,
                                                        baseRef,
                                                        branch,
                                                        pr,
                                                        critical.or(false),
                                                        priority.or(0),
                                                        after.or(List.of()),
                                                        roundCap.or(NewTask.DEFAULT_ROUND_CAP),
                                                        createdBy));
    }
}
