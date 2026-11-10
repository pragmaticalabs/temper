package org.pragmatica.temper.domain.task;

import java.util.List;

import org.pragmatica.temper.domain.model.JobId;


/// What a WAITING task waits for (m3-design §6.6): linked temper jobs, GitHub CI, or both. Empty outside WAITING.
public record Waiting(List<JobId> jobs, boolean ci) {
    public static final Waiting NOTHING = new Waiting(List.of(), false);
    /// A task in CI waits on the watcher (§5.1).
    public static final Waiting CI_ONLY = new Waiting(List.of(), true);

    /// Each job once: a job named twice is waited for once (a duplicate must never keep a settled wait open).
    public Waiting {
        jobs = jobs.stream().distinct().toList();
    }

    public static Waiting waiting(List<JobId> jobs, boolean ci) {
        return new Waiting(jobs, ci);
    }

    /// T5's guard: at least one job or CI.
    public boolean isAnything() {
        return ci || !jobs.isEmpty();
    }
}
