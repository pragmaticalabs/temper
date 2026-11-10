package org.pragmatica.kiln.service.github;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.feed.BaseFeed;
import org.pragmatica.kiln.core.feed.CheckFeed;
import org.pragmatica.kiln.core.feed.FeedChange;
import org.pragmatica.kiln.core.feed.IssueFeed;
import org.pragmatica.kiln.core.feed.ListFeed;
import org.pragmatica.kiln.core.feed.RunFeed;
import org.pragmatica.kiln.core.github.BotIssue;
import org.pragmatica.kiln.core.github.BranchRead;
import org.pragmatica.kiln.core.github.CheckRun;
import org.pragmatica.kiln.core.github.CommitStatus;
import org.pragmatica.kiln.core.github.Compare;
import org.pragmatica.kiln.core.github.GitHubError;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.github.PullsPage;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.Rulesets;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.store.BaseTip;
import org.pragmatica.kiln.service.store.Cached;
import org.pragmatica.kiln.service.store.FeedStore;
import org.pragmatica.kiln.service.store.HeadChecks;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.unitPromise;


/// The change feed's GitHub reads (CI interface T1 WP2, design §2.1), run inside the poller's single-flight cycle, so
/// every change they record has the poller's single writer. Every read degrades forward as the poller's do
/// ([GitHubPoller.Cycle#attempt]): a failed read is noted, decides the source state, and writes nothing.
///
/// Per repository (`[github] repo` first, then each `[[repos]]`), every one through the same path (lead ruling: no
/// special case for the first, whose dashboard reads stay the poller's own):
/// - **required lists** of every base read: the history version ([FeedStore#requiredList], `REQUIRED_LIST_CHANGED`);
/// - **per open PR** whose base requires checks: the head's workflow runs (`actions/runs?head_sha=`, paged), joined to
///   its check runs (`filter=all`, so a later run of the same name never hides a PR suite's run, F3) and statuses
///   ([CheckFeed]): `CHECK_CONCLUDED`, `CHECKS_SETTLED`, all read here from the open PRs (`pulls?state=open`, paged)
///   and their bases' lists;
/// - **not-completed runs** (`status=queued`, `status=in_progress`, paged) first, so they are re-read first;
/// - **push runs** of `main`, the configured bases and every `release-*` branch [BranchHeads] lists, each read
///   `created>=` [RunFeed#OVERLAP] before the newest stored run (else [RunFeed#RECENT] back), paged until exhausted
///   (F11), and the **scheduled runs** likewise: `*_RUN_CONCLUDED`, `RUN_CANCELLED`;
/// - **bot issues**, `state=all&since=` the newest stored `updated_at`, paged: `BOT_ISSUE_OPENED`, `BOT_ISSUE_CLOSED`;
/// - last, the **bounded re-read** (R2-11, R3-5): at most [#REREAD_MAX] stored runs by id, and never more than the
///   budget leaves above its reserve ([RateBudget#spare]), not-completed first, then oldest refreshed first. Each
///   re-read refreshes its run, so a backlog of N runs inside the 7-day window is read in full within
///   `ceil(N / REREAD_MAX)` cycles while the budget leaves at least [#REREAD_MAX] spare, and a cycle never spends past
///   the reserve on it. A rerun of an old run is found here whatever its `created_at`.
///
/// `tracked` holds, per repository, how many branches the last cycle read push runs of (main, bases and the `release-*`
/// branches ls-remote listed), so [#need] counts the `release-*` reads too.
public record GitHubFeed(GitHubReads reads,
                         FeedStore store,
                         BranchHeads heads,
                         List<KilnConfig.Repo> repos,
                         Map<String, Integer> tracked) {
    /// The most runs re-read by id per repository and cycle: 20 a minute stays far below GitHub's secondary limit and,
    /// with the rest of a cycle, inside the authenticated hourly budget at the 60 s period (`RateBudget#period` stretches
    /// the period when it does not).
    public static final int REREAD_MAX = 20;
    static final String MAIN = "main";
    static final String RELEASE = "release-";

    static final String ISSUES = "issues?state=all&creator=app/github-actions&sort=updated&direction=asc&per_page=100&since=";

    static final int NOT_FOUND = 404;
    /// Per open PR: its check runs, its statuses and its head's workflow runs.
    static final int PER_OPEN = 3;
    /// The statuses a run is listed under until it completes (R3-5: listed first each cycle, whatever its `created_at`).
    static final List<String> NOT_COMPLETED = List.of("queued", "in_progress");

    public GitHubFeed {
        repos = List.copyOf(repos);
    }

    public static GitHubFeed gitHubFeed(GitHubReads reads,
                                        FeedStore store,
                                        BranchHeads heads,
                                        List<KilnConfig.Repo> repos) {
        return new GitHubFeed(reads, store, heads, repos, new ConcurrentHashMap<>());
    }

    /// The requests the feed adds to a cycle with `open` open PRs in the first repository: per such PR its check runs,
    /// statuses and head runs (the other repositories' open PRs are unknown before their read); per repository its open
    /// PRs page, each base's branch and rulesets, and its base reads. A longer read is still held at the reserve, request
    /// by request.
    int need(int open) {
        return PER_OPEN * open + repos.stream()
                                      .mapToInt(this::repoNeed)
                                      .sum();
    }

    /// The open PRs page, each base's branch and rulesets, and the base reads.
    private int repoNeed(KilnConfig.Repo repo) {
        return 1 + 2 * repo.bases()
                           .size() + baseNeed(branches(repo));
    }

    /// The branches the last cycle read push runs of; before the first, `main` and the bases.
    private int branches(KilnConfig.Repo repo) {
        return option(tracked.get(repo.name())).or(() -> tracked(repo.bases(), List.of()).size());
    }

    /// The two not-completed listings, each tracked branch's push runs, the scheduled runs, the issues, and the re-read.
    private static int baseNeed(int branches) {
        return 2 + branches + 2 + REREAD_MAX;
    }

    // required lists
    /// Every list read, in the history; an unreadable one changes nothing.
    Promise<Unit> requiredList(GitHubPoller.Cycle cycle, String repo, BranchName base, RequiredChecks list) {
        return ListFeed.contexts(list)
                       .map(contexts -> recorded(repo,
                                                 base,
                                                 contexts,
                                                 cycle.at()))
                       .or(unitPromise());
    }

    /// With the ETag the branch read was answered with (stored by [GitHubReads], replayed on a 304): the list's protection
    /// half. [guess] The rulesets read's ETag is not kept; a ruleset change shows as changed contexts.
    private Promise<Unit> recorded(String repo, BranchName base, List<String> contexts, Instant at) {
        return reads.etags()
                    .cached(path(repo,
                                 "branches/" + GitHubPoller.segment(base)))
                    .map(cached -> cached.map(Cached::etag))
                    .flatMap(etag -> store.requiredList(repo, base, contexts, etag, at))
                    .mapToUnit();
    }

    // base tips
    /// Condition: only a configured base, and only a branch read that decoded with its tip and tree; a failed read was
    /// noted already and changes nothing.
    private Promise<Unit> baseTip(GitHubPoller.Cycle cycle,
                                  KilnConfig.Repo repo,
                                  BranchName base,
                                  Result<BranchRead> branch) {
        return branch.option()
                     .filter(_ -> repo.bases()
                                      .contains(base))
                     .flatMap(GitHubFeed::tipOf)
                     .map(now -> tipRead(cycle,
                                         repo.name(),
                                         base,
                                         now))
                     .or(unitPromise());
    }

    private static Option<BaseFeed.Tip> tipOf(BranchRead read) {
        return Option.all(read.tip(),
                          read.tree())
                     .map(BaseFeed.Tip::new);
    }

    private Promise<Unit> tipRead(GitHubPoller.Cycle cycle, String repo, BranchName base, BaseFeed.Tip now) {
        return store.baseTip(repo, base)
                    .flatMap(stored -> fromStored(cycle, repo, base, stored, now));
    }

    /// No stored tip: store it and emit nothing (a rebuilt kiln does not report its first read as a move).
    private Promise<Unit> fromStored(GitHubPoller.Cycle cycle,
                                     String repo,
                                     BranchName base,
                                     Option<BaseTip> stored,
                                     BaseFeed.Tip now) {
        return stored.map(old -> movedFrom(cycle, repo, base, old, now))
                     .or(() -> tipStored(repo,
                                         base,
                                         now,
                                         List.of(),
                                         cycle.at()));
    }

    /// The same tip: nothing. Another: one `compare` read, then the move and the new tip in one transaction.
    private Promise<Unit> movedFrom(GitHubPoller.Cycle cycle,
                                    String repo,
                                    BranchName base,
                                    BaseTip old,
                                    BaseFeed.Tip now) {
        return old.sha()
                  .equals(now.sha())
               ? unitPromise()
               : compared(cycle, repo, old, now).flatMap(moved -> tipStored(repo,
                                                                            base,
                                                                            now,
                                                                            List.of(BaseFeed.change(base,
                                                                                                    old.sha(),
                                                                                                    now.sha(),
                                                                                                    moved,
                                                                                                    cycle.at())),
                                                                            cycle.at()));
    }

    /// FER: a failed compare is noted and degrades forward to the fail-safe move (`complete=false, treeChanged=true`),
    /// so a GO resting on the old tip is still invalidated; the new tip is stored and the compare is not asked again.
    private Promise<BaseFeed.Moved> compared(GitHubPoller.Cycle cycle, String repo, BaseTip old, BaseFeed.Tip now) {
        return reads.parsedOnce(path(repo,
                                     "compare/" + old.sha().value() + "..." + now.sha().value()),
                                Compare::decode)
                    .fold(read -> Promise.success(BaseFeed.moved(old.tip(),
                                                                 now.sha(),
                                                                 cycle.noted(read))));
    }

    private Promise<Unit> tipStored(String repo,
                                    BranchName base,
                                    BaseFeed.Tip now,
                                    List<FeedChange> moved,
                                    Instant at) {
        return store.baseTip(repo,
                             new BaseTip(base,
                                         now.sha(),
                                         now.tree(),
                                         at),
                             moved)
                    .mapToUnit();
    }

    // checks at one head
    /// Condition: only a [RequiredChecks.Required] list, and only check reads that succeeded (a failed one was noted by
    /// the poller), are judged; anything else records nothing for this head this cycle.
    Promise<Unit> checks(GitHubPoller.Cycle cycle,
                         String repo,
                         PullRequest pr,
                         RequiredChecks list,
                         Result<List<CheckRun>> runs,
                         Result<List<CommitStatus>> statuses) {
        return switch (list) {
            case RequiredChecks.Required required -> Result.all(runs, statuses).map((checkRuns, commitStatuses) -> judged(cycle,
                                                                                                                          repo,
                                                                                                                          pr,
                                                                                                                          required,
                                                                                                                          checkRuns,
                                                                                                                          commitStatuses)).or(unitPromise());
            default -> unitPromise();
        };
    }

    private Promise<Unit> judged(GitHubPoller.Cycle cycle,
                                 String repo,
                                 PullRequest pr,
                                 RequiredChecks.Required list,
                                 List<CheckRun> runs,
                                 List<CommitStatus> statuses) {
        return cycle.attempt(reads.pages(path(repo,
                                              "actions/runs?head_sha=" + pr.head().value() + "&per_page=100"),
                                         WorkflowRun::decode,
                                         _ -> true))
                    .map(read -> read.map(GitHubPoller::joined))
                    .flatMap(read -> GitHubPoller.whenRead(read,
                                                           headRuns -> store.checks(repo,
                                                                                    new HeadChecks(pr.number(),
                                                                                                   pr.head(),
                                                                                                   pr.base(),
                                                                                                   list,
                                                                                                   CheckFeed.observed(list,
                                                                                                                      pr.number(),
                                                                                                                      runs,
                                                                                                                      statuses,
                                                                                                                      headRuns),
                                                                                                   headRuns),
                                                                                    cycle.at())
                                                                            .mapToUnit()));
    }

    // every repository, one path
    /// Each repository, `[github] repo` first, one after another and each the same way: its open PRs, its lists, its
    /// checks, then its base reads.
    Promise<Unit> repoFeeds(GitHubPoller.Cycle cycle) {
        return GitHubPoller.sequentially(cycle, repos, repo -> repoFeed(cycle, repo));
    }

    private Promise<Unit> repoFeed(GitHubPoller.Cycle cycle, KilnConfig.Repo repo) {
        return cycle.attempt(reads.pages(path(repo.name(),
                                              "pulls?state=open&per_page=100"),
                                         body -> PullsPage.decode(body,
                                                                  repo.name()),
                                         _ -> true))
                    .flatMap(read -> GitHubPoller.whenRead(read,
                                                           pages -> openChecks(cycle,
                                                                               repo,
                                                                               openOf(pages))))
                    .flatMap(_ -> baseFeed(cycle,
                                           repo.name(),
                                           repo.bases()));
    }

    private static List<PullRequest> openOf(List<PullsPage.Page> pages) {
        return pages.stream()
                    .flatMap(page -> page.pulls()
                                         .stream())
                    .toList();
    }

    private Promise<Unit> openChecks(GitHubPoller.Cycle cycle, KilnConfig.Repo repo, List<PullRequest> open) {
        var lists = new ConcurrentHashMap<BranchName, RequiredChecks>();

        return GitHubPoller.sequentially(cycle,
                                         Stream.concat(repo.bases().stream(),
                                                       open.stream().map(PullRequest::base))
                                               .distinct()
                                               .toList(),
                                         base -> listOf(cycle, repo, base, lists))
                           .flatMap(_ -> GitHubPoller.sequentially(cycle,
                                                                   open,
                                                                   pr -> prChecks(cycle,
                                                                                  repo.name(),
                                                                                  pr,
                                                                                  lists)));
    }

    private Promise<Unit> listOf(GitHubPoller.Cycle cycle,
                                 KilnConfig.Repo repo,
                                 BranchName base,
                                 Map<BranchName, RequiredChecks> lists) {
        return reads.parsed(path(repo.name(),
                                 "branches/" + GitHubPoller.segment(base)),
                            BranchRead::decode)
                    .fold(branch -> branchRead(cycle,
                                               repo,
                                               base,
                                               cycle.noted(branch),
                                               lists));
    }

    /// The list from the branch read and the rulesets, then the base's tip from the same branch read.
    private Promise<Unit> branchRead(GitHubPoller.Cycle cycle,
                                     KilnConfig.Repo repo,
                                     BranchName base,
                                     Result<BranchRead> branch,
                                     Map<BranchName, RequiredChecks> lists) {
        return withRulesets(cycle, repo, base, branch).flatMap(list -> listed(cycle,
                                                                              repo.name(),
                                                                              base,
                                                                              list,
                                                                              lists))
                           .flatMap(_ -> cycle.live(() -> baseTip(cycle, repo, base, branch)));
    }

    private Promise<RequiredChecks> withRulesets(GitHubPoller.Cycle cycle,
                                                 KilnConfig.Repo repo,
                                                 BranchName base,
                                                 Result<BranchRead> branch) {
        return reads.parsed(path(repo.name(),
                                 "rules/branches/" + GitHubPoller.segment(base)),
                            Rulesets::decode)
                    .fold(rules -> Promise.success(RequiredChecks.resolve(branch,
                                                                          cycle.noted(rules),
                                                                          repo.bases().contains(base))));
    }

    private Promise<Unit> listed(GitHubPoller.Cycle cycle,
                                 String repo,
                                 BranchName base,
                                 RequiredChecks list,
                                 Map<BranchName, RequiredChecks> lists) {
        lists.put(base, list);

        return requiredList(cycle, repo, base, list);
    }

    private Promise<Unit> prChecks(GitHubPoller.Cycle cycle,
                                   String repo,
                                   PullRequest pr,
                                   Map<BranchName, RequiredChecks> lists) {
        return switch (option(lists.get(pr.base())).or(new RequiredChecks.Unknown("not read"))) {
            case RequiredChecks.Required list -> checkReads(cycle, repo, pr, list);
            default -> unitPromise();
        };
    }

    private Promise<Unit> checkReads(GitHubPoller.Cycle cycle,
                                     String repo,
                                     PullRequest pr,
                                     RequiredChecks.Required list) {
        return reads.pages(path(repo,
                                "commits/" + pr.head().value() + "/check-runs?filter=all&per_page=100"),
                           CheckRun::decode,
                           _ -> true)
                    .map(GitHubPoller::joined)
                    .fold(runs -> withStatuses(cycle,
                                               repo,
                                               pr,
                                               list,
                                               cycle.noted(runs)));
    }

    private Promise<Unit> withStatuses(GitHubPoller.Cycle cycle,
                                       String repo,
                                       PullRequest pr,
                                       RequiredChecks.Required list,
                                       Result<List<CheckRun>> runs) {
        return reads.pages(path(repo,
                                "commits/" + pr.head().value() + "/statuses?per_page=100"),
                           CommitStatus::decode,
                           _ -> true)
                    .map(GitHubPoller::joined)
                    .fold(statuses -> checks(cycle,
                                             repo,
                                             pr,
                                             list,
                                             runs,
                                             cycle.noted(statuses)));
    }

    // base reads
    /// One repository's not-completed listings, branch, scheduled and issue reads. Its re-read runs later ([#rereads]),
    /// after every other read.
    Promise<Unit> baseFeed(GitHubPoller.Cycle cycle, String repo, List<BranchName> bases) {
        return cycle.attempt(heads.heads(repo))
                    .map(listed -> remembered(repo,
                                              tracked(bases,
                                                      listed.or(List.of()))))
                    .flatMap(branches -> notCompleted(cycle, repo, branches).map(_ -> branches))
                    .flatMap(branches -> GitHubPoller.sequentially(cycle,
                                                                   branches,
                                                                   branch -> pushRuns(cycle, repo, branch)))
                    .flatMap(_ -> cycle.live(() -> scheduledRuns(cycle, repo)))
                    .flatMap(_ -> cycle.live(() -> issues(cycle, repo)));
    }

    /// R3-5 (lead ruling): `status=queued` and `status=in_progress`, paged, before anything else, so a run that is not
    /// complete (a rerun of an old run included: it is listed again whatever its `created_at`) is stored as such and is
    /// re-read first ([FeedStore#rereadable]) instead of waiting its turn. Only the runs the feed reports are kept: a push
    /// run of a tracked branch, or a scheduled run.
    private Promise<Unit> notCompleted(GitHubPoller.Cycle cycle, String repo, List<BranchName> branches) {
        return GitHubPoller.sequentially(cycle, NOT_COMPLETED, status -> listing(cycle, repo, branches, status));
    }

    private Promise<Unit> listing(GitHubPoller.Cycle cycle, String repo, List<BranchName> branches, String status) {
        return cycle.attempt(reads.pages(path(repo, "actions/runs?status=" + status + "&per_page=100"),
                                         WorkflowRun::decode,
                                         _ -> true))
                    .flatMap(read -> GitHubPoller.whenRead(read,
                                                           pages -> store.runs(repo,
                                                                               reported(GitHubPoller.joined(pages),
                                                                                        branches),
                                                                               cycle.at())
                                                                         .mapToUnit()));
    }

    /// The runs the feed reports on: scheduled ones, and push runs of a tracked branch.
    static List<WorkflowRun> reported(List<WorkflowRun> runs, List<BranchName> branches) {
        var names = branches.stream().map(BranchName::value).toList();

        return runs.stream()
                   .filter(run -> RunFeed.scheduled(run) || pushOn(run, names))
                   .toList();
    }

    private static boolean pushOn(WorkflowRun run, List<String> branches) {
        return run.event()
                  .filter(RunFeed.PUSH::equals)
                  .isPresent() && run.headBranch()
                                     .filter(branches::contains)
                                     .isPresent();
    }

    private List<BranchName> remembered(String repo, List<BranchName> branches) {
        tracked.put(repo, branches.size());

        return branches;
    }

    /// `main`, the configured bases, and every listed `release-*` branch, each once.
    static List<BranchName> tracked(List<BranchName> bases, List<BranchName> listed) {
        return Stream.of(BranchName.branchName(MAIN).stream(),
                         bases.stream(),
                         listed.stream().filter(branch -> branch.value()
                                                                .startsWith(RELEASE)))
                     .flatMap(branches -> branches)
                     .distinct()
                     .toList();
    }

    private Promise<Unit> pushRuns(GitHubPoller.Cycle cycle, String repo, BranchName branch) {
        return store.newestCreated(repo,
                                   RunFeed.PUSH,
                                   some(branch.value()))
                    .flatMap(newest -> windowRead(cycle,
                                                  repo,
                                                  "actions/runs?branch=" + GitHubPoller.segment(branch) + "&event=push",
                                                  RunFeed.windowStart(newest,
                                                                      cycle.at())));
    }

    private Promise<Unit> scheduledRuns(GitHubPoller.Cycle cycle, String repo) {
        return store.newestCreated(repo,
                                   RunFeed.SCHEDULE,
                                   none())
                    .flatMap(newest -> windowRead(cycle,
                                                  repo,
                                                  "actions/runs?event=schedule",
                                                  RunFeed.windowStart(newest,
                                                                      cycle.at())));
    }

    /// Paged until exhausted (F11): never a fixed page count.
    private Promise<Unit> windowRead(GitHubPoller.Cycle cycle, String repo, String query, Instant from) {
        return cycle.attempt(reads.pages(path(repo,
                                              windowQuery(query, from)),
                                         WorkflowRun::decode,
                                         _ -> true))
                    .flatMap(read -> GitHubPoller.whenRead(read,
                                                           pages -> store.runs(repo,
                                                                               GitHubPoller.joined(pages),
                                                                               cycle.at())
                                                                         .mapToUnit()));
    }

    /// `<query>&created=%3E%3D<from, whole seconds>&per_page=100`: the same window asks the same URL, so a quiet cycle
    /// is answered 304.
    static String windowQuery(String query, Instant from) {
        return query
             + "&created=" + URLEncoder.encode(">=" + from.truncatedTo(ChronoUnit.SECONDS), StandardCharsets.UTF_8)
             + "&per_page=100";
    }

    private Promise<Unit> issues(GitHubPoller.Cycle cycle, String repo) {
        return store.newestIssue(repo)
                    .map(newest -> IssueFeed.since(newest,
                                                   cycle.at()))
                    .flatMap(since -> cycle.attempt(reads.pages(path(repo,
                                                                     ISSUES + since.truncatedTo(ChronoUnit.SECONDS)),
                                                                BotIssue::decode,
                                                                _ -> true)))
                    .flatMap(read -> GitHubPoller.whenRead(read,
                                                           pages -> store.issues(repo,
                                                                                 GitHubPoller.joined(pages),
                                                                                 cycle.at())
                                                                         .mapToUnit()));
    }

    // re-read
    /// Every repository's bounded re-read, last in the cycle, so it spends only what the cycle's other reads left.
    Promise<Unit> rereads(GitHubPoller.Cycle cycle) {
        return GitHubPoller.sequentially(cycle,
                                         repos.stream().map(KilnConfig.Repo::name).toList(),
                                         repo -> reread(cycle, repo));
    }

    /// The cap: [#REREAD_MAX], and no more than the budget's spare above the reserve now.
    int cap(Instant now) {
        return reads.budget()
                    .spare(now)
                    .map(spare -> Math.min(REREAD_MAX, spare))
                    .or(REREAD_MAX);
    }

    private Promise<Unit> reread(GitHubPoller.Cycle cycle, String repo) {
        return store.rereadable(repo,
                                cycle.at().minus(RunFeed.RECENT),
                                cycle.at(),
                                REREAD_MAX)
                    .map(candidates -> RunFeed.reread(candidates,
                                                      cap(cycle.at())))
                    .flatMap(ids -> GitHubPoller.sequentially(cycle,
                                                              ids,
                                                              id -> rereadOne(cycle, repo, id)));
    }

    /// A run GitHub no longer has (404: deleted) is forgotten, so it is not asked again; any other failure is noted.
    private Promise<Unit> rereadOne(GitHubPoller.Cycle cycle, String repo, long id) {
        return reads.parsed(path(repo, "actions/runs/" + id),
                            WorkflowRun::decodeOne)
                    .fold(read -> reread(cycle, repo, id, read));
    }

    private Promise<Unit> reread(GitHubPoller.Cycle cycle, String repo, long id, Result<WorkflowRun> read) {
        return read.fold(cause -> missing(cycle, repo, id, cause),
                         run -> store.runs(repo,
                                           List.of(run),
                                           cycle.at())
                                     .mapToUnit());
    }

    /// FER: a deleted run degrades forward to forgotten (nothing left to report for it); any other failure is noted
    /// and the run stays for the next cycle.
    private Promise<Unit> missing(GitHubPoller.Cycle cycle, String repo, long id, Cause cause) {
        return switch (cause) {
            case GitHubError.HttpStatus http when http.status() == NOT_FOUND -> store.forgotten(repo, id);
            default -> cycle.attempt(cause.<Unit> promise()).mapToUnit();
        };
    }

    private static String path(String repo, String relative) {
        return "/repos/" + repo + "/" + relative;
    }
}
