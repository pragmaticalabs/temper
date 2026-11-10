package org.pragmatica.temper.controller.api;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.ResultRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// maintainer ruling Q-M4-11c: the controller's job and result JSON carry the user's `-D` properties, those that skip or narrow
/// the tests marked, derived from the stored recipe; a job without one carries an empty list.
class UserPropertiesViewTest {
    private static final String SHA = "a".repeat(40);
    private static final List<String> SKIPPED = List.of("-DskipTests", "-Dtest=FooTest", "-DfailIfNoSpecifiedTests=false", "install");
    private static final String SHOWN = "\"userProperties\":[{\"define\":\"-DskipTests=true\",\"tests\":\"skipped\"},"
                                        + "{\"define\":\"-Dtest=FooTest\",\"tests\":\"narrowed\"},"
                                        + "{\"define\":\"-DfailIfNoSpecifiedTests=false\",\"tests\":null}]";

    private final ApiFixture fixture = new ApiFixture(List.of());

    @Test
    void jobAndResult_showTheUsersSkipAndSelectProperties() {
        stored(41, SKIPPED);

        var job = fixture.authed(HttpMethod.GET, "/api/v1/jobs/41", "");
        var result = fixture.authed(HttpMethod.GET, "/api/v1/jobs/41/result", "");

        assertThat(job.status()).isEqualTo(HttpStatus.OK);
        assertThat(job.bodyText()).contains(SHOWN);
        assertThat(result.status()).isEqualTo(HttpStatus.OK);
        assertThat(result.bodyText()).contains(SHOWN);
    }

    @Test
    void jobAndResult_showNone_withoutAUserProperty() {
        stored(42, List.of("install"));

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/42", "").bodyText()).contains("\"userProperties\":[]");
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/42/result", "").bodyText()).contains("\"userProperties\":[]");
    }

    private void stored(long id, List<String> args) {
        var job = JobId.jobId(id).unwrap();

        fixture.jobs.rows.put(job,
                              JobRow.jobRow(job, OwnerTag.ownerTag("v2174").unwrap(), Instant.now(), -20, false, false, false, none(),
                                            some(Sha.sha(SHA).unwrap()), Recipe.mvn(args).unwrap(), none(), JobState.PASSED, none(),
                                            none(), false, none(), none(), none(), some("PASSED"), none(), List.of(), List.of()));
        fixture.results.records.put(job,
                                    ResultRecord.resultRecord(id,
                                                              "PASSED",
                                                              none(),
                                                              none(),
                                                              ResultRecord.Source.source(none(), some(SHA)),
                                                              ResultRecord.Recipe.Mvn.mvn(args),
                                                              List.of(),
                                                              Counts.counts(0, 0, 0, 0, 0, 0, 0, none(), none(), none()),
                                                              List.of(),
                                                              0,
                                                              List.of(),
                                                              none(),
                                                              ResultRecord.Evidence.evidence(true, none(), none()),
                                                              ResultRecord.Timings.timings(none(), none()),
                                                              some("1a2b3c4")));
    }
}
