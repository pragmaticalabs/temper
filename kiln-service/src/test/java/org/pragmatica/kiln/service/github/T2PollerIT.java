package org.pragmatica.kiln.service.github;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.http.ApiRequest;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.MAIN;
import static org.pragmatica.kiln.service.github.GitHubFixtures.PULLS;
import static org.pragmatica.kiln.service.github.GitHubFixtures.REPO;
import static org.pragmatica.kiln.service.github.GitHubFixtures.ROOT;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.array;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.feedPoller;
import static org.pragmatica.kiln.service.github.GitHubFixtures.feedQuiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.open;
import static org.pragmatica.kiln.service.github.GitHubFixtures.pr;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.requiring;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.lang.Option.some;

/// The poller's T2 writes against Postgres and R2-7.c.
@Tag("pg")
class T2PollerIT {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final String BRANCH = ROOT + "branches/main";
    private static final Sha TIP = sha(0x100);
    private static final Sha TREE = sha(0x200);

    private KilnStore store;
    private GitHubFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private GitHubPoller poller;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = GitHubFake.start();
        quiet(fake, List.of(2L), List.of(sha(2)));
        feedQuiet(fake, ROOT);
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        fake.pages(ROOT + "pulls?state=open&per_page=100", List.of(array(open(2, "feat", sha(2), T0))));
        fake.answer(BRANCH, branchAt(TIP, TREE));
        poller = feedPoller(fake, store, now, BranchHeads.NONE, List.of());
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    static String branchAt(Sha tip, Sha tree) {
        return requiring("ci").replace("{\"name\":\"main\",",
                                       "{\"name\":\"main\",\"commit\":{\"sha\":\"" + tip.value() + "\",\"commit\":{\"tree\":{\"sha\":\""
                                       + tree.value() + "\"}}},");
    }

    static String commit(Sha sha, Sha tree, Sha parent) {
        return "{\"sha\":\"" + sha.value() + "\",\"commit\":{\"tree\":{\"sha\":\"" + tree.value() + "\"}},\"parents\":[{\"sha\":\""
               + parent.value() + "\"}]}";
    }

    private void compare(Sha from, Sha to, String status, String... commits) {
        fake.answer(ROOT + "compare/" + from.value() + "..." + to.value(), "{\"status\":\"" + status + "\",\"commits\":" + array(commits) + "}");
    }

    private void cycle() {
        now.set(now.get().plusSeconds(60));
        await(poller.cycle());
    }

    private List<ChangeFeed.Row> rows(String kind) {
        return await(store.changes().feed(0, 500)).changes()
                                                  .stream()
                                                  .filter(row -> row.kind().equals(kind))
                                                  .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(ChangeFeed.Row row) {
        return MAPPER.readString(row.payload().unwrap(), Map.class).unwrap();
    }

    private Sha storedTip() {
        return await(store.feed().baseTip(REPO, MAIN)).unwrap().sha();
    }

    // BASE_MOVED
    @Test
    void baseTip_firstReadStoresIt_andTheSameTipAgainRecordsNothing() {
        cycle();
        cycle();

        assertThat(rows("BASE_MOVED")).isEmpty();
        assertThat(storedTip()).isEqualTo(TIP);
        assertThat(fake.requests()).noneMatch(request -> request.path().contains("/compare/"));
    }

    @Test
    void baseMoved_treeChangingCommit_treeChanged_firstChanged() {
        cycle();
        var next = sha(0x102);
        compare(TIP, next, "ahead", commit(sha(0x101), TREE, TIP), commit(next, sha(0x202), sha(0x101)));
        fake.answer(BRANCH, branchAt(next, sha(0x202)));
        cycle();

        var moved = rows("BASE_MOVED");

        assertThat(moved).singleElement().satisfies(row -> {
            assertThat(row.repo()).isEqualTo(REPO);
            assertThat(row.pr().isEmpty()).isTrue();
            assertThat(row.subject()).isEqualTo("main");
            assertThat(row.before()).isEqualTo(TIP.value());
            assertThat(row.after()).isEqualTo(next.value());
            assertThat(payload(row)).isEqualTo(Map.of("base", "main", "treeChanged", true, "rewound", false, "commits", 2,
                                                      "firstChanged", next.value(), "complete", true));
        });
        assertThat(storedTip()).isEqualTo(next);
    }

    /// A `compare` URL (two shas) is asked once, so it is read unconditionally: its body is never kept in `gh_etag`.
    @Test
    void baseMoved_compareIsUnconditional_noEtagRowKept() {
        cycle();
        var next = sha(0x102);
        compare(TIP, next, "ahead", commit(next, sha(0x202), TIP));
        fake.answer(BRANCH, branchAt(next, sha(0x202)));
        cycle();

        var compares = fake.requests().stream().filter(request -> request.path().contains("/compare/")).toList();

        assertThat(rows("BASE_MOVED")).hasSize(1);
        assertThat(compares).singleElement().satisfies(request -> assertThat(request.ifNoneMatch()).isNull());
        assertThat(await(store.etags().cached(compares.getFirst().path())).isEmpty()).isTrue();
    }

    @Test
    void baseMoved_onlyEmptyCommits_treeUnchanged() {
        cycle();
        var next = sha(0x101);
        compare(TIP, next, "ahead", commit(next, TREE, TIP));
        fake.answer(BRANCH, branchAt(next, TREE));
        cycle();

        assertThat(rows("BASE_MOVED")).singleElement()
                                      .satisfies(row -> assertThat(payload(row)).isEqualTo(Map.of("base", "main", "treeChanged", false, "rewound", false,
                                                                                                  "commits", 1, "complete", true)));
    }

    @Test
    void baseMoved_rewoundOrDiverged_rewound() {
        cycle();
        var next = sha(0x99);
        compare(TIP, next, "diverged");
        fake.answer(BRANCH, branchAt(next, TREE));
        cycle();

        assertThat(payload(rows("BASE_MOVED").getFirst())).containsEntry("rewound", true).containsEntry("treeChanged", true).containsEntry("complete", true);
    }

    @Test
    void baseMoved_walkPastTheListing_over250_failSafe() {
        cycle();
        var next = sha(0x400);
        var listed = IntStream.rangeClosed(0x400 - 249, 0x400).mapToObj(n -> commit(sha(n), TREE, sha(n - 1))).toArray(String[]::new);
        compare(TIP, next, "ahead", listed);
        fake.answer(BRANCH, branchAt(next, TREE));
        cycle();

        assertThat(payload(rows("BASE_MOVED").getFirst())).containsEntry("complete", false)
                                                          .containsEntry("treeChanged", true)
                                                          .containsEntry("commits", 250)
                                                          .doesNotContainKey("firstChanged");
    }

    @Test
    void baseMoved_failedCompare_failSafe_andTheNewTipIsStored() {
        cycle();
        var next = sha(0x101);
        fake.answer(BRANCH, branchAt(next, TREE));
        cycle();
        cycle();

        assertThat(rows("BASE_MOVED")).singleElement()
                                      .satisfies(row -> assertThat(payload(row)).containsEntry("complete", false).containsEntry("treeChanged", true));
        assertThat(storedTip()).isEqualTo(next);
    }

    // mergedBy
    private void mergedNext(String pullAnswer, int status) {
        var later = T0.plusSeconds(120);

        fake.pages(PULLS, List.of(array(pr(2, "merged", "main", "feat", sha(2), later))));
        fake.answer(ROOT + "pulls/2", status, pullAnswer);
    }

    @Test
    void mergedBy_storedOpenListedMerged_readsPullsN_andAddsTheLogin() {
        cycle();
        mergedNext("{\"number\":2,\"merged_by\":{\"login\":\"cto-bot\"}}", 200);
        cycle();

        assertThat(rows("PR_STATE")).filteredOn(row -> row.after().equals("merged"))
                                    .singleElement()
                                    .satisfies(row -> assertThat(payload(row)).containsEntry("mergedBy", "cto-bot").containsEntry("head", sha(2).value()));
    }

    @Test
    void mergedBy_failedRead_omitsTheKey_mergeStillRecorded() {
        cycle();
        mergedNext("{}", 500);
        cycle();

        assertThat(rows("PR_STATE")).filteredOn(row -> row.after().equals("merged"))
                                    .singleElement()
                                    .satisfies(row -> assertThat(payload(row)).doesNotContainKey("mergedBy").containsKey("mergedAt"));
    }

    // verifier t2wpbv
    @Test
    void vx9_mergedBy_readOnlyOnceForTheStoredOpenPr_neverForAStoredMergedOne() {
        cycle();
        var open = pulls2();
        mergedNext("{\"number\":2,\"merged_by\":{\"login\":\"cto-bot\"}}", 200);
        cycle();
        var merging = pulls2();
        cycle();

        assertThat(List.of(merging - open, pulls2() - merging)).containsExactly(1L, 0L);
    }

    private long pulls2() {
        return fake.requests().stream().filter(request -> request.path().equals(ROOT + "pulls/2")).count();
    }

    // R2-7.c
    @Test
    void concurrentPollAndPrFresh_changeIdsInCommitOrder_prFreshWritesNone() {
        cycle();
        var before = await(store.changes().feed(0, 500));
        var fresh = PrFresh.prFresh(poller.reads().http(), poller.reads().budget(), store.feed()::baseTip,
                                    List.of(KilnConfig.Repo.repo(REPO, List.of(MAIN))), now::get);
        var next = sha(0x101);
        compare(TIP, next, "ahead", commit(next, sha(0x300), TIP));
        fake.answer(BRANCH, branchAt(next, sha(0x300)));
        mergedNext("{\"number\":2,\"merged_by\":{\"login\":\"x\"}}", 200);
        fake.answer(ROOT + "pulls/2/files?per_page=100", "[]");
        now.set(now.get().plusSeconds(60));

        var asks = IntStream.range(0, 6)
                            .mapToObj(_ -> fresh.answer(ApiRequest.apiRequest("GET", PrFresh.PATH, Map.of("repo", REPO, "pr", "2", "sha", sha(2).value()), "")))
                            .toList();
        var cycle = poller.cycle();

        await(Promise.allOf(asks));
        await(cycle);

        var after = await(store.changes().feed(0, 500)).changes();
        var ids = after.stream().map(ChangeFeed.Row::id).toList();

        assertThat(after.size()).isGreaterThan(before.changes().size());
        assertThat(ids).isSorted().doesNotHaveDuplicates();
        assertThat(after).isSortedAccordingTo(Comparator.comparing(ChangeFeed.Row::recordedAt));
        assertThat(ids.getLast()).isEqualTo(ids.getFirst() + ids.size() - 1);
        assertThat(after.stream().map(ChangeFeed.Row::kind)).contains("BASE_MOVED", "PR_STATE");
    }
}
