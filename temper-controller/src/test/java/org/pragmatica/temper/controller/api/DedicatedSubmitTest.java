package org.pragmatica.temper.controller.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;


/// `POST /jobs` with `dedicatedCpu` (m4-design §9.2, §9.3): the class reaches the stored job, and enters its dedup key
/// (a plain resubmit never reuses a dedicated job's verdict); a refused submit records it too.
class DedicatedSubmitTest {
    private static final String BODY = "{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"ref\":\"main\"}},"
                                       + "\"recipe\":{\"mvn\":{\"args\":[\"-pl\",\"core\",\"verify\"]}}";

    private final ApiFixture fixture = new ApiFixture(List.of());

    private HttpStatus post(String body) {
        return fixture.authed(HttpMethod.POST, "/api/v1/jobs", body).status();
    }

    @Test
    void submit_dedicatedCpu_isStored_andChangesTheDedupKey() {
        assertThat(post(BODY + ",\"dedicatedCpu\":true}")).isEqualTo(HttpStatus.OK);
        assertThat(post(BODY + "}")).isEqualTo(HttpStatus.OK);
        assertThat(post(BODY + ",\"dedicatedCpu\":false}")).isEqualTo(HttpStatus.OK);

        assertThat(fixture.jobs.submitted).extracting(job -> job.dedicatedCpu()).containsExactly(true, false, false);
        assertThat(fixture.jobs.submitted.get(0).dedupKey()).isNotEqualTo(fixture.jobs.submitted.get(1).dedupKey());
        assertThat(fixture.jobs.submitted.get(1).dedupKey()).isEqualTo(fixture.jobs.submitted.get(2).dedupKey());
    }

    /// R-SHELL refuses before planning; the refused row still says which class was asked for.
    @Test
    void submit_refused_recordsDedicatedCpu() {
        var shell = "{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"ref\":\"main\"}},"
                    + "\"recipe\":{\"mvn\":{\"args\":[\"exec:exec\"]}},\"dedicatedCpu\":true}";

        assertThat(post(shell)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(fixture.jobs.refused).extracting(job -> job.dedicatedCpu()).containsExactly(true);
    }
}
