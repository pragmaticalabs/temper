package org.pragmatica.temper.controller.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.CancelOutcome;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.UnitRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class ApiRoutesTest {
    private final ApiFixture fixture = new ApiFixture(List.of());

    @Test
    void cancel_running_sendsCancelForUnitsToKill() {
        var ref = UnitRef.unitRef(JobId.jobId(7).unwrap(), UnitName.MAIN);
        fixture.jobs.nextCancel = new CancelOutcome.Cancelling(JobId.jobId(7).unwrap(), List.of(ref));

        var response = fixture.authed(HttpMethod.POST, "/api/v1/jobs/7/cancel", "");

        assertThat(response.bodyText()).isEqualTo("{\"state\":\"CANCELLING\"}");
        assertThat(fixture.cancelled).containsExactly(List.of(ref));
    }

    /// The kill is a step of the cancel, not a detached callback: with a store that answers on another thread, the
    /// `CancelUnit` requests were handed over by the time the response is.
    @Test
    void cancel_running_sendsCancelBeforeAnswering_storeAnsweringLater() {
        var ref = UnitRef.unitRef(JobId.jobId(7).unwrap(), UnitName.MAIN);
        fixture.jobs.nextCancel = new CancelOutcome.Cancelling(JobId.jobId(7).unwrap(), List.of(ref));
        fixture.jobs.delayMs = 1;

        for (var call = 1; call <= 200; call++) {
            var response = fixture.authed(HttpMethod.POST, "/api/v1/jobs/7/cancel", "");

            assertThat(response.bodyText()).isEqualTo("{\"state\":\"CANCELLING\"}");
            assertThat(fixture.cancelled).as("call " + call).hasSize(call);
        }
    }

    @Test
    void cancel_terminal_reportsItsState() {
        fixture.jobs.nextCancel = new CancelOutcome.AlreadyTerminal(JobId.jobId(7).unwrap(), JobState.PASSED);

        assertThat(fixture.authed(HttpMethod.POST, "/api/v1/jobs/7/cancel", "").bodyText()).isEqualTo("{\"state\":\"PASSED\"}");
        assertThat(fixture.cancelled).isEmpty();
    }

    @Test
    void job_showsLeaseExpired_runningUnitPastLease() {
        var host = HostName.hostName("bb").unwrap();
        fixture.jobs.rows.put(JobId.jobId(8).unwrap(),
                              Rows.job(8, JobState.RUNNING, List.of(Rows.unit(UnitState.RUNNING, some(host),
                                                                              some(Instant.now().minusSeconds(60))))));

        var body = fixture.authed(HttpMethod.GET, "/api/v1/jobs/8", "").bodyText();

        assertThat(body).contains("\"leaseExpired\":true", "\"host\":\"bb\"");
    }

    /// Verification F6 (`Views` active states): an UNKNOWN unit runs, so a passed lease shows `leaseExpired` like a
    /// RUNNING one.
    @Test
    void job_showsLeaseExpired_unknownUnitPastLease() {
        var host = HostName.hostName("bb").unwrap();
        fixture.jobs.rows.put(JobId.jobId(15).unwrap(),
                              Rows.job(15, JobState.UNKNOWN, List.of(Rows.unit(UnitState.UNKNOWN, some(host),
                                                                               some(Instant.now().minusSeconds(60))))));

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/15", "").bodyText()).contains("\"state\":\"UNKNOWN\"",
                                                                                            "\"leaseExpired\":true");
    }

    @Test
    void job_showsThePortGroupHold_withItsTicket() {
        fixture.jobs.rows.put(JobId.jobId(12).unwrap(),
                              Rows.job(12, JobState.RUNNING, List.of(Rows.held(Instant.parse("2026-10-13T10:00:00Z"), "20261013-1"))));

        var body = fixture.authed(HttpMethod.GET, "/api/v1/jobs/12", "").bodyText();

        assertThat(body).contains("\"portGroup\":\"held\"", "\"portGroupSince\":\"2026-10-13T10:00:00Z\"", "\"ticket\":\"20261013-1\"");
    }

    @Test
    void job_refused_reasonIsRefusalCode() {
        assertThat(fixture.submit("{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"ref\":\"main\"}},\"recipe\":{\"mutate\":{}}}").status())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /// R-13, amended: the store still has the unit GRANTED and the job QUEUED (the UNKNOWN mark never landed), but the
    /// grant is past the start bound with no recorded start, so the view and the list show UNKNOWN.
    @Test
    void job_grantedPastTheStartBound_showsUnknown_inTheViewAndTheList() {
        fixture.jobs.rows.put(JobId.jobId(13).unwrap(), Rows.job(13, JobState.QUEUED, List.of(Rows.granted(Instant.now()
                                                                                                                 .minus(LinkTiming.DEFAULT.startBound())
                                                                                                                 .minusSeconds(1),
                                                                                                          none()))));

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/13", "").bodyText()).startsWith("{\"job\":13,\"state\":\"UNKNOWN\"")
                                                                                .contains("\"name\":\"main\",\"state\":\"UNKNOWN\"")
                                                                                .doesNotContain("QUEUED");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs", "").bodyText()).contains("{\"job\":13,\"state\":\"UNKNOWN\"");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs?state=QUEUED", "").bodyText()).isEqualTo("[]");
    }

    /// Verification F1 (`state=` after the store's limit): job 1 shows UNKNOWN (its grant is past the start bound), the
    /// newer job 2 is a plain QUEUED one. `state=UNKNOWN&limit=1` pages past job 2 and answers job 1.
    @Test
    void list_stateUnknown_limit1_findsTheOlderDerivedUnknownJob() {
        putJob(1, overdue());
        putJob(2, Rows.unit(UnitState.QUEUED, none(), none()));

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs?state=UNKNOWN&limit=1", "").bodyText())
            .startsWith("[{\"job\":1,\"state\":\"UNKNOWN\"")
            .doesNotContain("{\"job\":2,");
    }

    /// Verification F1: job 1 is a plain QUEUED job, the newer job 2 shows UNKNOWN. `state=QUEUED&limit=1` pages past
    /// job 2 and answers job 1, never `[]`.
    @Test
    void list_stateQueued_limit1_findsTheOlderQueuedJob() {
        putJob(1, Rows.unit(UnitState.QUEUED, none(), none()));
        putJob(2, overdue());

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs?state=QUEUED&limit=1", "").bodyText())
            .startsWith("[{\"job\":1,\"state\":\"QUEUED\"")
            .doesNotContain("{\"job\":2,");
    }

    /// Across several pages: the matches come newest first, at most `limit`, and the union for `state=UNKNOWN` keeps
    /// the stored UNKNOWN jobs and the derived ones in one order.
    @Test
    void list_stateFilters_pageUntilLimitMatches_newestFirst() {
        putJob(1, overdue());
        putJob(2, Rows.unit(UnitState.QUEUED, none(), none()));
        fixture.jobs.rows.put(JobId.jobId(3).unwrap(), Rows.job(3, JobState.UNKNOWN, List.of(Rows.unit(UnitState.UNKNOWN, none(), none()))));
        putJob(4, overdue());
        putJob(5, Rows.unit(UnitState.QUEUED, none(), none()));
        putJob(6, Rows.unit(UnitState.QUEUED, none(), none()));

        assertThat(ids("/api/v1/jobs?state=UNKNOWN&limit=2")).containsExactly(4L, 3L);
        assertThat(ids("/api/v1/jobs?state=UNKNOWN&limit=5")).containsExactly(4L, 3L, 1L);
        assertThat(ids("/api/v1/jobs?state=QUEUED&limit=3")).containsExactly(6L, 5L, 2L);
        assertThat(ids("/api/v1/jobs?limit=2")).containsExactly(6L, 5L);
    }

    private void putJob(long id, UnitRow unit) {
        fixture.jobs.rows.put(JobId.jobId(id).unwrap(), Rows.job(id, JobState.QUEUED, List.of(unit)));
    }

    private static UnitRow overdue() {
        return Rows.granted(Instant.now().minus(LinkTiming.DEFAULT.startBound()).minusSeconds(60), none());
    }

    private List<Long> ids(String path) {
        var matcher = Pattern.compile("\\{\"job\":(\\d+),").matcher(fixture.authed(HttpMethod.GET, path, "").bodyText());
        var found = new ArrayList<Long>();
        while (matcher.find()) {
            found.add(Long.parseLong(matcher.group(1)));
        }
        return found;
    }

    /// Just granted: the launch may still be under way, so the unit shows GRANTED and the job QUEUED.
    @Test
    void job_grantedWithinTheStartBound_showsQueued() {
        fixture.jobs.rows.put(JobId.jobId(14).unwrap(), Rows.job(14, JobState.QUEUED, List.of(Rows.granted(Instant.now(), none()))));

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/14", "").bodyText()).startsWith("{\"job\":14,\"state\":\"QUEUED\"")
                                                                                .contains("\"name\":\"main\",\"state\":\"GRANTED\"");
    }

    @Test
    void log_answers404_unitNeverGranted() {
        fixture.jobs.rows.put(JobId.jobId(9).unwrap(), Rows.job(9, JobState.QUEUED, List.of(Rows.unit(UnitState.QUEUED, none(), none()))));

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/9/log", "").status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void log_answers404_hostNotConfigured() {
        fixture.jobs.rows.put(JobId.jobId(10).unwrap(),
                              Rows.job(10, JobState.RUNNING, List.of(Rows.unit(UnitState.RUNNING, some(HostName.hostName("gone").unwrap()), none()))));

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/10/log?tail=50", "").status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void log_withoutUnit_usesTheJobsOnlyUnit() {
        fixture.jobs.rows.put(JobId.jobId(15).unwrap(),
                              Rows.job(15, JobState.RUNNING, List.of(Rows.named("gate", some(HostName.hostName("gone").unwrap())))));

        var response = fixture.authed(HttpMethod.GET, "/api/v1/jobs/15/log", "");

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.bodyText()).contains("host gone").doesNotContain("unit main");
    }

    @Test
    void log_withoutUnit_severalUnits_missNamesTheJobsUnits() {
        fixture.jobs.rows.put(JobId.jobId(16).unwrap(),
                              Rows.job(16, JobState.RUNNING, List.of(Rows.named("gate", none()), Rows.named("it", none()))));

        var response = fixture.authed(HttpMethod.GET, "/api/v1/jobs/16/log", "");

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.bodyText()).contains("unit main").contains("gate, it");
    }

    @Test
    void log_answers400_badTail() {
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/10/log?tail=abc", "").status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void result_answers404_untilTerminal() {
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/11/result", "").status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void next_answers204_nothingPending() {
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/subscribers/cto/next?terminal=true", "").status())
            .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void maintenance_answersNulls_beforeTheFirstRun() {
        var response = fixture.authed(HttpMethod.GET, "/api/v1/maintenance", "");

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.bodyText()).isEqualTo("{\"latestDump\":null,\"lastGoodDumpEndedAt\":null}");
    }

    /// The latest run is the failed one; the latest good run is the older one, by `ended_at` (not recording order).
    @Test
    void maintenance_showsTheLatestRun_andWhenTheLatestGoodOneEnded() {
        fixture.maintenance.runs.add(dump("2026-10-13T03:31:00Z", false));
        fixture.maintenance.runs.add(dump("2026-10-12T03:31:00Z", true));
        fixture.maintenance.runs.add(dump("2026-10-11T03:31:00Z", true));

        var body = fixture.authed(HttpMethod.GET, "/api/v1/maintenance", "").bodyText();

        assertThat(body).isEqualTo("{\"latestDump\":{\"kind\":\"pg_dump\",\"startedAt\":\"2026-10-13T03:30:00Z\","
                                   + "\"endedAt\":\"2026-10-13T03:31:00Z\",\"ok\":false,\"detail\":\"pg_restore --list failed\"},"
                                   + "\"lastGoodDumpEndedAt\":\"2026-10-12T03:31:00Z\"}");
    }

    @Test
    void maintenance_requiresTheToken() {
        assertThat(fixture.call(HttpMethod.GET, "/api/v1/maintenance", "", Map.of()).status())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static MaintenanceRun dump(String endedAt, boolean ok) {
        var ended = Instant.parse(endedAt);

        return MaintenanceRun.maintenanceRun(MaintenanceKind.PG_DUMP,
                                             ended.minusSeconds(60),
                                             ended,
                                             ok,
                                             ok ? none() : some("pg_restore --list failed"),
                                             ok ? some(Path.of("/ev/pgdump/d.dump")) : none(),
                                             ok ? some(4096L) : none());
    }
}
