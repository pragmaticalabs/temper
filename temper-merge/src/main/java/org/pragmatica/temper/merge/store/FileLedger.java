package org.pragmatica.temper.merge.store;

import java.time.InstantSource;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.merge.engine.Job;
import org.pragmatica.temper.merge.engine.JobLedger;


/// The [JobLedger] over the job files and the events log. A state change is appended to the log FIRST, then the job is
/// saved: a crash between the two repeats the line after the restart (the step runs again) rather than lose it. A
/// change of the observation alone is saved without a line.
public record FileLedger(JobFiles jobs, EventsLog events, InstantSource clock) implements JobLedger {
    public static FileLedger fileLedger(JobFiles jobs, EventsLog events, InstantSource clock) {
        return new FileLedger(jobs, events, clock);
    }

    @Override
    public Promise<Job> record(Job before, Job after) {
        var logged = before.state() == after.state()
                     ? jobs.save(after)
                     : events.append(after, clock.instant()).flatMap(_ -> jobs.save(after));

        return logged.map(_ -> after)
                     .async();
    }
}
