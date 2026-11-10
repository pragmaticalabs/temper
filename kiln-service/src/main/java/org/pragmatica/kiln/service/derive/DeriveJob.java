package org.pragmatica.kiln.service.derive;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.checkout.Derivation;
import org.pragmatica.kiln.core.checkout.PrIndex;
import org.pragmatica.kiln.core.checkout.Reach;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.BranchTip;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.service.flow.SingleFlight;
import org.pragmatica.kiln.service.flow.Steps;
import org.pragmatica.kiln.service.mirror.MirrorGit;
import org.pragmatica.kiln.service.scan.HostScanner;
import org.pragmatica.kiln.service.scan.ScanAnswers;
import org.pragmatica.kiln.service.scan.ScanHost;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.CheckoutStore;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The checkout derivation (kiln-f1-design §3.1 `DeriveJob`, spec §5.3): for every host's rows, the reach of each tip and
/// HEAD's ancestry, then [Derivation#derive] over the full PR table, written to `checkout_derived` with the times of the
/// inputs it used (the row's `facts_at`, the last mirror fetch, the last PR upsert), so a status always shows the age of
/// the fact it came from (§5.6).
///
/// Reach: a tip the mirror holds is [Reach#MIRROR]. For a tip it lacks, a scanned host is asked with `reach` (at most 200
/// SHAs a call, through the host's [ScanHost#exclusive], so it never overlaps the host's scan sessions): a ref of the
/// host's own bare is [Reach#HOST_BARE], none is [Reach#NOWHERE]. A host that fails to answer leaves those tips
/// [Reach#UNKNOWN], never `NOWHERE`; the Mac has no `reach`, so its tips the mirror lacks read `UNKNOWN`. A mirror or
/// store failure fails the run, and the previous derived rows stay with their own times.
///
/// Runs are coalesced ([#trigger]): one at a time; a trigger during a run makes exactly one more run after it.
public final class DeriveJob {
    /// The most SHAs one `reach` call names (§3.2).
    public static final int REACH_BATCH = 200;
    /// A run still going this long ends (fails), and a pending trigger starts the next.
    public static final Duration DEADLINE = Duration.ofMinutes(30);
    static final Cause EXPIRED = Causes.cause("the derivation run expired before this write");
    private static final Logger log = LoggerFactory.getLogger(DeriveJob.class);
    private static final Ancestry NONE = new Ancestry(false, false, Set.of(), Set.of());

    private final CheckoutStore checkouts;
    private final PrStore prs;
    private final MirrorGit mirror;
    private final List<ScanHost> hosts;
    private final String repo;
    private final List<BranchName> bases;
    private final Duration staleAfter;
    private final Duration stepDeadline;
    private final Duration deadline;
    private final Supplier<Instant> clock;
    private final AtomicReference<Option<Instant>> mirrorAt = new AtomicReference<>(none());
    private final AtomicReference<Option<Instant>> prsAt = new AtomicReference<>(none());
    /// Triggers not yet covered by a finished run: a run covers those counted when it started.
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Promise<Unit>> settled = new AtomicReference<>(Promise.unitPromise());

    private DeriveJob(CheckoutStore checkouts,
                      PrStore prs,
                      MirrorGit mirror,
                      List<ScanHost> hosts,
                      String repo,
                      List<BranchName> bases,
                      Duration staleAfter,
                      Duration stepDeadline,
                      Duration deadline,
                      Supplier<Instant> clock) {
        this.checkouts = checkouts;
        this.prs = prs;
        this.mirror = mirror;
        this.hosts = List.copyOf(hosts);
        this.repo = repo;
        this.bases = List.copyOf(bases);
        this.staleAfter = staleAfter;
        this.stepDeadline = stepDeadline;
        this.deadline = deadline;
        this.clock = clock;
    }

    /// `hosts` are the scanned hosts (their `reach` and their serial); the Mac's rows are derived too, without reach.
    /// `repo` and `bases` are `[github]`'s; `staleAfter` is `[scan] staleAfterHours`.
    public static DeriveJob deriveJob(CheckoutStore checkouts,
                                      PrStore prs,
                                      MirrorGit mirror,
                                      List<ScanHost> hosts,
                                      String repo,
                                      List<BranchName> bases,
                                      Duration staleAfter,
                                      Supplier<Instant> clock) {
        return deriveJob(checkouts,
                         prs,
                         mirror,
                         hosts,
                         repo,
                         bases,
                         staleAfter,
                         HostScanner.STEP_DEADLINE,
                         DEADLINE,
                         clock);
    }

    public static DeriveJob deriveJob(CheckoutStore checkouts,
                                      PrStore prs,
                                      MirrorGit mirror,
                                      List<ScanHost> hosts,
                                      String repo,
                                      List<BranchName> bases,
                                      Duration staleAfter,
                                      Duration stepDeadline,
                                      Duration deadline,
                                      Supplier<Instant> clock) {
        return new DeriveJob(checkouts, prs, mirror, hosts, repo, bases, staleAfter, stepDeadline, deadline, clock);
    }

    /// The scanned hosts whose `reach` this job asks, each through its one serial (shared with its scanner, WP9).
    public List<ScanHost> hosts() {
        return hosts;
    }

    /// The trigger after a mirror fetch that succeeded at `at` (§3.1): the derived rows' `mirror_at` from now on.
    public Promise<Unit> mirrorFetched(Instant at) {
        mirrorAt.set(some(at));

        return trigger();
    }

    /// The trigger after a PR upsert observed at `at` (§3.1): the derived rows' `prs_at` from now on.
    public Promise<Unit> prsUpserted(Instant at) {
        prsAt.set(some(at));

        return trigger();
    }

    /// A coalesced run (after a pass, a fetch or an upsert): starts one now, or, while one runs, makes one more run after
    /// it. Resolves once no run is pending; it never fails (a failed run leaves the previous derived rows). Residual: a
    /// trigger racing the very start of a run may get the previous, already settled promise; its request is still
    /// covered by the run that started.
    public Promise<Unit> trigger() {
        return requests.getAndIncrement() == 0
               ? started()
               : settled.get();
    }

    private Promise<Unit> started() {
        var done = Promise.<Unit> promise();

        settled.set(done);
        drain(done);

        return done;
    }

    /// A run past its deadline is marked expired: its late writes are dropped ([#live]), so it never writes older rows
    /// over the next run's. Residual: a write already in flight at the deadline still completes.
    private void drain(Promise<Unit> done) {
        var covered = requests.get();
        var expired = new AtomicBoolean();

        SingleFlight.bounded(() -> derive(expired),
                             deadline)
                    .withFailure(cause -> expiredBy(expired, cause))
                    .withResult(_ -> ran(done, covered));
    }

    private static void expiredBy(AtomicBoolean expired, Cause cause) {
        expired.set(true);
        log.warn("derivation failed; the derived rows keep their times: {}", cause.message());
    }

    /// Triggers that arrived during the run make one more run, which covers them all.
    private void ran(Promise<Unit> done, int covered) {
        if (requests.addAndGet(-covered) > 0) {
            drain(done);
        } else {
            done.succeed(unit());
        }
    }

    /// One derivation of every host now, outside the coalescing.
    public Promise<Unit> derive() {
        return derive(new AtomicBoolean());
    }

    private Promise<Unit> derive(AtomicBoolean expired) {
        var now = clock.get();

        return prs.all()
                  .map(all -> PrIndex.prIndex(all, repo))
                  .flatMap(index -> Steps.sequence(targets(),
                                                   target -> derived(target, index, now, expired)))
                  .mapToUnit();
    }

    /// One host's rows and how its tips are reached beyond the mirror: a scanned host's `reach`, or none (the Mac).
    record Target(HostName name, Option<ScanHost> link) {}

    private List<Target> targets() {
        return Stream.concat(hosts.stream().map(host -> new Target(host.name(),
                                                                   some(host))),
                             Stream.of(new Target(HostName.MAC,
                                                  none())))
                     .toList();
    }

    private Promise<Unit> derived(Target target, PrIndex index, Instant now, AtomicBoolean expired) {
        return checkouts.rows(target.name())
                        .flatMap(rows -> derivedRows(target, rows, index, now))
                        .flatMap(derived -> live(expired,
                                                 () -> checkouts.derived(target.name(),
                                                                         derived)));
    }

    /// A step of a run past its deadline fails at once and writes nothing.
    private static Promise<Unit> live(AtomicBoolean expired, Supplier<Promise<Unit>> write) {
        return expired.get()
               ? EXPIRED.promise()
               : write.get();
    }

    private Promise<List<DerivedRow>> derivedRows(Target target, List<CheckoutRow> rows, PrIndex index, Instant now) {
        return Promise.all(reach(target, tips(rows)), ancestries(rows)).map((reach, ancestry) -> rows.stream()
                                                                                                     .map(row -> derivedRow(row,
                                                                                                                            reach,
                                                                                                                            ancestry,
                                                                                                                            index,
                                                                                                                            now))
                                                                                                     .toList());
    }

    private DerivedRow derivedRow(CheckoutRow row,
                                  Map<Sha, Reach> reach,
                                  Map<Sha, Ancestry> ancestry,
                                  PrIndex index,
                                  Instant now) {
        var heads = row.facts().flatMap(CheckoutFacts::head);
        var known = heads.map(head -> ancestry.getOrDefault(head, NONE)).or(NONE);

        return new DerivedRow(row.path(),
                              Derivation.derive(row.facts(), reach, known, index, staleAfter, now),
                              row.factsAt(),
                              mirrorAt.get(),
                              prsAt.get(),
                              now);
    }

    /// Every tip of every row with facts: each local branch's tip and HEAD.
    static Set<Sha> tips(List<CheckoutRow> rows) {
        return rows.stream()
                   .flatMap(row -> row.facts()
                                      .stream())
                   .flatMap(facts -> Stream.concat(facts.branches().stream().map(BranchTip::tip),
                                                   facts.head().stream()))
                   .collect(Collectors.toSet());
    }

    // ---- reach ---------------------------------------------------------------------------------------------------
    Promise<Map<Sha, Reach>> reach(Target target, Set<Sha> tips) {
        return mirror.reachable(tips)
                     .flatMap(inMirror -> hostBare(target,
                                                   lacking(tips, inMirror)).map(inBare -> reachOf(tips, inMirror, inBare)));
    }

    private static Set<Sha> lacking(Set<Sha> tips, Map<Sha, Boolean> inMirror) {
        return tips.stream()
                   .filter(tip -> !inMirror.getOrDefault(tip, false))
                   .collect(Collectors.toSet());
    }

    /// MIRROR first; then the host's answer: in its bare HOST_BARE, not NOWHERE; no answer UNKNOWN.
    static Map<Sha, Reach> reachOf(Set<Sha> tips, Map<Sha, Boolean> inMirror, Map<Sha, Boolean> inBare) {
        return tips.stream()
                   .collect(Collectors.toMap(tip -> tip,
                                             tip -> inMirror.getOrDefault(tip, false)
                                                    ? Reach.MIRROR
                                                    : Option.option(inBare.get(tip))
                                                            .map(DeriveJob::bareReach)
                                                            .or(Reach.UNKNOWN)));
    }

    private static Reach bareReach(boolean inBare) {
        return inBare
               ? Reach.HOST_BARE
               : Reach.NOWHERE;
    }

    /// The host's answers for the tips the mirror lacks, batch by batch; a failed batch answers nothing (UNKNOWN).
    private Promise<Map<Sha, Boolean>> hostBare(Target target, Set<Sha> lacking) {
        return target.link()
                     .map(link -> Steps.sequence(batches(lacking),
                                                 batch -> asked(link, batch)).map(DeriveJob::merged))
                     .or(() -> Promise.success(Map.of()));
    }

    static List<Set<Sha>> batches(Set<Sha> shas) {
        var sorted = shas.stream().sorted((left, right) -> left.value()
                                                               .compareTo(right.value())).toList();

        return IntStream.range(0,
                               (sorted.size() + REACH_BATCH - 1) / REACH_BATCH)
                        .mapToObj(index -> Set.copyOf(sorted.subList(index * REACH_BATCH,
                                                                     Math.min(sorted.size(),
                                                                              (index + 1) * REACH_BATCH))))
                        .toList();
    }

    private Promise<Map<Sha, Boolean>> asked(ScanHost link, Set<Sha> batch) {
        return link.exclusive(() -> link.transport()
                                        .reach(batch),
                              stepDeadline)
                   .flatMap(lines -> ScanAnswers.reach(batch, lines).async())
                   .recover(cause -> unanswered(link, cause));
    }

    /// No answer: the batch's tips read UNKNOWN. The reason is logged, as nothing else records it.
    private static Map<Sha, Boolean> unanswered(ScanHost link, Cause cause) {
        log.warn("reach on {} answered nothing; its tips read UNKNOWN: {}", link.name(), cause.message());

        return Map.of();
    }

    private static Map<Sha, Boolean> merged(List<Map<Sha, Boolean>> answers) {
        return answers.stream()
                      .flatMap(answer -> answer.entrySet()
                                               .stream())
                      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ---- ancestry ------------------------------------------------------------------------------------------------
    /// HEAD's ancestry once per distinct HEAD, one after another (the mirror's git calls are not parallel).
    private Promise<Map<Sha, Ancestry>> ancestries(List<CheckoutRow> rows) {
        var heads = rows.stream().flatMap(row -> row.facts()
                                                    .flatMap(CheckoutFacts::head)
                                                    .stream()).distinct().toList();

        return Steps.sequence(heads,
                              head -> mirror.ancestry(head, bases)
                                            .map(ancestry -> Map.entry(head, ancestry)))
                    .map(entries -> entries.stream()
                                           .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
}
