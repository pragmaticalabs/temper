package org.pragmatica.temper.controller.submit;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.cancel.UnitCanceller;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.dedup.DedupKey;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.JobRequest;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.plan.PlanDefaults;
import org.pragmatica.temper.domain.plan.Planner;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalRule;
import org.pragmatica.temper.domain.refusal.Refusals;
import org.pragmatica.temper.domain.refusal.SubmitShape;
import org.pragmatica.temper.domain.refusal.TreeFacts;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.RefusalStore;
import org.pragmatica.temper.store.api.SubmitOutcome;
import org.pragmatica.temper.store.api.TaskLink;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// `POST /jobs` (m0-design §6.3, m1-design §1.6): resolve source → gather the refusal inputs (the active
/// rules, then the tree facts their classes and the reactor need) → plan (the port-group rule needs the reactor) →
/// refuse → dedup key → `JobStore.submit` → cancel superseded units and wake the scheduler.
///
/// Any `Refusal` on the way persists the job as REFUSED (Q-16) and answers [Response.Refused] with its id. A refusal
/// raised after the source was resolved records the resolved SHA. Other failures are returned as they are: a
/// `DomainError` (400) or a [GitError] (git is down, 503, nothing persisted). The body is parsed into the [JobRequest]
/// by the HTTP adapter, so the use case sees no wire type.
///
/// A submit under a lease (`submit --task`, m3-design §6.4) carries the lease to the store, which links the job (a
/// refused one too) to the task at that epoch only while the lease holds, in the transaction that writes the job, and
/// fails with `TaskError.LEASE_LOST` (409) otherwise.
@FunctionalInterface
public interface SubmitJob {
    /// The parsed submit body (spec §2.1), and the lease it is submitted under, if any, with the submit's time.
    record Request(JobRequest job, Option<TaskLink> lease) {
        public static Request request(JobRequest job) {
            return new Request(job, none());
        }

        public static Request underLease(JobRequest job, Lease lease, Instant at) {
            return new Request(job, some(TaskLink.taskLink(lease, at)));
        }
    }

    /// The source is resolved.
    record Resolved(JobRequest request, Option<TaskLink> lease, PushedSource source) {
        static Resolved resolved(JobRequest request, Option<TaskLink> lease, PushedSource source) {
            return new Resolved(request, lease, source);
        }
    }

    /// The plan passed every refusal.
    record Planned(Resolved resolved, List<UnitSpec> units) {
        static Planned planned(Resolved resolved, List<UnitSpec> units) {
            return new Planned(resolved, units);
        }
    }

    /// Inputs of the data-driven refusals: the active rules and the tree facts their classes need.
    record RefusalInputs(List<RefusalRule> rules, TreeFacts tree) {
        static RefusalInputs refusalInputs(List<RefusalRule> rules, TreeFacts tree) {
            return new RefusalInputs(rules, tree);
        }
    }

    /// The steps and settings the submit needs.
    record Steps(SourceResolver resolver,
                 RefusalStore refusals,
                 TreeReader tree,
                 JobStore jobs,
                 PlanDefaults defaults,
                 DedupKeys dedupKeys,
                 UnitCanceller canceller,
                 Wake wake,
                 Leases leases) {
        public static Steps steps(SourceResolver resolver,
                                  RefusalStore refusals,
                                  TreeReader tree,
                                  JobStore jobs,
                                  PlanDefaults defaults,
                                  DedupKeys dedupKeys,
                                  UnitCanceller canceller,
                                  Wake wake,
                                  Leases leases) {
            return new Steps(resolver, refusals, tree, jobs, defaults, dedupKeys, canceller, wake, leases);
        }
    }

    Promise<Response> execute(Request request);

    /// Whether a lease holds now (m3-design §6.4), `TaskError.LEASE_LOST` otherwise; a read, the store's `holds`.
    @FunctionalInterface
    interface Leases {
        Promise<Unit> holds(Lease lease);
    }

    /// The dedup key of a resolved job (spec §2.3): SHA-256 over `DedupKey.material(...)` with the controller's whole
    /// build version as the runner component (m0-design §3.5, D-6 A1). The hash is the adapter's (`Digests`), so the
    /// wiring binds it.
    @FunctionalInterface
    interface DedupKeys {
        Result<DedupKey> key(String sourceIdentity, Recipe recipe, RecipeOptions options);
    }

    /// What the submit did. (Member order is JBCT lint's: nested records, `execute`, nested interfaces, factory.)
    sealed interface Response {
        /// Accepted (a new job) or deduplicated (an existing one).
        record Submitted(SubmitOutcome outcome) implements Response {
            public static Submitted submitted(SubmitOutcome outcome) {
                return new Submitted(outcome);
            }
        }

        /// Refused; `job` is the persisted REFUSED job.
        record Refused(Refusal refusal, JobId job) implements Response {
            public static Refused refused(Refusal refusal, JobId job) {
                return new Refused(refusal, job);
            }
        }
    }

    static SubmitJob submitJob(Steps steps) {
        return request -> answer(steps, request);
    }

    /// A submit under a lease is refused (`lease-lost`) by a read of the lease before any git, tree or plan work, so a
    /// request presenting a stale or invented lease token costs one indexed read and nothing else. The store still fences
    /// the write itself (the lease can be lost between the check and the insert).
    private static Promise<Response> answer(Steps steps, Request request) {
        return request.lease()
                      .map(link -> heldThenResolved(steps,
                                                    request,
                                                    link.lease()))
                      .or(() -> resolved(steps, request));
    }

    private static Promise<Response> heldThenResolved(Steps steps, Request request, Lease lease) {
        return steps.leases()
                    .holds(lease)
                    .flatMap(_ -> resolved(steps, request));
    }

    private static Promise<Response> resolved(Steps steps, Request request) {
        return steps.resolver()
                    .resolve(request.job().source())
                    .fold(resolution -> afterResolution(steps, request, resolution));
    }

    private static Promise<Response> afterResolution(Steps steps, Request request, Result<PushedSource> resolution) {
        return resolution.fold(cause -> refused(steps, request.job(), request.lease(), none(), cause),
                               source -> planned(steps,
                                                 Resolved.resolved(request.job(), request.lease(), source)));
    }

    /// A failure to gather the inputs (git or the store) is returned as it is; nothing is persisted.
    private static Promise<Response> planned(Steps steps, Resolved resolved) {
        return refusalInputs(steps, resolved).flatMap(inputs -> judged(steps, resolved, inputs));
    }

    private static Promise<Response> judged(Steps steps, Resolved resolved, RefusalInputs inputs) {
        return afterPlanning(steps, resolved, plan(steps, resolved, inputs));
    }

    private static Promise<Response> afterPlanning(Steps steps, Resolved resolved, Result<Planned> planning) {
        return planning.fold(cause -> refused(steps,
                                              resolved.request(),
                                              resolved.lease(),
                                              some(resolved.source()),
                                              cause),
                             planned -> submit(steps, planned));
    }

    // ---- gather, plan and refuse -------------------------------------------------------------------------------
    /// The tree facts follow the rules: only the classes the rules name are read.
    private static Promise<RefusalInputs> refusalInputs(Steps steps, Resolved resolved) {
        return steps.refusals()
                    .active()
                    .mapWith(rules -> steps.tree()
                                           .facts(resolved.source().sha(),
                                                  classNames(rules)),
                             RefusalInputs::refusalInputs);
    }

    private static Result<Planned> plan(Steps steps, Resolved resolved, RefusalInputs inputs) {
        return Planner.plan(resolved.request().recipe(),
                            resolved.request().options(),
                            steps.defaults(),
                            inputs.tree().reactor())
                      .map(units -> Planned.planned(resolved, units))
                      .flatMap(planned -> passedRefusals(planned, inputs));
    }

    private static Result<Planned> passedRefusals(Planned planned, RefusalInputs inputs) {
        return Refusals.checkSubmit(shape(planned),
                                    inputs.rules(),
                                    inputs.tree())
                       .map(_ -> planned);
    }

    private static SubmitShape shape(Planned planned) {
        return SubmitShape.submitShape(planned.resolved().request().envFieldPresent(),
                                       planned.resolved().request().recipe(),
                                       planned.units());
    }

    /// The classes the R-1553-style rules name: only those files are read from the tree.
    private static Set<String> classNames(List<RefusalRule> rules) {
        return rules.stream()
                    .flatMap(SubmitJob::className)
                    .collect(Collectors.toUnmodifiableSet());
    }

    private static Stream<String> className(RefusalRule rule) {
        return switch (rule) {
            case RefusalRule.ExcludeTestClass exclude -> Stream.of(exclude.simpleClassName());
            case RefusalRule.ItTestRequired _ -> Stream.empty();
        };
    }

    // ---- submit ------------------------------------------------------------------------------------------------
    private static Promise<Response> submit(Steps steps, Planned planned) {
        return dedupKey(steps, planned).async()
                       .map(key -> accepted(planned, key))
                       .flatMap(steps.jobs()::submit)
                       .withSuccess(outcome -> afterSubmit(steps, outcome))
                       .map(Response.Submitted::submitted);
    }

    private static Result<DedupKey> dedupKey(Steps steps, Planned planned) {
        var request = planned.resolved().request();

        return steps.dedupKeys()
                    .key(planned.resolved().source().identity(),
                         request.recipe(),
                         request.options());
    }

    private static NewJob accepted(Planned planned, DedupKey key) {
        var request = planned.resolved().request();

        return leased(NewJob.newJob(request.submittedBy(),
                                    request.priority(),
                                    request.keep(),
                                    request.fresh(),
                                    request.source(),
                                    some(planned.resolved().source()),
                                    request.recipe(),
                                    some(key),
                                    planned.units())
                            .dedicated(request.dedicatedCpu()),
                      planned.resolved().lease());
    }

    private static NewJob leased(NewJob job, Option<TaskLink> lease) {
        return lease.map(link -> job.underLease(link.lease(),
                                                link.at()))
                    .or(job);
    }

    /// Superseded RUNNING jobs' units are CANCELLING now; their `CancelUnit` is fire-and-forget (reconcile re-sends).
    private static Unit afterSubmit(Steps steps, SubmitOutcome outcome) {
        return switch (outcome) {
            case SubmitOutcome.Accepted accepted -> cancelAndWake(steps, accepted);
            case SubmitOutcome.Deduplicated _ -> unit();
        };
    }

    private static Unit cancelAndWake(Steps steps, SubmitOutcome.Accepted accepted) {
        steps.canceller().cancel(accepted.toCancel());

        return steps.wake()
                    .signal();
    }

    // ---- refusal -----------------------------------------------------------------------------------------------
    private static Promise<Response> refused(Steps steps,
                                             JobRequest job,
                                             Option<TaskLink> lease,
                                             Option<PushedSource> source,
                                             Cause cause) {
        return switch (cause) {
            case Refusal refusal -> steps.jobs().recordRefused(refusedJob(job, lease, source), refusal).map(id -> Response.Refused.refused(refusal,
                                                                                                                                           id));
            default -> cause.promise();
        };
    }

    private static NewJob refusedJob(JobRequest job, Option<TaskLink> lease, Option<PushedSource> source) {
        return leased(NewJob.newJob(job.submittedBy(),
                                    job.priority(),
                                    job.keep(),
                                    job.fresh(),
                                    job.source(),
                                    source,
                                    job.recipe(),
                                    none(),
                                    List.of())
                            .dedicated(job.dedicatedCpu()),
                      lease);
    }
}
