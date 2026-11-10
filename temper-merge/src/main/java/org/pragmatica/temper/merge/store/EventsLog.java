package org.pragmatica.temper.merge.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.merge.engine.Job;
import org.pragmatica.temper.merge.engine.JobState;


/// The runner's report: one line per state change, appended to the events log (default
/// `~/temper/merge-runner/events.log`). Tab-separated `key=value` fields after the time: `job`, `pr`, `sha`, `state`,
/// `reason` always; then `merge` and `closed` for `MERGED`, `run`, `failedJobs` and `failedTests` for `REMOVED`. A
/// value's tabs and line breaks become spaces, so one change is always one line.
///
/// Each line is one `write` on a file opened with `APPEND`; `submit` and the serve loop both append, and on a local
/// Linux filesystem an appended write of one short line is not interleaved with another.
public record EventsLog(Path file) {
    public static EventsLog eventsLog(Path file) {
        return new EventsLog(file);
    }

    public Result<Unit> append(Job job, Instant at) {
        return appended(line(job, at));
    }

    public static String line(Job job, Instant at) {
        var fields = new ArrayList<>(List.of(at.toString(),
                                             "job=" + job.id(),
                                             "pr=" + job.pr(),
                                             "sha=" + job.sha(),
                                             "state=" + job.state(),
                                             "reason=" + flat(job.reason())));

        if (job.state() == JobState.MERGED) {
            fields.add("merge=" + job.mergeSha());
            fields.add("closed=" + String.join(",", job.closedIssues()));
        }

        if (job.state() == JobState.REMOVED) {
            fields.add("run=" + flat(job.run()));
            fields.add("failedJobs=" + flat(String.join(",", job.failedJobs())));
            fields.add("failedTests=" + flat(String.join(",", job.failedTests())));
        }

        return String.join("\t", fields);
    }

    private static String flat(String value) {
        return value.replaceAll("[\\t\\r\\n]+", " ");
    }

    private Result<Unit> appended(String line) {
        return Result.lift(thrown -> Causes.cause("cannot append to " + file + ": " + thrown),
                           () -> {
                               Files.createDirectories(file.toAbsolutePath().getParent());
                               Files.writeString(file,
                                                 line + "\n",
                                                 StandardCharsets.UTF_8,
                                                 StandardOpenOption.CREATE,
                                                 StandardOpenOption.WRITE,
                                                 StandardOpenOption.APPEND);
                           });
    }
}
