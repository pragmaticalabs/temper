package org.pragmatica.temper.controller.api;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/// `POST /jobs` with a `task` link (m3-design §6.4, review #1): any `Authorization: Lease` header reaches the route, so
/// the lease must be checked before the submit does any work of its own. A lease that does not hold is `409 lease-lost`
/// with no git, mirror or tree read and nothing stored; one that holds is submitted, linked, under it.
///
/// Probe: drop the lease check before the source resolution (`SubmitJob.answer` resolves at once): the git-call count
/// of `bogusLease_is409_beforeAnyGitOrTreeRead` is no longer zero, red.
class SubmitUnderLeaseTest {
    private static final String LINKED = "{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"ref\":\"main\"}},"
                                         + "\"recipe\":{\"mvn\":{\"args\":[\"-pl\",\"core\",\"verify\"]}},\"task\":{\"id\":42,\"epoch\":7}}";

    private final ApiFixture fixture = new ApiFixture(List.of());

    private ApiResponseView post(String authorization) {
        var response = fixture.call(HttpMethod.POST, "/api/v1/jobs", LINKED, Map.of("Authorization", authorization));

        return new ApiResponseView(response.status(), response.bodyText());
    }

    private record ApiResponseView(HttpStatus status, String body) {}

    @Test
    void bogusLease_is409_beforeAnyGitOrTreeRead() {
        var response = post("Lease an-invented-token");

        assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.body()).contains("lease-lost");
        assertThat(fixture.gitCalls.get()).as("git, mirror and tree reads").isZero();
        assertThat(fixture.jobs.submitted).isEmpty();
        assertThat(fixture.jobs.refused).isEmpty();
    }

    @Test
    void heldLease_submitsTheJobUnderIt() {
        var response = post("Lease " + ApiFixture.LEASE_TOKEN);

        assertThat(response.status()).as(response.body()).isEqualTo(HttpStatus.OK);
        assertThat(fixture.gitCalls.get()).isPositive();
        assertThat(fixture.jobs.submitted).hasSize(1);
        assertThat(fixture.jobs.submitted.getFirst().task().map(link -> link.lease().task().value() + "@" + link.lease().epoch()).unwrap())
            .isEqualTo("42@7");
    }
}
