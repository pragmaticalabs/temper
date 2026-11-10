package org.pragmatica.temper.merge.engine;

import org.pragmatica.lang.Promise;


/// Where every job change goes before the runner acts on it: the store (so a restart resumes) and, when the state
/// changed, the report (one events line). Its success is the change being durable.
@FunctionalInterface
public interface JobLedger {
    /// Records `after`; `before` tells whether the state changed. Answers `after`.
    Promise<Job> record(Job before, Job after);
}
