package org.pragmatica.temper.domain.subscribe;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.model.JobId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// m2-design §2.2: the CLI's classification of a bare argument, the prefixed wire form, and typed matching (F3).
class SelectorTest {
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";

    /// Bare argument → stored form. An explicit prefix wins; a numeric string is a job before it is hex.
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"4711, job:4711",
                "1234567, job:1234567",
                "9223372036854775807, job:9223372036854775807",
                "0cf563192a0cf563192a0cf563192a0cf563192a, sha:0cf563192a0cf563192a0cf563192a0cf563192a",
                "main, ref:main",
                "rewrite/deployment-fsm, ref:rewrite/deployment-fsm",
                "0abc, ref:0abc",
                "0123, ref:0123",
                "abcdef, ref:abcdef",
                "release-1.0.0-rc4, ref:release-1.0.0-rc4",
                "ref:4711, ref:4711",
                "ref:0cf5631, ref:0cf5631",
                "ref:0cf563192a0cf563192a0cf563192a0cf563192a, ref:0cf563192a0cf563192a0cf563192a0cf563192a",
                "job:4711, job:4711",
                "sha:0cf563192a0cf563192a0cf563192a0cf563192a, sha:0cf563192a0cf563192a0cf563192a0cf563192a",
                "0CF563192A0CF563192A0CF563192A0CF563192A, sha:0cf563192a0cf563192a0cf563192a0cf563192a",
                "Release-1, ref:Release-1"})
    void classified_bareAndPrefixedArguments(String raw, String stored) {
        assertThat(ok(Selector.classified(raw)).wire()).isEqualTo(stored);
    }

    /// 7 to 39 hex matches no payload (they carry 40): refused, so nobody waits on a selector that can never match.
    @ParameterizedTest
    @ValueSource(strings = {"0cf5631", "0cf563192a", "deadbeefcafe", "0cf563192a0cf563192a0cf563192a0cf563192", "0CF5631",
                            "DeadBeef"})
    void classified_shortSha_isRefusedAsAmbiguous(String raw) {
        assertThat(failure(Selector.classified(raw))).isInstanceOf(SelectorError.AmbiguousShortSha.class)
                                                     .extracting(cause -> cause.message())
                                                     .asString()
                                                     .contains("sha:<full 40 hex> or ref:<name>");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-main", "a..b", "a b", "job:0", "job:-1", "job:+5", "job:12a", "job:", "sha:0cf5631",
                            "sha:", "ref:", "ref:-x"})
    void classified_refusesWhatNoTypeAccepts(String raw) {
        assertThat(failure(Selector.classified(raw))).isInstanceOf(DomainError.class);
    }

    @Test
    void classified_jobIdAboveLongRange_isAnInvalidJobId_withoutTheJdkText() {
        for (var raw : List.of("9223372036854775808", "9999999999999999999", "job:9223372036854775808")) {
            var cause = failure(Selector.classified(raw));

            assertThat(cause).as(raw).isInstanceOf(DomainError.InvalidValue.class);
            assertThat(cause.message()).as(raw).contains("at most 9223372036854775807").doesNotContain("NumberFormatException");
            assertThat(cause.source().isEmpty()).as(raw).isTrue();
        }
    }

    /// The wire form needs its prefix: a bare value on the wire is never guessed at.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"4711", "main", SHA, "JOB:1", " job:1", "jobs:1"})
    void selector_wireFormWithoutPrefix_isRefused(String raw) {
        assertThat(failure(Selector.selector(raw))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"job:4711", "ref:main", "ref:4711", "sha:" + SHA})
    void selector_wireForm_roundTrips(String raw) {
        assertThat(ok(Selector.selector(raw)).wire()).isEqualTo(raw);
    }

    @Test
    void selector_shaIsStoredLowercase() {
        assertThat(ok(Selector.selector("sha:" + SHA.toUpperCase())).wire()).isEqualTo("sha:" + SHA);
    }

    // ---- matching (F3) -------------------------------------------------------------------------------------------
    private static final Option<JobId> JOB_4711 = some(ok(JobId.jobId(4711)));

    private static boolean matches(String selector, Option<JobId> job, Option<String> sha, Option<String> ref) {
        return ok(Selector.selector(selector)).matches(job, sha, ref);
    }

    @Test
    void matches_eachTypeAgainstItsOwnFieldOnly() {
        assertThat(matches("job:4711", JOB_4711, none(), none())).isTrue();
        assertThat(matches("job:4711", some(ok(JobId.jobId(4712))), none(), none())).isFalse();
        assertThat(matches("sha:" + SHA, none(), some(SHA), none())).isTrue();
        assertThat(matches("ref:main", none(), none(), some("main"))).isTrue();
        assertThat(matches("ref:main", none(), none(), some("refs/heads/main"))).as("the ref as submitted").isFalse();
    }

    /// F3: under untyped equality a ref named `4711` matched job 4711, and a job selector matched a ref `4711`.
    @Test
    void matches_refNamed4711_neverMatchesJob4711_andTheReverse() {
        assertThat(matches("ref:4711", JOB_4711, some(SHA), some("main"))).isFalse();
        assertThat(matches("job:4711", some(ok(JobId.jobId(1))), some(SHA), some("4711"))).isFalse();
        assertThat(matches("ref:" + SHA, JOB_4711, some(SHA), none())).as("a ref spelled like the SHA").isFalse();
        assertThat(matches("sha:" + SHA, JOB_4711, none(), some(SHA))).as("a ref spelled like the SHA").isFalse();
    }

    /// A job submitted by SHA carries no ref: a `ref:` selector never matches it.
    @Test
    void matches_refSelector_neverMatchesAJobSubmittedBySha() {
        assertThat(matches("ref:main", JOB_4711, some(SHA), none())).isFalse();
    }

    /// F4 (ruled R-M2-9): no selector means every job; one selector narrows to it.
    @Test
    void selects_emptyMeansEveryJob_oneSelectorNarrows() {
        var job = ok(Selector.selector("job:4711"));

        assertThat(Selector.selects(List.of(), some(ok(JobId.jobId(1))), none(), none())).isTrue();
        assertThat(Selector.selects(List.of(), none(), none(), none())).as("a job-less event too").isTrue();
        assertThat(Selector.selects(List.of(job), some(ok(JobId.jobId(1))), none(), none())).isFalse();
        assertThat(Selector.selects(List.of(job), JOB_4711, none(), none())).isTrue();
    }
}
