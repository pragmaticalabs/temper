package org.pragmatica.temper.controller.api;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobQuery;
import org.pragmatica.temper.store.api.JobRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `state=UNKNOWN` is the union of two store reads: stored QUEUED jobs shown UNKNOWN, and stored UNKNOWN jobs. A job
/// that shows UNKNOWN before and after its mark is listed when the mark commits between the two reads. Read in the
/// other order, the first read sees the job still stored QUEUED and the second sees it already stored UNKNOWN: both
/// miss it.
class StateUnknownReadOrderTest {
    private static final JobId JOB = JobId.jobId(21).unwrap();
    private static final String LISTED = "{\"job\":21,\"state\":\"UNKNOWN\"";

    private final ApiFixture fixture = new ApiFixture(List.of());
    private final AtomicBoolean marked = new AtomicBoolean();

    @Test
    void stateUnknown_listsTheJob_markCommitsBetweenTheTwoReads() {
        fixture.jobs.rows.put(JOB, overdueQueued());
        fixture.jobs.afterList = this::markOnce;

        var body = fixture.authed(HttpMethod.GET, "/api/v1/jobs?state=UNKNOWN", "").bodyText();

        assertThat(marked).as("the mark committed after the first read").isTrue();
        assertThat(body).as("a job shown UNKNOWN before and after its mark").contains(LISTED);
    }

    /// Control: with no interleaving the job is listed before the mark (the QUEUED read) and after it (the UNKNOWN read).
    @Test
    void stateUnknown_listsTheJob_beforeAndAfterTheMark() {
        fixture.jobs.rows.put(JOB, overdueQueued());
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs?state=UNKNOWN", "").bodyText()).contains(LISTED);

        fixture.jobs.rows.put(JOB, storedUnknown());
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs?state=UNKNOWN", "").bodyText()).contains(LISTED);
    }

    private void markOnce(JobQuery ignored) {
        if (marked.compareAndSet(false, true)) {
            fixture.jobs.rows.put(JOB, storedUnknown());
        }
    }

    private static JobRow overdueQueued() {
        var overdue = Instant.now().minus(LinkTiming.DEFAULT.startBound()).minusSeconds(5);
        return Rows.job(21, JobState.QUEUED, List.of(Rows.granted(overdue, none())));
    }

    private static JobRow storedUnknown() {
        return Rows.job(21, JobState.UNKNOWN, List.of(Rows.unit(UnitState.UNKNOWN, some(HostName.hostName("bb").unwrap()), none())));
    }
}
