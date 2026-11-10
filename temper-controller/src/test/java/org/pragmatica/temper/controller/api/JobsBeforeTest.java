package org.pragmatica.temper.controller.api;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;

import static org.assertj.core.api.Assertions.assertThat;

/// `GET /jobs?before=<id>` (WP-C, for `temper jobs`): the next page, the jobs older than that id, newest first.
class JobsBeforeTest {
    private static final Pattern JOB = Pattern.compile("\\{\"job\":(\\d+),");
    private final ApiFixture fixture = new ApiFixture(List.of());

    private List<Long> ids(String path) {
        var response = fixture.authed(HttpMethod.GET, path, "");
        assertThat(response.status()).as(response.bodyText()).isEqualTo(HttpStatus.OK);
        return JOB.matcher(response.bodyText()).results().map(match -> Long.parseLong(match.group(1))).toList();
    }

    @Test
    void before_answersTheOlderJobs_newestFirst() {
        LongStream.rangeClosed(1, 6)
                  .forEach(id -> fixture.jobs.rows.put(JobId.jobId(id).unwrap(), Rows.job(id, JobState.PASSED, List.of())));

        assertThat(ids("/api/v1/jobs?limit=2")).containsExactly(6L, 5L);
        assertThat(ids("/api/v1/jobs?limit=2&before=5")).containsExactly(4L, 3L);
        assertThat(ids("/api/v1/jobs?limit=2&before=1")).isEmpty();
    }

    @Test
    void before_thatIsNotAJobId_is400() {
        for (var bad : List.of("x", "0", "-3")) {
            assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs?before=" + bad, "").status()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
