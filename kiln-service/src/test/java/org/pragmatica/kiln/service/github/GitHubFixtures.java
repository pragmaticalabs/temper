package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.some;

/// GitHub answers as JSON text, and a poller wired to a [GitHubFake] and a store.
final class GitHubFixtures {
    static final String REPO = "pragmaticalabs/pragmatica";
    static final String ROOT = "/repos/" + REPO + "/";
    static final String PULLS = ROOT + GitHubPoller.PULLS;
    static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    static final Instant RESET = T0.plusSeconds(1800);
    static final BranchName MAIN = BranchName.branchName("main").unwrap();
    static final long APP = 15368;
    static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();
    static final String NO_RUNS = "{\"total_count\":0,\"workflow_runs\":[]}";

    private GitHubFixtures() {}

    static <T> T await(Promise<T> promise) {
        return promise.await(WAIT).unwrap();
    }

    /// A SHA spelling `n` in hex.
    static Sha sha(long n) {
        return Sha.sha(String.format("%040x", n)).unwrap();
    }

    static String pr(long number, String state, String base, String headRef, Sha head, Instant updatedAt) {
        var merged = state.equals("merged");
        var closed = !state.equals("open");

        return "{\"number\":" + number + ",\"title\":\"pr " + number + "\",\"state\":\"" + (closed ? "closed" : "open")
               + "\",\"draft\":false,\"base\":{\"ref\":\"" + base + "\"},\"head\":{\"ref\":\"" + headRef + "\",\"sha\":\""
               + head.value() + "\",\"repo\":{\"full_name\":\"" + REPO + "\"}},\"merged_at\":"
               + (merged ? quoted(updatedAt) : "null") + ",\"closed_at\":" + (closed ? quoted(updatedAt) : "null")
               + ",\"updated_at\":" + quoted(updatedAt) + ",\"labels\":[],\"body\":null,\"user\":{\"login\":\"x\"}}";
    }

    static String open(long number, String headRef, Sha head, Instant updatedAt) {
        return pr(number, "open", "main", headRef, head, updatedAt);
    }

    static String array(List<String> items) {
        return items.stream().collect(Collectors.joining(",", "[", "]"));
    }

    static String array(String... items) {
        return array(List.of(items));
    }

    static String quoted(Instant at) {
        return "\"" + at + "\"";
    }

    static String requiring(String context) {
        return "{\"name\":\"main\",\"protection\":{\"enabled\":true,\"required_status_checks\":{\"enforcement_level\":"
               + "\"non_admins\",\"checks\":[{\"context\":\"" + context + "\",\"app_id\":" + APP + "}]}}}";
    }

    /// `main` requiring `context` from any app, or a commit status (no app id).
    static String requiringUnpinned(String context) {
        return "{\"name\":\"main\",\"protection\":{\"enabled\":true,\"required_status_checks\":{\"enforcement_level\":"
               + "\"non_admins\",\"checks\":[{\"context\":\"" + context + "\",\"app_id\":null}]}}}";
    }

    static String status(long id, String context, String state) {
        return "{\"id\":" + id + ",\"context\":\"" + context + "\",\"state\":\"" + state
               + "\",\"description\":null,\"updated_at\":" + quoted(T0) + "}";
    }

    static String issue(long number, String title) {
        return "{\"number\":" + number + ",\"title\":\"" + title + "\",\"html_url\":\"https://x/i" + number
               + "\",\"created_at\":" + quoted(T0) + "}";
    }

    static String unprotected() {
        return "{\"name\":\"x\",\"protection\":{\"enabled\":false}}";
    }

    static String checkRuns(String... runs) {
        return "{\"total_count\":" + runs.length + ",\"check_runs\":" + array(runs) + "}";
    }

    static String run(long id, String name, String status, String conclusion, Instant started) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"app\":{\"id\":" + APP + "},\"status\":\"" + status
               + "\",\"conclusion\":" + (conclusion == null ? "null" : "\"" + conclusion + "\"") + ",\"started_at\":"
               + quoted(started) + "}";
    }

    static String workflowRuns(long id, String name) {
        return "{\"total_count\":1,\"workflow_runs\":[{\"id\":" + id + ",\"name\":\"" + name + "\",\"status\":\"completed\","
               + "\"conclusion\":\"success\",\"head_sha\":\"abc\",\"html_url\":\"https://x/" + id + "\",\"created_at\":"
               + quoted(T0) + "}]}";
    }

    static String commit(Instant at) {
        return "{\"sha\":\"x\",\"commit\":{\"committer\":{\"date\":" + quoted(at) + "}}}";
    }

    static String pull(long number) {
        return "{\"number\":" + number + ",\"mergeable\":true,\"mergeable_state\":\"clean\"}";
    }

    /// Every read a cycle makes for these open heads on base `main`: the PR detail, the head's date, `main`'s list
    /// (`ci` pinned to the app), no rulesets, the check runs (`ci` succeeded) and no statuses, the base and scheduled
    /// runs and no issues. The pulls pages are the caller's.
    static void quiet(GitHubFake fake, List<Long> numbers, List<Sha> heads) {
        Stream.iterate(0, index -> index + 1)
              .limit(numbers.size())
              .forEach(index -> quietHead(fake, numbers.get(index), heads.get(index)));
        fake.answer(ROOT + "branches/main", requiring("ci"));
        fake.answer(ROOT + "rules/branches/main", "[]");
        fake.answer(ROOT + "actions/runs?branch=main&per_page=20", workflowRuns(1, "CI"));
        fake.answer(ROOT + "actions/runs?event=schedule&per_page=20", workflowRuns(2, "nightly"));
        fake.answer(ROOT + "issues?state=open&creator=app/github-actions", "[]");
    }

    static void quietHead(GitHubFake fake, long number, Sha head) {
        fake.answer(ROOT + "pulls/" + number, pull(number));
        fake.answer(ROOT + "commits/" + head.value(), commit(T0));
        fake.answer(ROOT + "commits/" + head.value() + "/check-runs?filter=all&per_page=100",
                    checkRuns(run(number * 10, "ci", "completed", "success", T0)));
        fake.answer(ROOT + "commits/" + head.value() + "/statuses?per_page=100", "[]");
    }

    /// A check run of `ci`-like `name` in check suite `suite`.
    static String suiteRun(long id, String name, String status, String conclusion, Instant started, long suite) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"app\":{\"id\":" + APP + "},\"status\":\"" + status
               + "\",\"conclusion\":" + (conclusion == null ? "null" : "\"" + conclusion + "\"") + ",\"started_at\":"
               + quoted(started) + ",\"check_suite\":{\"id\":" + suite + "}}";
    }

    /// One workflow run with every field the change feed reads; a `pull_request` run lists PR 2 (G4), any other none.
    static String workflowRun(long id,
                              String name,
                              String event,
                              String branch,
                              String status,
                              String conclusion,
                              long attempt,
                              long suite,
                              Instant created,
                              Instant updated) {
        return workflowRun(id, name, event, branch, status, conclusion, attempt, suite, created, updated, "pull_request".equals(event)
                                                                                                           ? List.of(2L)
                                                                                                           : List.of());
    }

    /// As above, listing PRs `prs` in `pull_requests[]` (a fork PR's run lists none).
    static String workflowRun(long id,
                              String name,
                              String event,
                              String branch,
                              String status,
                              String conclusion,
                              long attempt,
                              long suite,
                              Instant created,
                              Instant updated,
                              List<Long> prs) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"status\":\"" + status + "\",\"conclusion\":"
               + (conclusion == null ? "null" : "\"" + conclusion + "\"") + ",\"head_sha\":\"" + sha(id).value()
               + "\",\"html_url\":\"https://x/" + id + "\",\"created_at\":" + quoted(created) + ",\"event\":\""
               + event + "\",\"head_branch\":\"" + branch + "\",\"run_attempt\":" + attempt + ",\"check_suite_id\":"
               + suite + ",\"updated_at\":" + quoted(updated) + ",\"pull_requests\":"
               + array(prs.stream().map(number -> "{\"number\":" + number + "}").toList()) + "}";
    }

    static String runsPage(List<String> runs) {
        return "{\"total_count\":" + runs.size() + ",\"workflow_runs\":" + array(runs) + "}";
    }

    static String botIssue(long number, String state, Instant created, Instant updated) {
        return "{\"number\":" + number + ",\"title\":\"heavy forge " + number + "\",\"html_url\":\"https://x/i" + number
               + "\",\"created_at\":" + quoted(created) + ",\"state\":\"" + state + "\",\"updated_at\":"
               + quoted(updated) + "}";
    }

    /// Empty answers for every read the change feed adds, for `root` (exact answers override them).
    static void feedQuiet(GitHubFake fake, String root) {
        fake.answerPrefix(root + "actions/runs?head_sha=", NO_RUNS);
        fake.answerPrefix(root + "actions/runs?branch=", NO_RUNS);
        fake.answerPrefix(root + "actions/runs?event=schedule&created=", NO_RUNS);
        fake.answerPrefix(root + "issues?state=all", "[]");
        fake.answerPrefix(root + "actions/runs?status=", NO_RUNS);
        fake.answerPrefix(root + "pulls?state=open", "[]");
    }

    /// A poller with the change feed: `heads` lists the branches, `others` the `[[repos]]`.
    static GitHubPoller feedPoller(GitHubFake fake,
                                   KilnStore store,
                                   AtomicReference<Instant> now,
                                   BranchHeads heads,
                                   List<KilnConfig.Repo> others) {
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), fake.api(), some("t"), Duration.ofSeconds(10));
        var reads = GitHubReads.gitHubReads(http, store.etags(), RateBudget.rateBudget(1_000_000, 10), now::get);

        return GitHubPoller.gitHubPoller(reads,
                                         store,
                                         REPO,
                                         List.of(MAIN),
                                         now::get,
                                         GitHubPoller.DEADLINE,
                                         some(GitHubFeed.gitHubFeed(reads,
                                                                    store.feed(),
                                                                    heads,
                                                                    Stream.concat(Stream.of(KilnConfig.Repo.repo(REPO, List.of(MAIN))),
                                                                                  others.stream())
                                                                          .toList())))
                           .unwrap();
    }

    /// A poller with the default deadline (10 min).
    static GitHubPoller poller(GitHubFake fake, KilnStore store, AtomicReference<Instant> now, Option<String> token) {
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), fake.api(), token, Duration.ofSeconds(10));
        var reads = GitHubReads.gitHubReads(http, store.etags(), RateBudget.rateBudget(1_000_000, 10), now::get);

        return GitHubPoller.gitHubPoller(reads, store, REPO, List.of(MAIN), now::get).unwrap();
    }

    static GitHubPoller poller(GitHubFake fake,
                               KilnStore store,
                               AtomicReference<Instant> now,
                               Option<String> token,
                               List<BranchName> bases) {
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), fake.api(), token, Duration.ofSeconds(10));
        var reads = GitHubReads.gitHubReads(http, store.etags(), RateBudget.rateBudget(1_000_000, 10), now::get);

        return GitHubPoller.gitHubPoller(reads, store, REPO, bases, now::get).unwrap();
    }

    static GitHubPoller poller(GitHubFake fake,
                               KilnStore store,
                               AtomicReference<Instant> now,
                               Option<String> token,
                               Duration deadline) {
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), fake.api(), token, Duration.ofSeconds(10));
        var reads = GitHubReads.gitHubReads(http, store.etags(), RateBudget.rateBudget(1_000_000, 10), now::get);

        return GitHubPoller.gitHubPoller(reads, store, REPO, List.of(MAIN), now::get, deadline).unwrap();
    }
}
