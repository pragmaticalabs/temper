package org.pragmatica.temper.controller.fake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.retention.JobRetention;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.RetentionStore;

import static org.pragmatica.lang.Option.none;

/// An in-memory [RetentionStore]: the candidate jobs (paged by id as the store does), the event horizon, and a record
/// of every deletion asked for.
public final class FakeRetention implements RetentionStore {
    public final List<JobRetention> jobs = new CopyOnWriteArrayList<>();
    public volatile Option<Long> horizon = none();
    public final List<Instant> horizonAsked = new CopyOnWriteArrayList<>();
    public final List<Instant> finishedBeforeAsked = new CopyOnWriteArrayList<>();
    public final List<JobId> rowsDeleted = new CopyOnWriteArrayList<>();
    public final List<Long> eventsDeletedUpTo = new CopyOnWriteArrayList<>();
    public final List<String> checkoutsGone = new CopyOnWriteArrayList<>();
    public final AtomicInteger pages = new AtomicInteger();
    public volatile boolean hangJobs = false;
    public volatile boolean failJobs = false;

    @Override
    public Promise<List<JobRetention>> jobs(Instant finishedBefore, Option<JobId> after, int limit) {
        pages.incrementAndGet();
        finishedBeforeAsked.add(finishedBefore);
        if (hangJobs) {
            return Promise.promise();
        }
        if (failJobs) {
            return Causes.cause("store down").promise();
        }
        var from = after.map(JobId::value).or(0L);
        var page = new ArrayList<JobRetention>();
        jobs.stream()
            .filter(job -> job.job().value() > from)
            .sorted((a, b) -> Long.compare(a.job().value(), b.job().value()))
            .limit(limit)
            .forEach(page::add);
        return Promise.success(List.copyOf(page));
    }

    @Override
    public Promise<Option<Long>> eventHorizon(Instant before) {
        horizonAsked.add(before);
        return Promise.success(horizon);
    }

    @Override
    public Promise<Long> deleteJobRows(JobId job) {
        rowsDeleted.add(job);
        return Promise.success(3L);
    }

    @Override
    public Promise<Long> deleteEventsUpTo(long upTo) {
        eventsDeletedUpTo.add(upTo);
        return Promise.success(upTo);
    }

    @Override
    public Promise<Unit> checkoutGone(HostName host, JobId job) {
        checkoutsGone.add(host.value() + "/" + job.value());
        return Promise.unitPromise();
    }
}
