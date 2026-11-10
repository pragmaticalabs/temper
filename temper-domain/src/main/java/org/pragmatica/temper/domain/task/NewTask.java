package org.pragmatica.temper.domain.task;

import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Values;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A task to create (`POST /tasks`, m3-design §5.4), every field as the `tasks` CHECKs judge it: `slug` as the column's
/// pattern, a present `title` of at most [#MAX_TITLE] characters [G], positive issue numbers and PR, `repo` as GitHub's
/// `owner/name`, refs as [Ref], `priority` in -1000..1000, positive `after` ids, a `roundCap` in 1..[#MAX_ROUND_CAP]
/// [G], `createdBy` an owner tag. Whether the base resolves and the branch is protected are the controller's
/// refusals (422), not parse failures.
public record NewTask(String slug,
                      String title,
                      List<Integer> issues,
                      TaskBrief brief,
                      String repo,
                      Ref baseRef,
                      Ref branch,
                      Option<Integer> pr,
                      boolean critical,
                      int priority,
                      List<TaskId> after,
                      int roundCap,
                      OwnerTag createdBy) {
    public static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
    public static final Pattern REPO = Pattern.compile("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$");
    public static final int MAX_TITLE = 200;
    public static final int DEFAULT_ROUND_CAP = 3;
    public static final int MAX_ROUND_CAP = 20;
    public static final int PRIORITY_BOUND = 1000;

    public NewTask {
        issues = List.copyOf(issues);
        after = List.copyOf(after);
    }

    public static Result<NewTask> newTask(String slug,
                                          String title,
                                          List<Integer> issues,
                                          TaskBrief brief,
                                          String repo,
                                          String baseRef,
                                          String branch,
                                          Option<Integer> pr,
                                          boolean critical,
                                          int priority,
                                          List<Long> after,
                                          int roundCap,
                                          String createdBy) {
        return Result.all(Values.matching(slug, SLUG, "slug"),
                          titleOf(title),
                          Result.allOf(issues.stream().map(NewTask::issue).toList()),
                          Values.matching(repo, REPO, "repo"),
                          Ref.ref(baseRef),
                          Ref.ref(branch),
                          Verify.ensureOption(pr,
                                              Verify.Is::positive,
                                              invalid("pr")),
                          Verify.ensure(priority,
                                        Verify.Is::between,
                                        - PRIORITY_BOUND,
                                        PRIORITY_BOUND,
                                        invalid("priority")),
                          Result.allOf(after.stream().map(TaskId::taskId).toList()),
                          Verify.ensure(roundCap,
                                        Verify.Is::between,
                                        1,
                                        MAX_ROUND_CAP,
                                        invalid("roundCap")),
                          OwnerTag.ownerTag(createdBy, "createdBy"))
                     .map((checkedSlug, checkedTitle, checkedIssues, checkedRepo, base, checkedBranch, checkedPr, checkedPriority, checkedAfter, checkedCap, owner) -> new NewTask(checkedSlug,
                                                                                                                                                                                   checkedTitle,
                                                                                                                                                                                   checkedIssues,
                                                                                                                                                                                   brief,
                                                                                                                                                                                   checkedRepo,
                                                                                                                                                                                   base,
                                                                                                                                                                                   checkedBranch,
                                                                                                                                                                                   checkedPr,
                                                                                                                                                                                   critical,
                                                                                                                                                                                   checkedPriority,
                                                                                                                                                                                   checkedAfter,
                                                                                                                                                                                   checkedCap,
                                                                                                                                                                                   owner));
    }

    private static Result<String> titleOf(String title) {
        return Verify.ensure(title, Verify.Is::present, invalid("title")).flatMap(present -> Verify.ensure(present,
                                                                                                           Verify.Is::lenBetween,
                                                                                                           1,
                                                                                                           MAX_TITLE,
                                                                                                           invalid("title")));
    }

    private static Result<Integer> issue(Integer raw) {
        return Verify.ensure(raw, Verify.Is::positive, invalid("issue"));
    }
}
