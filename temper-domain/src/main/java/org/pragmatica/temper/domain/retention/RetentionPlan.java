package org.pragmatica.temper.domain.retention;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.evidence.EvidenceKind;


/// The retention plan (m1-design §4.1, Q-M1-11). Pure: the store supplies the candidates, the controller
/// executes the actions.
///
/// - **A job with `keep`, or one that is not terminal, produces no action. Ever:** not for its evidence, its
///   rows or its checkout.
/// - From the job's finish: the `full` tarball at [RetentionPolicy#full]; the `small` tarball and the
///   `testcases`/`manifests`/`unit_facts` rows at [RetentionPolicy#evidence]; each host checkout at
///   [RetentionPolicy#checkout]. A terminal job's small evidence is already settled (pulled, or failed and
///   recorded as `evidence=missing`), because the controller pulls it before the job's result commits.
/// - Never: `results`, `jobs`, `units`, `result.json`.
/// - Events: `eventHorizonSeq` is the highest `seq` older than [RetentionPolicy#events], computed by the store
///   regardless of `keep` (events are a stream no job references); a positive horizon becomes one action.
/// - `pg_dump` files older than [RetentionPolicy#pgDumps], except the newest, which is always kept. Precondition
///   on the caller: `dumps` lists verified dumps only ([DumpFile]), so "the newest" is the newest good one.
public sealed interface RetentionPlan {
    static List<RetentionAction> plan(RetentionPolicy policy,
                                      Instant now,
                                      List<JobRetention> jobs,
                                      Option<Long> eventHorizonSeq,
                                      List<DumpFile> dumps) {
        return Stream.of(jobs.stream().flatMap(job -> jobActions(policy, now, job)),
                         eventActions(eventHorizonSeq),
                         dumpActions(policy, now, dumps))
                     .flatMap(actions -> actions)
                     .toList();
    }

    private static Stream<RetentionAction> jobActions(RetentionPolicy policy, Instant now, JobRetention job) {
        return job.finishedAt()
                  .filter(_ -> isCollectable(job))
                  .map(finishedAt -> finishedJobActions(policy, now, job, finishedAt))
                  .or(Stream::empty);
    }

    private static boolean isCollectable(JobRetention job) {
        return ! job.keep() && job.terminal();
    }

    private static Stream<RetentionAction> finishedJobActions(RetentionPolicy policy,
                                                              Instant now,
                                                              JobRetention job,
                                                              Instant finishedAt) {
        return Stream.of(evidenceActions(policy, now, job, finishedAt),
                         testcaseActions(policy, now, job, finishedAt),
                         checkoutActions(policy, now, job, finishedAt)).flatMap(actions -> actions);
    }

    private static Stream<RetentionAction> evidenceActions(RetentionPolicy policy,
                                                           Instant now,
                                                           JobRetention job,
                                                           Instant finishedAt) {
        return job.items()
                  .stream()
                  .filter(item -> isElapsed(finishedAt,
                                            lifetime(policy,
                                                     item.kind()),
                                            now))
                  .map(item -> new RetentionAction.DeleteEvidence(item.id(),
                                                                  item.storedAt()));
    }

    private static Stream<RetentionAction> checkoutActions(RetentionPolicy policy,
                                                           Instant now,
                                                           JobRetention job,
                                                           Instant finishedAt) {
        return isElapsed(finishedAt, policy.checkout(), now)
               ? job.hostsWithCheckout()
                    .stream()
                    .map(host -> new RetentionAction.DeleteCheckout(host,
                                                                    job.job()))
               : Stream.empty();
    }

    private static Stream<RetentionAction> testcaseActions(RetentionPolicy policy,
                                                           Instant now,
                                                           JobRetention job,
                                                           Instant finishedAt) {
        return job.hasTestcases() && isElapsed(finishedAt, policy.evidence(), now)
               ? Stream.of(new RetentionAction.DeleteTestcases(job.job()))
               : Stream.empty();
    }

    private static Duration lifetime(RetentionPolicy policy, EvidenceKind kind) {
        return switch (kind) {
            case SMALL -> policy.evidence();
            case FULL -> policy.full();
        };
    }

    private static Stream<RetentionAction> eventActions(Option<Long> eventHorizonSeq) {
        return eventHorizonSeq.filter(seq -> seq > 0)
                              .<RetentionAction> map(RetentionAction.DeleteEventsUpTo::new)
                              .stream();
    }

    private static Stream<RetentionAction> dumpActions(RetentionPolicy policy, Instant now, List<DumpFile> dumps) {
        var newest = Option.from(dumps.stream().max(Comparator.comparing(DumpFile::takenAt)));

        return dumps.stream()
                    .filter(dump -> isElapsed(dump.takenAt(),
                                              policy.pgDumps(),
                                              now))
                    .filter(dump -> !isNewest(dump, newest))
                    .map(dump -> new RetentionAction.DeletePgDump(dump.name()));
    }

    private static boolean isNewest(DumpFile dump, Option<DumpFile> newest) {
        return newest.filter(dump::equals)
                     .isPresent();
    }

    /// The `lifetime` starting at `start` has run out at `now` (inclusive).
    private static boolean isElapsed(Instant start, Duration lifetime, Instant now) {
        return ! start.plus(lifetime)
                      .isAfter(now);
    }

    record unused() implements RetentionPlan {}
}
