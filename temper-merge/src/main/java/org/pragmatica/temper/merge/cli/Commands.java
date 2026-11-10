package org.pragmatica.temper.merge.cli;

import java.time.InstantSource;
import java.util.HexFormat;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

import org.pragmatica.temper.merge.engine.Job;
import org.pragmatica.temper.merge.store.EventsLog;
import org.pragmatica.temper.merge.store.JobFiles;


/// `submit` and `status` (maintainer ruling, #101): run over ssh on the runner's host, they touch only the job files and the
/// events log; the serve loop does every GitHub read and write.
///
/// - `submit <pr> <sha> [--ready]`: records a `SUBMITTED` job and prints its id. `<sha>` must be the full 40-hex
///   head (GitHub's `expectedHeadOid` takes nothing shorter). Nothing is checked against GitHub here: the serve
///   loop's first step does that, and refuses with the reason.
/// - `status <id>`: the job's state, reason, last observation and its outcome fields, one `key=value` per line.
///
/// Exit: 0 done; 2 usage; 3 no such job; 1 the store could not be read or written.
public record Commands(RunnerConfig config,
                       JobFiles jobs,
                       EventsLog events,
                       InstantSource clock,
                       RandomGenerator random) {
    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int USAGE = 2;
    public static final int NOT_FOUND = 3;
    static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    static final Pattern PR = Pattern.compile("[1-9][0-9]{0,8}");

    /// What a command prints and its exit code.
    public record Outcome(int code, List<String> lines) {
        static Outcome of(int code, String... lines) {
            return new Outcome(code, List.of(lines));
        }
    }

    public static Commands commands(RunnerConfig config, InstantSource clock, RandomGenerator random) {
        return new Commands(config,
                            JobFiles.jobFiles(config.jobs()),
                            EventsLog.eventsLog(config.eventsLog()),
                            clock,
                            random);
    }

    public Outcome submit(List<String> args) {
        var ready = args.contains("--ready");
        var plain = args.stream().filter(arg -> !"--ready".equals(arg)).toList();

        if (plain.size() != 2 || !PR.matcher(plain.get(0)).matches() || !SHA.matcher(plain.get(1)).matches()) {
            return Outcome.of(USAGE, "usage: temper-merge submit <pr> <sha40, lowercase hex> [--ready]");
        }

        var pr = Long.parseLong(plain.get(0));
        var now = clock.instant();
        var job = Job.submitted(idOf(pr, now.getEpochSecond()), config.repo(), pr, plain.get(1), ready, now);

        return jobs.create(job)
                   .flatMap(_ -> events.append(job, now))
                   .fold(cause -> Outcome.of(FAILED,
                                             "temper-merge: " + cause.message()),
                         _ -> Outcome.of(OK,
                                         job.id()));
    }

    public Outcome status(List<String> args) {
        if (args.size() != 1) {
            return Outcome.of(USAGE, "usage: temper-merge status <job id>");
        }

        var id = args.getFirst();

        return jobs.load(id)
                   .fold(cause -> Outcome.of(FAILED,
                                             "temper-merge: " + cause.message()),
                         found -> found.fold(() -> Outcome.of(NOT_FOUND, "temper-merge: no job " + id),
                                             job -> new Outcome(OK,
                                                                described(job))));
    }

    static List<String> described(Job job) {
        return List.of("job=" + job.id(),
                       "repo=" + job.repo(),
                       "pr=" + job.pr(),
                       "sha=" + job.sha(),
                       "state=" + job.state(),
                       "reason=" + job.reason(),
                       "since=" + job.phaseSince(),
                       "observed=" + job.lastObservation(),
                       "merge=" + job.mergeSha(),
                       "closed=" + String.join(",", job.closedIssues()),
                       "removal=" + job.removal(),
                       "run=" + job.run(),
                       "failedJobs=" + String.join(",", job.failedJobs()),
                       "failedTests=" + String.join(",", job.failedTests()));
    }

    private String idOf(long pr, long epochSecond) {
        var bytes = new byte[2];

        random.nextBytes(bytes);

        return "mr-" + epochSecond + "-" + pr + "-" + HexFormat.of().formatHex(bytes);
    }
}
