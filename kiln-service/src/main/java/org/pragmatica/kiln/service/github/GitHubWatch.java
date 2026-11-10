package org.pragmatica.kiln.service.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.RulesetDrift;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.Promise.unitPromise;


/// What kiln publishes in `GET /api/v1/github-budget` besides the rate: the drift
/// reads, and the `/rate_limit` sample taken when no reply has stated the rate for [#IDLE].
///
/// - **Rulesets**: each cycle, `rulesets/{id}` for each id in the `rules/branches/{base}` replies of the configured bases
///   that the cycle read ([#seen]); the hash covers those replies and each ruleset's governing part ([RulesetDrift]).
/// - **Team**: every [#TEAM_EVERY]th cycle, the members of `temper-reviewers` (the first cycle included), every page.
///
/// A hash is published only from a complete cycle: every configured base's `rules/branches` reply and every ruleset it
/// names were read. Otherwise the last complete hash stays, with its `observedAt`, so one failed read is not a flap.
///
/// FER: a ruleset drift read that fails, or a cycle that lacks a configured base's reply, keeps the last published value
/// but is never silent: the cycle is logged with its reason, [Drift] reports it not fresh (`failure`, `failedCycles`, and
/// `observedAt` still the last good read), and after [#STALE_AFTER] consecutive such cycles [#staleReason] turns the GitHub
/// source state to `Incomplete` (the poller's existing mechanism for a source that answered but not completely). A complete
/// cycle clears it. The team read is not part of this count: it runs every [#TEAM_EVERY]th cycle, so "consecutive cycles"
/// does not describe it; its time stays visible through [Drift#observedAt]. Each drift request is spent from the shared [RateBudget] by
/// [GitHubReads], so it is bounded by `hourlyCap` like any other; a cycle's `need` counts them ([#driftNeed]), and a drift
/// read the budget refuses keeps the last value.
public record GitHubWatch(Map<BranchName, String> effective,
                          AtomicInteger cycles,
                          AtomicReference<Option<Rulesets>> rulesets,
                          AtomicReference<Option<Team>> team,
                          AtomicReference<Option<Instant>> probed,
                          AtomicReference<Option<Failing>> failing) {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubWatch.class);
    /// Consecutive cycles whose ruleset drift read was not complete before the GitHub source reads `Incomplete`. A constant:
    /// kiln's config has no section for read-loop tuning, and two cycles (about two minutes) rides out one transient error.
    public static final int STALE_AFTER = 2;
    /// No reply for this long and kiln asks `GET /rate_limit`.
    public static final Duration IDLE = Duration.ofMinutes(5);
    public static final int TEAM_EVERY = 5;
    public static final String TEAM_SLUG = "temper-reviewers";

    public record Rulesets(String hash, List<Long> ids, Instant at) {}

    public record Team(List<String> members, int pages, Instant at) {}

    /// The ruleset drift read has not been complete for `cycles` consecutive cycles; `reason` is the latest cause.
    public record Failing(String reason, int cycles) {}

    /// The drift as published; a part not yet read is none.
    public record Drift(Option<String> rulesetHash,
                        List<Long> rulesetIds,
                        Option<List<String>> teamMembers,
                        Option<Instant> observedAt,
                        Option<String> failure,
                        int failedCycles) {
        /// Whether the latest ruleset read was complete; `observedAt` is the last good read either way.
        public boolean fresh() {
            return failure.isEmpty();
        }
    }

    public static GitHubWatch gitHubWatch() {
        return new GitHubWatch(new ConcurrentHashMap<>(),
                               new AtomicInteger(),
                               new AtomicReference<>(none()),
                               new AtomicReference<>(none()),
                               new AtomicReference<>(none()),
                               new AtomicReference<>(none()));
    }

    /// A cycle starts: the effective rules of the last one are not this cycle's.
    Unit begin() {
        effective.clear();

        return unit();
    }

    /// The decode of a `rules/branches/{base}` reply, which also keeps the reply of a configured base for the drift read.
    <T> Result<T> seen(BranchName base, boolean configured, String body, Result<T> decoded) {
        return decoded.onSuccess(_ -> keep(base, configured, body));
    }

    private void keep(BranchName base, boolean configured, String body) {
        if (configured) {
            effective.put(base, body);
        }
    }

    public Drift drift() {
        var read = rulesets.get();
        var members = team.get();

        return new Drift(read.map(Rulesets::hash),
                         read.map(Rulesets::ids).or(List.of()),
                         members.map(Team::members),
                         oldest(read.map(Rulesets::at), members.map(Team::at)),
                         failing.get().map(Failing::reason),
                         failing.get().map(Failing::cycles).or(0));
    }

    /// Why the GitHub source is not `Ok` on account of the drift read: it has been incomplete for [#STALE_AFTER] cycles.
    /// Names the failure and the last good read, so the tab shows both.
    Option<String> staleReason() {
        return failing.get()
                      .filter(now -> now.cycles() >= STALE_AFTER)
                      .map(now -> "ruleset drift not read for " + now.cycles()
                                 + " cycles, last good " + rulesets.get()
                                                                   .map(read -> read.at()
                                                                                    .toString())
                                                                   .or("never")
                                 + ": " + now.reason());
    }

    /// The older of the two read times: a ruleset hash frozen by a failing base keeps its own time even while the team
    /// read goes on, so the consumer sees the staleness instead of a fresh time over an old hash.
    private static Option<Instant> oldest(Option<Instant> first, Option<Instant> second) {
        return Option.from(Stream.of(first, second).flatMap(Option::stream).min(Comparator.naturalOrder()));
    }

    /// The `/rate_limit` sample, when the last reply that stated the rate is older than [#IDLE]. Before any reply there is no sample to age: the cycle's own replies state the rate. The sample
    /// is the reply's own `x-ratelimit-*`, recorded as any reply's; the request is free on the primary limit (F3), so it is
    /// not spent from the budget.
    Promise<Unit> sampled(GitHubReads reads, Instant now) {
        return stale(reads.budget(), now)
               ? reads.http()
                      .ready()
                      .flatMap(_ -> reads.http()
                                         .get("/rate_limit",
                                              none()))
                      .map(answer -> stated(reads.budget(),
                                            answer,
                                            now))
                      .recover(_ -> unit())
               : unitPromise();
    }

    private static boolean stale(RateBudget budget, Instant now) {
        return ! budget.blocked(now) && budget.rate()
                                              .get()
                                              .at()
                                              .map(at -> !at.plus(IDLE)
                                                            .isAfter(now))
                                              .or(false);
    }

    private Unit stated(RateBudget budget, GhAnswer answer, Instant now) {
        budget.observed(answer, now);
        answer.remaining().onPresent(_ -> probed.set(some(now)));

        return unit();
    }

    /// Whether the rate last stated came from `/rate_limit` rather than a read's headers.
    boolean fromProbe(Instant at) {
        return probed.get()
                     .filter(at::equals)
                     .isPresent();
    }

    /// The requests the coming cycle's drift reads can send: one per ruleset the last cycle found, and the team's pages
    /// (as many as it had when last read) on every [#TEAM_EVERY]th cycle. The first cycle does not know its rulesets yet: the budget still spends each request.
    int driftNeed() {
        return rulesets.get()
                       .map(known -> known.ids()
                                          .size())
                       .or(0) + (cycles.get() % TEAM_EVERY == 0
                                 ? team.get()
                                       .map(Team::pages)
                                       .or(1)
                                 : 0);
    }

    /// The drift reads of one cycle: the rulesets, then (every [#TEAM_EVERY]th cycle) the team.
    Promise<Unit> read(GitHubReads reads, String repo, List<BranchName> bases, Instant now) {
        var number = cycles.getAndIncrement();

        return readRulesets(reads, repo, bases, now).flatMap(_ -> number % TEAM_EVERY == 0
                                                                  ? readTeam(reads, repo, now)
                                                                  : unitPromise());
    }

    private Promise<Unit> readRulesets(GitHubReads reads, String repo, List<BranchName> bases, Instant now) {
        var bodies = new TreeMap<String, String>();

        effective.forEach((base, body) -> bodies.put(base.value(), body));
        var absent = bases.stream().filter(base -> !effective.containsKey(base)).map(BranchName::value).toList();

        return bases.isEmpty()
               ? unitPromise()
               : absent.isEmpty()
                 ? complete(reads, repo, bodies, now)
                 : Promise.success(failed("rules/branches not read for " + String.join(", ", absent)));
    }

    private Promise<Unit> complete(GitHubReads reads, String repo, Map<String, String> bodies, Instant now) {
        return ids(bodies).async()
                  .flatMap(ids -> organizations(bodies).async()
                                               .flatMap(organizations -> governing(reads, repo, ids, organizations)))
                  .map(governing -> published(bodies, governing, now))
                  .recover(cause -> failed(cause.message()));
    }

    /// An incomplete ruleset read: counted, logged with its reason, and shown through [#drift] and [#staleReason].
    private Unit failed(String reason) {
        var next = failing.updateAndGet(previous -> some(new Failing(reason,
                                                                     previous.map(Failing::cycles).or(0) + 1)))
                          .unwrap();

        LOG.warn("ruleset drift read incomplete ({} consecutive): {}", next.cycles(), reason);

        return unit();
    }

    private static Result<List<Long>> ids(Map<String, String> bodies) {
        return Result.allOf(bodies.values().stream().map(RulesetDrift::ids).toList()).map(lists -> lists.stream()
                                                                                                        .flatMap(List::stream)
                                                                                                        .distinct()
                                                                                                        .sorted()
                                                                                                        .toList());
    }

    private static Result<Set<Long>> organizations(Map<String, String> bodies) {
        return Result.allOf(bodies.values().stream().map(RulesetDrift::organizationIds).toList()).map(sets -> sets.stream()
                                                                                                                  .flatMap(Set::stream)
                                                                                                                  .collect(Collectors.toSet()));
    }

    private Promise<Map<Long, String>> governing(GitHubReads reads,
                                                 String repo,
                                                 List<Long> ids,
                                                 Set<Long> organizations) {
        return ids.stream()
                  .reduce(Promise.success((Map<Long, String>) new TreeMap<Long, String>()),
                          (chain, id) -> chain.flatMap(read -> governing(reads,
                                                                         repo,
                                                                         id,
                                                                         organizations.contains(id)).map(part -> with(read,
                                                                                                                      id,
                                                                                                                      part))),
                          (first, second) -> second);
    }

    /// An organization's ruleset is read at the organization's path (guess: its owner is the repository's owner).
    private Promise<String> governing(GitHubReads reads, String repo, long id, boolean organization) {
        return reads.parsed((organization
                             ? "/orgs/" + owner(repo)
                             : "/repos/" + repo) + "/rulesets/" + id,
                            RulesetDrift::governing);
    }

    private static Map<Long, String> with(Map<Long, String> read, long id, String part) {
        var next = new TreeMap<>(read);

        next.put(id, part);

        return next;
    }

    private Unit published(Map<String, String> bodies, Map<Long, String> governing, Instant now) {
        var canonical = new StringBuilder();

        bodies.forEach((base, body) -> canonical.append(base)
                                                .append('=')
                                                .append(RulesetDrift.canonical(body).or(body))
                                                .append('\n'));
        rulesets.set(some(new Rulesets(sha256(RulesetDrift.material(canonical.toString(), governing)),
                                       List.copyOf(governing.keySet()),
                                       now)));
        failing.getAndSet(none())
               .onPresent(was -> LOG.info("ruleset drift read complete again after {} cycles",
                                          was.cycles()));

        return unit();
    }

    private Promise<Unit> readTeam(GitHubReads reads, String repo, Instant now) {
        return reads.pages("/orgs/" + owner(repo) + "/teams/" + TEAM_SLUG + "/members?per_page=100",
                           RulesetDrift::members,
                           _ -> true)
                    .map(pages -> {
                             team.set(some(new Team(pages.stream().flatMap(List::stream).sorted().toList(),
                                                    pages.size(),
                                                    now)));

                             return unit();
                         })
                    .recover(_ -> unit());
    }

    private static String owner(String repo) {
        return repo.substring(0, repo.indexOf('/'));
    }

    /// SHA-256 as 64 lowercase hex digits (the digest lives here: the domain package takes no I/O).
    static String sha256(String text) {
        return Result.lift(Causes::fromThrowable,
                           () -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))))
                     .unwrap();
    }
}
