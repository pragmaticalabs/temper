package org.pragmatica.temper.domain.task;

import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.Values;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A task's brief (`tasks.brief`, m3-design §5.3): the brief's path, optionally pinned to a SHA, whether the task
/// starts in DESIGN (T1), and the stage templates by name [G: stored as given; M3b renders them].
public record TaskBrief(String path, Option<Sha> sha, boolean design, Map<String, String> templates) {
    public static final int MAX_PATH = 400;

    public TaskBrief {
        templates = Map.copyOf(templates);
    }

    public static Result<TaskBrief> taskBrief(String path,
                                              Option<String> sha,
                                              boolean design,
                                              Map<String, String> templates) {
        return Result.all(Verify.ensure(path,
                                        Verify.Is::present,
                                        invalid("brief path")).flatMap(present -> Verify.ensure(present,
                                                                                                Verify.Is::lenBetween,
                                                                                                1,
                                                                                                MAX_PATH,
                                                                                                invalid("brief path"))),
                          Values.optional(sha, Sha::sha))
                     .map((checkedPath, checkedSha) -> new TaskBrief(checkedPath, checkedSha, design, templates));
    }
}
