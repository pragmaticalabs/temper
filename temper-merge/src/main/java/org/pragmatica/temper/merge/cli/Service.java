package org.pragmatica.temper.merge.cli;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.merge.engine.Engine;
import org.pragmatica.temper.merge.engine.Job;
import org.pragmatica.temper.merge.engine.OneLine;
import org.pragmatica.temper.merge.store.JobFiles;


/// One round of the serve loop: every job not terminal, read from the store (so a restarted runner resumes exactly
/// where the files say), advanced one step each, in submit order, one after another. A job whose step fails (its
/// record could not be written) is reported on stderr and tried again next round; the others go on.
public record Service(JobFiles jobs, Engine engine) {
    public static Service service(JobFiles jobs, Engine engine) {
        return new Service(jobs, engine);
    }

    /// The jobs as this round left them (the terminal ones untouched).
    public Promise<List<Job>> round() {
        return jobs.all()
                   .async()
                   .flatMap(all -> stepped(all.stream().filter(job -> !job.state()
                                                                          .terminal()).toList(),
                                           0,
                                           List.of()));
    }

    private Promise<List<Job>> stepped(List<Job> pending, int next, List<Job> done) {
        if (next == pending.size()) {
            return Promise.success(done);
        }

        var job = pending.get(next);

        return engine.advance(job)
                     .fold(step -> Promise.success(step.fold(cause -> failed(job,
                                                                             OneLine.of(cause)),
                                                             advanced -> advanced)))
                     .flatMap(advanced -> stepped(pending,
                                                  next + 1,
                                                  appended(done, advanced)));
    }

    private static Job failed(Job job, String why) {
        System.err.println("temper-merge: job " + job.id() + " step not recorded: " + why);

        return job;
    }

    private static List<Job> appended(List<Job> done, Job job) {
        return Stream.concat(done.stream(),
                             Stream.of(job))
                     .toList();
    }
}
