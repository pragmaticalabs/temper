package org.pragmatica.temper.controller.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.mvn.ReactorModules;
import org.pragmatica.temper.domain.refusal.ClassSource;
import org.pragmatica.temper.domain.refusal.RefusalRule;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.store.api.SubmitOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// A1 (controller side): POST the offending form, assert 422 with `refused=<code>` and the persisted REFUSED job.
class SubmitJobTest {
    private static String submit(String recipe) {
        return submit("{\"pushed\":{\"ref\":\"main\"}}", recipe, "");
    }

    private static String submit(String source, String recipe, String extra) {
        return "{\"submittedBy\":\"v2174\",\"source\":" + source + ",\"recipe\":" + recipe + extra + "}";
    }

    private static String mvn(String... args) {
        return "{\"mvn\":{\"args\":[" + String.join(",", java.util.Arrays.stream(args).map(arg -> "\"" + arg + "\"").toList()) + "]}}";
    }

    private static void assertRefused(ApiFixture fixture, String json, String code) {
        var response = fixture.submit(json);

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.bodyText()).contains("\"refused\":\"" + code + "\"").contains("\"job\":101");
        assertThat(fixture.refusalsRecorded()).isEqualTo(1);
        assertThat(fixture.jobs.refusals.getFirst().code()).isEqualTo(code);
        assertThat(fixture.jobs.submitted).isEmpty();
    }

    @Nested
    class Refusals {
        private final ApiFixture fixture = new ApiFixture(List.of("aether/ember"));

        @Test
        void submit_refusesREnv_envField() {
            assertRefused(fixture, submit("{\"pushed\":{\"ref\":\"main\"}}", mvn("verify"), ",\"env\":{\"HCLOUD_TOKEN\":\"x\"}"), "R-ENV");
        }

        @Test
        void submit_refusesREnv_environmentField() {
            assertRefused(fixture, submit("{\"pushed\":{\"ref\":\"main\"}}", mvn("verify"), ",\"environment\":{}"), "R-ENV");
        }

        @Test
        void submit_refusesRM2_userRepoLocal() {
            assertRefused(fixture, submit(mvn("-pl", "core", "verify", "-Dmaven.repo.local=/x")), "R-M2");
        }

        @Test
        void submit_refusesRM2_settingsFile() {
            assertRefused(fixture, submit(mvn("-s", "s.xml", "-pl", "core", "verify")), "R-M2");
        }

        @Test
        void submit_refusesRShell_execGoal() {
            assertRefused(fixture, submit(mvn("-pl", "core", "exec:exec")), "R-SHELL");
        }

        /// m4-design §5.3 (R-M4-14): an `Mvn` that publishes is refused at submit, never planned to a unit row.
        @Test
        void submit_refusesREffect_deploy() {
            assertRefused(fixture, submit(mvn("deploy")), "R-EFFECT");
        }

        /// Q-M4-11b: a user `-D` off the property allowlist is refused through the whole submit path.
        @Test
        void submit_refusesREffect_releaseProperty() {
            assertRefused(fixture, submit(mvn("-Drelease=true", "install")), "R-EFFECT");
        }

        /// Q-M4-11b scope: the planner appends `-Dmaven.repo.local=${ws}/.m2-local` to every step; only the user's arguments are judged as properties, so this submit is accepted.
        @Test
        void submit_accepts_listedUserProperties_withThePlannersOwn() {
            assertThat(fixture.submit(submit(mvn("-pl", "core", "-Dtest=X", "-DskipITs", "verify"))).status()).isEqualTo(HttpStatus.OK);
        }

        /// M1 (m1-design §1.6): bb-hold holds the port group, so a unit that needs it is accepted, no longer
        /// `REFUSED(port-group)`.
        @Test
        void submit_accepts_portGroupUnit() {
            assertThat(fixture.submit(submit("{\"moduleTest\":{\"pl\":[\"aether/ember\"]}}")).status()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void submit_refusesR1553_enabledClassAndNoExclusion() {
            fixture.refusals.rules = List.of(RefusalRule.ExcludeTestClass.excludeTestClass("R-1553", "StreamPublishReshuffleTest", "#1553", none()));
            fixture.tree.put("StreamPublishReshuffleTest",
                             List.of(ClassSource.classSource("aether/x/src/test/java/StreamPublishReshuffleTest.java",
                                                             "class StreamPublishReshuffleTest {}")));

            assertRefused(fixture, submit("{\"moduleTest\":{\"pl\":[\"aether/x\"]}}"), "R-1553");
        }

        @Test
        void submit_accepts_r1553ClassDisabled() {
            fixture.refusals.rules = List.of(RefusalRule.ExcludeTestClass.excludeTestClass("R-1553", "StreamPublishReshuffleTest", "#1553", none()));
            fixture.tree.put("StreamPublishReshuffleTest",
                             List.of(ClassSource.classSource("aether/x/src/test/java/StreamPublishReshuffleTest.java",
                                                             "@Disabled(\"#1553\")\nclass StreamPublishReshuffleTest {}")));

            assertThat(fixture.submit(submit("{\"moduleTest\":{\"pl\":[\"aether/x\"]}}")).status()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void submit_refusesRFailsafe_testWithoutItTest() {
            fixture.refusals.rules = List.of(RefusalRule.ItTestRequired.itTestRequired("R-FAILSAFE", "forge-tests"));

            assertRefused(fixture, submit("{\"moduleTest\":{\"pl\":[\"aether/forge/forge-tests\"],\"test\":\"X\"}}"), "R-FAILSAFE");
        }

        @Test
        void submit_refusesSource_unknownRef() {
            assertRefused(fixture, submit("{\"pushed\":{\"ref\":\"nope\"}}", mvn("verify"), ""), "source");
            assertThat(fixture.jobs.refused.getFirst().source().isEmpty()).isTrue();
        }

        @Test
        void submit_refusesSource_shaNotInAnyHead() {
            assertRefused(fixture, submit("{\"pushed\":{\"sha\":\"" + "b".repeat(40) + "\"}}", mvn("verify"), ""), "source");
        }

        @Test
        void submit_refusesUnsupported_mutateRecipe() {
            assertRefused(fixture, submit("{\"mutate\":{}}"), "unsupported");
        }

        /// M1 (m1-design §1.1): the Gate is a supported recipe.
        @Test
        void submit_accepts_gateRecipe() {
            assertThat(fixture.submit(submit("{\"gate\":{}}")).status()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void submit_refusesUnsupported_bundleSource() {
            assertRefused(fixture, submit("{\"bundle\":{\"blobId\":\"b\",\"sha\":\"x\"}}", mvn("verify"), ""), "unsupported");
        }

        @Test
        void submit_recordsResolvedSha_refusalAfterResolution() {
            fixture.submit(submit(mvn("-pl", "core", "verify", "-Dmaven.repo.local=/x")));

            assertThat(fixture.jobs.refused.getFirst().source().map(source -> source.sha().value()).or("")).isEqualTo(ApiFixture.SHA);
        }
    }

    @Nested
    class Accepted {
        private final ApiFixture fixture = new ApiFixture(List.of());

        @Test
        void submit_acceptsMvnJob_resolvesRefAndKeysDedup() {
            var response = fixture.submit(submit(mvn("-pl", "core", "verify")));

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.bodyText()).isEqualTo("{\"job\":101,\"state\":\"QUEUED\",\"deduplicated\":false}");
            var job = fixture.jobs.submitted.getFirst();
            assertThat(job.source().unwrap().sha().value()).isEqualTo(ApiFixture.SHA);
            assertThat(job.dedupKey().isPresent()).isTrue();
            assertThat(job.units()).hasSize(1);
            assertThat(job.units().getFirst().steps().getFirst()).startsWith("mvn", "-B", "-pl", "core", "verify");
        }

        @Test
        void submit_acceptsShaSource_containedInHead() {
            fixture.containedShas.add("c".repeat(40));

            assertThat(fixture.submit(submit("{\"pushed\":{\"sha\":\"" + "c".repeat(40) + "\"}}", mvn("verify"), "")).status())
                .isEqualTo(HttpStatus.OK);
        }

        @Test
        void submit_answersDeduplicated_withOldState() {
            fixture.jobs.nextSubmit = new SubmitOutcome.Deduplicated(JobId.jobId(5).unwrap(), JobState.PASSED);

            assertThat(fixture.submit(submit(mvn("verify"))).bodyText()).isEqualTo("{\"job\":5,\"state\":\"PASSED\",\"deduplicated\":true}");
        }

        /// R-13, amended: the reused job's state is the one its view shows, so a job whose unit was granted past the
        /// start bound with no recorded start answers UNKNOWN, never the QUEUED the store still has.
        @Test
        void submit_deduplicatedOntoAJobPastTheStartBound_answersUnknown() {
            var job = JobId.jobId(5).unwrap();
            fixture.jobs.rows.put(job, Rows.job(5, JobState.QUEUED, List.of(Rows.granted(Instant.now()
                                                                                                .minus(LinkTiming.DEFAULT.startBound())
                                                                                                .minusSeconds(1),
                                                                                         none()))));
            fixture.jobs.nextSubmit = new SubmitOutcome.Deduplicated(job, JobState.QUEUED);

            assertThat(fixture.submit(submit(mvn("verify"))).bodyText()).isEqualTo("{\"job\":5,\"state\":\"UNKNOWN\",\"deduplicated\":true}");
        }

        @Test
        void submit_cancelsSupersededUnits() {
            var superseded = UnitRef.unitRef(JobId.jobId(3).unwrap(), UnitName.MAIN);
            fixture.jobs.nextSubmit = new SubmitOutcome.Accepted(JobId.jobId(9).unwrap(), List.of(superseded));

            fixture.submit(submit(mvn("verify")));

            assertThat(fixture.cancelled).containsExactly(List.of(superseded));
        }

        @Test
        void submit_sameDedupKey_priorityAndOwnerIgnored() {
            fixture.submit(submit(mvn("verify")));
            fixture.submit("{\"submittedBy\":\"other\",\"priority\":10,\"source\":{\"pushed\":{\"ref\":\"main\"}},\"recipe\":" + mvn("verify") + "}");

            assertThat(fixture.jobs.submitted.get(0).dedupKey()).isEqualTo(fixture.jobs.submitted.get(1).dedupKey());
        }

        @Test
        void submit_timeoutOverride_changesDedupKey() {
            fixture.submit(submit(mvn("verify")));
            fixture.submit(submit("{\"pushed\":{\"ref\":\"main\"}}", mvn("verify"), ",\"timeout\":\"PT90M\""));

            assertThat(fixture.jobs.submitted.get(0).dedupKey()).isNotEqualTo(fixture.jobs.submitted.get(1).dedupKey());
            assertThat(fixture.jobs.submitted.get(1).units().getFirst().timeout()).isEqualTo(java.time.Duration.ofMinutes(90));
        }
    }

    @Nested
    class Failures {
        private final ApiFixture fixture = new ApiFixture(List.of());

        @Test
        void submit_answers503_gitDown_nothingPersisted() {
            fixture.gitDown = true;

            var response = fixture.submit(submit(mvn("verify")));

            assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(fixture.jobs.refused).isEmpty();
            assertThat(fixture.jobs.submitted).isEmpty();
        }

        @Test
        void submit_answers400_malformedJson() {
            assertThat(fixture.submit("{not json").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void submit_answers400_unknownField() {
            assertThat(fixture.submit(submit("{\"pushed\":{\"ref\":\"main\"}}", mvn("verify"), ",\"bogus\":1")).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void submit_answers400_badOwner() {
            var response = fixture.submit("{\"submittedBy\":\"bad owner!\",\"source\":{\"pushed\":{\"ref\":\"main\"}},\"recipe\":" + mvn("verify") + "}");

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.bodyText()).contains("submittedBy");
        }

        @Test
        void submit_answers400_shortSha() {
            assertThat(fixture.submit(submit("{\"pushed\":{\"sha\":\"0cf5631\"}}", mvn("verify"), "")).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    /// M1 (m1-design §1.1, §1.6): the Gate is accepted, and the reactor read from the tree reaches the planner, whose
    /// port-group rule needs it: a reactor without `aether/ember` builds no port-group module.
    /// Superseded units are cancelled as a step of the submit, not by a detached callback: with a store that answers on
    /// another thread, their `CancelUnit` requests were handed over by the time the response is.
    @Test
    void submit_cancelsSupersededUnitsBeforeAnswering_storeAnsweringLater() {
        var fixture = new ApiFixture(List.of());
        var superseded = UnitRef.unitRef(JobId.jobId(3).unwrap(), UnitName.MAIN);
        fixture.jobs.nextSubmit = new SubmitOutcome.Accepted(JobId.jobId(9).unwrap(), List.of(superseded));
        fixture.jobs.delayMs = 1;

        for (var call = 1; call <= 200; call++) {
            assertThat(fixture.submit(submit(mvn("verify"))).status()).isEqualTo(HttpStatus.OK);
            assertThat(fixture.cancelled).as("call " + call).hasSize(call);
        }
    }

    @Nested
    class Gate {
        private final ApiFixture fixture = new ApiFixture(List.of("aether/ember"));
        private static final String GATE = "{\"gate\":{}}";

        private UnitSpec submittedGate() {
            var response = fixture.submit(submit(GATE));

            assertThat(response.status()).as(response.bodyText()).isEqualTo(HttpStatus.OK);
            return fixture.jobs.submitted.getFirst().units().getFirst();
        }

        @Test
        void submit_acceptsGate_withItsManifestAndGateMemory() {
            fixture.reactor = some(ReactorModules.reactorModules(Set.of("", "core", "aether/ember")));

            var gate = submittedGate();

            assertThat(gate.name().value()).isEqualTo("gate");
            assertThat(gate.memory().value()).isEqualTo(16384);
            assertThat(gate.manifest().isPresent()).isTrue();
            assertThat(gate.needsPortGroup()).isTrue();
        }

        @Test
        void submit_gate_needsNoPortGroup_whenTheReactorHasNoPortGroupModule() {
            fixture.reactor = some(ReactorModules.reactorModules(Set.of("", "core")));

            assertThat(submittedGate().needsPortGroup()).isFalse();
        }

        @Test
        void submit_gate_needsThePortGroup_whenTheReactorIsUnknown() {
            assertThat(submittedGate().needsPortGroup()).as("unknown reactor: conservative").isTrue();
        }

        @Test
        void submit_answers503_whenTheTreeCannotBeRead_andPersistsNothing() {
            fixture.treeDown = true;

            assertThat(fixture.submit(submit(GATE)).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(fixture.jobs.submitted).isEmpty();
            assertThat(fixture.jobs.refused).isEmpty();
        }
    }
}
