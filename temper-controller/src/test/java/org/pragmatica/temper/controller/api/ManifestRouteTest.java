package org.pragmatica.temper.controller.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.store.api.JobManifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// `GET /jobs/{id}/manifest` (m1-design §6; the T1 harness reads it): the expected classes, and `missing`/`drift`
/// against the reported testcases, uncapped and sorted, judged only for a computed manifest.
class ManifestRouteTest {
    private final ApiFixture fixture = new ApiFixture(List.of());

    private static final List<ManifestEntry> ENTRIES = List.of(ManifestEntry.manifestEntry("core", "org.x.ATest", Runner.SUREFIRE),
                                                               ManifestEntry.manifestEntry("core", "org.x.CrashTest", Runner.SUREFIRE),
                                                               ManifestEntry.manifestEntry("forge", "org.x.ZIT", Runner.FAILSAFE));

    private static TestcaseRow row(String className) {
        return TestcaseRow.testcaseRow(UnitName.unitName("gate").unwrap(), className, "t", Outcome.PASSED, 0.1, false);
    }

    @Test
    void manifest_listsEntries_missingAndDrift() {
        fixture.results.manifest = some(JobManifest.jobManifest(true, false, ENTRIES, List.of(), List.of("aether/ember"), 2));
        fixture.results.rows = List.of(row("org.x.ATest"), row("org.x.ATest"), row("org.x.ZIT"), row("org.x.Stray"), row("org.x.Another"));

        var response = fixture.authed(HttpMethod.GET, "/api/v1/jobs/5/manifest", "");

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.bodyText()).contains("\"computed\":true",
                                                 "{\"module\":\"core\",\"class\":\"org.x.ATest\",\"runner\":\"surefire\"}",
                                                 "\"missing\":[\"org.x.CrashTest\"]",
                                                 "\"drift\":[\"org.x.Another\",\"org.x.Stray\"]",
                                                 "\"notReached\":[\"aether/ember\"]");
    }

    @Test
    void manifest_notComputed_judgesNothing() {
        fixture.results.manifest = some(JobManifest.jobManifest(false, false, ENTRIES, List.of(), List.of(), 0));
        fixture.results.rows = List.of(row("org.x.Stray"));

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/5/manifest", "").bodyText())
            .contains("\"computed\":false", "\"missing\":[]", "\"drift\":[]");
    }

    /// Ruling R-2: an optional class is neither missing (when silent) nor drift (when it reports).
    @Test
    void manifest_optionalClasses_areNeitherMissingNorDrift() {
        var optional = List.of(ManifestEntry.manifestEntry("core", "org.x.RunWithTest", Runner.SUREFIRE),
                               ManifestEntry.manifestEntry("core", "org.x.SilentTest", Runner.SUREFIRE));
        fixture.results.manifest = some(JobManifest.jobManifest(true, false, ENTRIES, optional, List.of(), 0));
        fixture.results.rows = List.of(row("org.x.ATest"), row("org.x.CrashTest"), row("org.x.ZIT"), row("org.x.RunWithTest"),
                                       row("org.x.Stray"));

        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/5/manifest", "").bodyText())
            .contains("\"optional\":[{\"module\":\"core\",\"class\":\"org.x.RunWithTest\",\"runner\":\"surefire\"},"
                      + "{\"module\":\"core\",\"class\":\"org.x.SilentTest\",\"runner\":\"surefire\"}]",
                      "\"missing\":[]",
                      "\"drift\":[\"org.x.Stray\"]");
    }

    /// Ruling R-7: once retention deleted the rows the view says so; computed, not pruned and empty means no tests.
    @Test
    void manifest_saysPruned_onlyWhenRetentionDeletedTheRows() {
        fixture.results.manifest = some(JobManifest.jobManifest(true, true, List.of(), List.of(), List.of(), 0));
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/5/manifest", "").bodyText())
            .contains("\"computed\":true", "\"pruned\":true", "\"entries\":[]");

        fixture.results.manifest = some(JobManifest.jobManifest(true, false, List.of(), List.of(), List.of(), 0));
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/5/manifest", "").bodyText())
            .contains("\"computed\":true", "\"pruned\":false", "\"entries\":[]");
    }

    @Test
    void manifest_answers404_jobWithoutManifest() {
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/5/manifest", "").status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/jobs/x/manifest", "").status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
