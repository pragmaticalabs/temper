package org.pragmatica.temper.store.api;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;


/// Everything a digest reads from the store, from ONE snapshot ([DigestStore]): the event window, the jobs its events
/// name (with their units), the waiting jobs (QUEUED, or any other with a unit still QUEUED) in grant order (priority
/// descending, submit time, id) up to [DigestStore#QUEUE_LIMIT] with `queued` their full count, the QUEUED units a grant would read now (`candidates`: its
/// rows, order and [UnitStore#CANDIDATE_LIMIT], without its locks), the hosts, and the latest and the latest good
/// `pg_dump` run.
public record DigestSnapshot(EventWindow window,
                             Map<JobId, JobRow> jobs,
                             List<JobRow> queue,
                             int queued,
                             List<QueuedUnit> candidates,
                             List<StoredHost> hosts,
                             Option<MaintenanceRun> latestDump,
                             Option<MaintenanceRun> lastGoodDump) {
    public DigestSnapshot {
        jobs = Map.copyOf(jobs);
        queue = List.copyOf(queue);
        candidates = List.copyOf(candidates);
        hosts = List.copyOf(hosts);
    }

    public static DigestSnapshot digestSnapshot(EventWindow window,
                                                Map<JobId, JobRow> jobs,
                                                List<JobRow> queue,
                                                int queued,
                                                List<QueuedUnit> candidates,
                                                List<StoredHost> hosts,
                                                Option<MaintenanceRun> latestDump,
                                                Option<MaintenanceRun> lastGoodDump) {
        return new DigestSnapshot(window, jobs, queue, queued, candidates, hosts, latestDump, lastGoodDump);
    }
}
