package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.pragmatica.kiln.service.github.GitHubFixtures.ROOT;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The drift reads and the budget view against a [GitHubFake] (controls K.5, K.6):
/// the ruleset hash moves on a change that governs merging (a `bypass_actors` entry) and only then, the reads cost 72
/// calls an hour, and the `/rate_limit` sample is taken only when no reply has stated the rate for five minutes.
@Tag("pg")
class GitHubDriftIT {
    private static final String RULES = ROOT + "rules/branches/main";
    private static final String RULESET = ROOT + "rulesets/7";
    private static final String TEAM = "/orgs/pragmaticalabs/teams/temper-reviewers/members?per_page=100";
    private static final String ORG_RULESET = "/orgs/pragmaticalabs/rulesets/9";

    private static final String EFFECTIVE = "[{\"type\":\"pull_request\",\"ruleset_source_type\":\"Repository\","
                                          + "\"ruleset_id\":7,\"parameters\":{\"a\":1,\"b\":[1,2]}}]";

    private static final String EFFECTIVE_REORDERED = "[{\"parameters\":{\"b\":[1,2],\"a\":1},\"ruleset_id\":7,"
                                                    + "\"ruleset_source_type\":\"Repository\",\"type\":\"pull_request\"}]";

    private KilnStore store;
    private GitHubFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private GitHubPoller poller;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = GitHubFake.start();
        quiet(fake, List.of(2L), List.of(sha(2)));
        fake.pages(GitHubFixtures.PULLS, List.of("[]"));
        fake.answer(RULES, EFFECTIVE);
        fake.answer(RULESET, ruleset("[]"));
        fake.answer(TEAM, "[{\"login\":\"reviewer-pragmatica\"}]");
        poller = GitHubFixtures.poller(fake, store, now, some("t0ken-secret"));
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    /// K.5: a `bypass_actors` change is one hash change; identical cycles and a reply with only its keys re-ordered
    /// (the effective rules and the ruleset both) are none.
    @Test
    void k5_theHash_movesOnABypassActorChange_andOnlyThen() {
        var baseline = cycleHash();

        assertThat(baseline).isNotEqualTo("none").hasSize(64);
        IntStream.range(0, 20).forEach(_ -> assertThat(cycleHash()).isEqualTo(baseline));
        fake.answer(RULES, EFFECTIVE_REORDERED);
        fake.answer(RULESET, reordered("[]"));
        assertThat(cycleHash()).as("keys re-ordered").isEqualTo(baseline);
        fake.answer(RULESET, ruleset("[{\"actor_id\":5,\"actor_type\":\"Team\",\"bypass_mode\":\"always\"}]"));
        var changed = cycleHash();

        assertThat(changed).as("a bypass actor added").isNotEqualTo(baseline);
        IntStream.range(0, 5).forEach(_ -> assertThat(cycleHash()).isEqualTo(changed));
        assertThat(poller.budget().drift().rulesetIds()).containsExactly(7L);
    }

    /// K.6: over one hour of 60 s cycles, 60 ruleset reads and 12 team reads.
    @Test
    void k6_anHourOfCycles_readsTheRulesetEveryCycle_andTheTeamEveryFifth() {
        IntStream.range(0, 60).forEach(_ -> cycleHash());
        var paths = fake.pathsSince(0);

        assertThat(paths.stream().filter(RULESET::equals).count()).isEqualTo(60);
        assertThat(paths.stream().filter(TEAM::equals).count()).isEqualTo(12);
        assertThat(poller.budget().drift().teamMembers()).isEqualTo(some(List.of("reviewer-pragmatica")));
    }

    /// FER (temper issue: a failed drift read was silent): one failed ruleset read keeps the last hash but is logged with its
    /// reason and reported not fresh with the last good read time; the source is still `Ok`. A second consecutive failure
    /// turns the GitHub source `Incomplete`, naming the failure and the last good time; a good read clears both.
    @Test
    void aFailedDriftRead_isLoggedAndNotFresh_staleAfterTwoCycles_andClearedByASuccess() {
        var baseline = cycleHash();
        var good = poller.budget().drift().observedAt().unwrap();
        var logs = captured();

        assertThat(poller.budget().drift().fresh()).isTrue();
        fake.answer(RULESET, 500, "{}");
        var first = await(poller.cycle());
        var after = poller.budget().drift();

        assertThat(first).as("below the threshold the source stays Ok").isEqualTo(new SourceState.Ok(now.get()));
        assertThat(after.rulesetHash()).as("the last good hash is kept").isEqualTo(some(baseline));
        assertThat(after.fresh()).isFalse();
        assertThat(after.failedCycles()).isEqualTo(1);
        assertThat(after.failure().unwrap()).isNotBlank();
        assertThat(after.observedAt()).as("the time of the last good read").isEqualTo(some(good));
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("ruleset drift read incomplete")
                                                            .contains(after.failure().unwrap()));
        now.set(now.get().plusSeconds(60));
        var second = await(poller.cycle());

        assertThat(second).isInstanceOfSatisfying(SourceState.Incomplete.class,
                                                  incomplete -> assertThat(incomplete.reason()).contains("ruleset drift not read for 2 cycles")
                                                                          .contains(good.toString())
                                                                          .contains(after.failure().unwrap()));
        assertThat(poller.budget().drift().failedCycles()).isEqualTo(2);
        fake.answer(RULESET, ruleset("[]"));
        now.set(now.get().plusSeconds(60));
        assertThat(await(poller.cycle())).as("a success clears it").isEqualTo(new SourceState.Ok(now.get()));
        assertThat(poller.budget().drift().fresh()).isTrue();
        assertThat(poller.budget().drift().failedCycles()).isZero();
        assertThat(poller.budget().drift().rulesetHash()).isEqualTo(some(baseline));
        assertThat(poller.watch().rulesets().get().unwrap().at()).as("the ruleset read is stamped again").isAfter(good);
        ((Logger) LoggerFactory.getLogger(GitHubWatch.class)).detachAppender(logs);
    }

    /// A cycle missing one configured base's `rules/branches` reply is not silently skipped: it counts as a failed drift read.
    @Test
    void aPartialCycle_isNotFresh_namingTheMissingBase() {
        var release = BranchName.branchName("release").unwrap();
        var other = ROOT + "rules/branches/release";

        fake.answer(ROOT + "branches/release", GitHubFixtures.requiring("ci"));
        fake.answer(other, "[{\"type\":\"deletion\",\"ruleset_source_type\":\"Repository\",\"ruleset_id\":8}]");
        fake.answer(ROOT + "rulesets/8", ruleset("[]"));
        fake.answer(ROOT + "actions/runs?branch=release&per_page=20", GitHubFixtures.workflowRuns(1, "CI"));
        poller = GitHubFixtures.poller(fake, store, now, some("t0ken-secret"), List.of(GitHubFixtures.MAIN, release));
        cycleHash();
        assertThat(poller.budget().drift().fresh()).isTrue();
        fake.answer(other, 500, "{}");
        cycleHash();
        var drift = poller.budget().drift();

        assertThat(drift.fresh()).isFalse();
        assertThat(drift.failure().unwrap()).contains("release");
        assertThat(drift.failedCycles()).isEqualTo(1);
    }

    /// §7.1: `GET /rate_limit` only when no reply stated the rate for five minutes; the sample says where it was read.
    @Test
    void rateLimit_isAskedOnlyAfterFiveSilentMinutes_andTheSampleNamesItsSource() {
        fake.rate(4000, 5000, T0.plusSeconds(36000), false);
        fake.answer("/rate_limit", "{\"resources\":{}}");
        cycleHash();
        assertThat(fake.pathsSince(0)).doesNotContain("/rate_limit");
        assertThat(poller.budget().sample().map(GitHubBudget.Sample::source)).isEqualTo(some(GitHubBudget.Sample.HEADER));
        now.set(now.get().plusSeconds(200));
        await(poller.watch().sampled(poller.reads(), now.get()));
        assertThat(fake.pathsSince(0)).doesNotContain("/rate_limit");
        now.set(now.get().plusSeconds(120));
        await(poller.watch().sampled(poller.reads(), now.get()));
        assertThat(fake.pathsSince(0)).filteredOn("/rate_limit"::equals).hasSize(1);
        var sample = poller.budget().sample().unwrap();

        assertThat(sample.identity()).isEqualTo("kiln-app");
        assertThat(sample.source()).isEqualTo(GitHubBudget.Sample.RATE_LIMIT);
        assertThat(sample.observedAt()).isEqualTo(now.get());
        assertThat(sample.limit()).isEqualTo(5000);
        assertThat(sample.used()).isEqualTo(sample.limit() - sample.remaining());
        assertThat(poller.budget().mode()).isEqualTo(Credential.Mode.TOKEN);
    }

    /// §7.1: the `/rate_limit` probe is an ordinary budgeted request: a stale sample inside a `Retry-After` wait sends nothing.
    @Test
    void rateLimitProbe_isNotSentDuringARetryAfterWait() {
        fake.answer(GitHubFixtures.PULLS, 403, "{\"message\":\"slow down\"}");
        fake.retryAfter(GitHubFixtures.PULLS, "3600");
        fake.rate(4000, 5000, T0.plusSeconds(36000), false);
        fake.answer("/rate_limit", "{\"resources\":{}}");
        await(poller.cycle());
        now.set(now.get().plusSeconds(400));
        await(poller.watch().sampled(poller.reads(), now.get()));
        assertThat(fake.pathsSince(0)).as("the sample is over five minutes old, and the wait holds")
                  .doesNotContain("/rate_limit");
        now.set(now.get().plusSeconds(3300));
        await(poller.watch().sampled(poller.reads(), now.get()));
        assertThat(fake.pathsSince(0)).as("the wait is over: the probe goes")
                  .filteredOn("/rate_limit"::equals)
                  .hasSize(1);
    }

    /// Review item 1: the team is read to its last page (GitHub pages at 30 unless asked), and the next cycle's need counts them.
    @Test
    void theTeam_isReadOnEveryPage() {
        fake.pages(TEAM, List.of(logins(1, 30), logins(31, 40)));
        cycleHash();
        assertThat(poller.budget().drift().teamMembers().unwrap()).hasSize(40).contains("u01", "u30", "u31", "u40");
        assertThat(fake.pathsSince(0)).filteredOn(path -> path.startsWith("/orgs/"))
                  .containsExactly(TEAM, TEAM + "&page=2");
        assertThat(poller.watch().driftNeed()).as("ruleset 7 now; the team's two pages on its turn").isEqualTo(1);
        IntStream.range(0, 4).forEach(_ -> cycleHash());
        assertThat(poller.watch().driftNeed()).as("ruleset 7, and the team's two pages").isEqualTo(3);
    }

    /// Review item 2: a base whose `rules/branches` read fails in one cycle publishes nothing: the last complete hash and its
    /// time stay, and the next good cycle publishes the same hash (no flap).
    @Test
    void aBaseThatFailsOneCycle_neitherMovesTheHash_norFlapsIt() {
        var release = BranchName.branchName("release").unwrap();
        var other = ROOT + "rules/branches/release";

        fake.answer(ROOT + "branches/release", GitHubFixtures.requiring("ci"));
        fake.answer(other, "[{\"type\":\"deletion\",\"ruleset_source_type\":\"Repository\",\"ruleset_id\":8}]");
        fake.answer(ROOT + "rulesets/8", ruleset("[]"));
        fake.answer(ROOT + "actions/runs?branch=release&per_page=20", GitHubFixtures.workflowRuns(1, "CI"));
        poller = GitHubFixtures.poller(fake, store, now, some("t0ken-secret"), List.of(GitHubFixtures.MAIN, release));
        var baseline = cycleHash();
        var published = poller.watch().rulesets().get().unwrap();

        assertThat(baseline).isNotEqualTo("none");
        assertThat(published.ids()).containsExactly(7L, 8L);
        fake.answer(other, 500, "{}");
        assertThat(cycleHash()).as("one base failed").isEqualTo(baseline);
        assertThat(poller.watch().rulesets().get().unwrap()).as("nothing published, nothing re-stamped")
                  .isEqualTo(published);
        fake.answer(other, "[{\"type\":\"deletion\",\"ruleset_source_type\":\"Repository\",\"ruleset_id\":8}]");
        assertThat(cycleHash()).as("the next good cycle").isEqualTo(baseline);
    }

    /// Review item 3: a ruleset whose source is the organization is read at the organization's path, and its bypass actors
    /// count.
    @Test
    void anOrganizationRuleset_isReadAtTheOrganizationPath() {
        fake.answer(RULES,
                    "[{\"type\":\"deletion\",\"ruleset_source_type\":\"Repository\",\"ruleset_id\":7},"
                   + "{\"type\":\"deletion\",\"ruleset_source_type\":\"Organization\",\"ruleset_id\":9}]");
        fake.answer(ORG_RULESET, ruleset("[]"));
        var baseline = cycleHash();

        assertThat(baseline).isNotEqualTo("none");
        assertThat(poller.budget().drift().rulesetIds()).containsExactly(7L, 9L);
        assertThat(fake.pathsSince(0)).contains(ORG_RULESET).doesNotContain(ROOT + "rulesets/9");
        fake.answer(ORG_RULESET, ruleset("[{\"actor_id\":5,\"actor_type\":\"Team\",\"bypass_mode\":\"always\"}]"));
        assertThat(cycleHash()).as("a bypass actor on the organization's ruleset").isNotEqualTo(baseline);
    }

    /// Live fact: the deployed App token gets no `bypass_actors` key. The hash still exists and is stable.
    @Test
    void anAbsentBypassActors_hashesStably() {
        fake.answer(RULESET,
                    "{\"id\":7,\"name\":\"r\",\"enforcement\":\"active\",\"conditions\":{\"ref_name\":{\"include\":[],"
                   + "\"exclude\":[]}},\"rules\":[{\"type\":\"deletion\"}]}");
        var baseline = cycleHash();

        assertThat(baseline).isNotEqualTo("none").hasSize(64);
        IntStream.range(0, 6).forEach(_ -> assertThat(cycleHash()).isEqualTo(baseline));
    }

    /// Review item 5B: the next cycle's need counts one request per ruleset the last cycle found.
    @Test
    void need_countsTheRulesetReads() {
        cycleHash();
        var withOne = poller.need(List.of());

        assertThat(poller.watch().driftNeed()).isEqualTo(1);
        fake.answer(RULES, "[]");
        cycleHash();
        assertThat(withOne - poller.need(List.of())).isEqualTo(1);
    }

    /// Review item 5A: a `/rate_limit` probe that fails (here: the request cannot be made) does not abort the cycle.
    @Test
    void aFailedRateLimitProbe_doesNotAbortTheCycle() {
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(),
                                               fake.api(),
                                               some("t0ken-secret"),
                                               Duration.ofSeconds(10));
        var failing = new GitHubHttp() {
            @Override
            public Promise<GhAnswer> get(String pathAndQuery, Option<String> etag) {
                return "/rate_limit".equals(pathAndQuery)
                       ? Causes.cause("probe down").promise()
                       : http.get(pathAndQuery, etag);
            }
        };
        var reads = GitHubReads.gitHubReads(failing, store.etags(), RateBudget.rateBudget(1_000_000, 10), now::get);

        poller = GitHubPoller.gitHubPoller(reads,
                                           store,
                                           GitHubFixtures.REPO,
                                           List.of(GitHubFixtures.MAIN),
                                           now::get)
                             .unwrap();
        fake.rate(4000, 5000, T0.plusSeconds(36000), false);
        cycleHash();
        now.set(now.get().plusSeconds(400));
        var from = fake.requests().size();

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(now.get()));
        assertThat(fake.pathsSince(from)).as("the cycle went on past the failed probe").contains(GitHubFixtures.PULLS);
    }

    /// Collects what [GitHubWatch] logs from now on; the caller detaches it.
    private static ListAppender<ILoggingEvent> captured() {
        var appender = new ListAppender<ILoggingEvent>();

        appender.start();
        ((Logger) LoggerFactory.getLogger(GitHubWatch.class)).addAppender(appender);

        return appender;
    }

    private static String logins(int from, int to) {
        return IntStream.rangeClosed(from, to)
                        .mapToObj(index -> "{\"login\":\"u%02d\"}".formatted(index))
                        .collect(Collectors.joining(",", "[", "]"));
    }

    private String cycleHash() {
        await(poller.cycle());
        now.set(now.get().plusSeconds(60));

        return poller.budget()
                     .drift()
                     .rulesetHash()
                     .or("none");
    }

    private static String ruleset(String bypass) {
        return "{\"id\":7,\"name\":\"r\",\"enforcement\":\"active\",\"conditions\":{\"ref_name\":{\"include\":[\"~DEFAULT_BRANCH\"],"
             + "\"exclude\":[]}},\"rules\":[{\"type\":\"deletion\"},{\"type\":\"required_linear_history\"}],\"bypass_actors\":" + bypass
             + "}";
    }

    /// [#ruleset] with every object's keys in another order.
    private static String reordered(String bypass) {
        return "{\"bypass_actors\":" + bypass
             + ",\"rules\":[{\"type\":\"deletion\"},{\"type\":\"required_linear_history\"}],"
             + "\"conditions\":{\"ref_name\":{\"exclude\":[],\"include\":[\"~DEFAULT_BRANCH\"]}},\"name\":\"r\","
             + "\"enforcement\":\"active\",\"id\":7}";
    }
}
