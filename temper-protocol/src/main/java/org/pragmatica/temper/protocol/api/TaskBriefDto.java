package org.pragmatica.temper.protocol.api;

import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.TaskBrief;


/// A task's brief on the wire (m3-design §5.3, §5.4): `{path, sha?, design?, templates?}`.
public record TaskBriefDto(String path,
                           Option<String> sha,
                           Option<Boolean> design,
                           Option<Map<String, String>> templates) {
    public static TaskBriefDto taskBriefDto(TaskBrief brief) {
        return new TaskBriefDto(brief.path(),
                                brief.sha().map(Sha::value),
                                Option.some(brief.design()),
                                Option.some(brief.templates()));
    }

    public Result<TaskBrief> toDomain() {
        return TaskBrief.taskBrief(path,
                                   sha,
                                   design.or(false),
                                   templates.or(Map.of()));
    }
}
