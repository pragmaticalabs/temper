package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.task.Audit;
import org.pragmatica.temper.domain.task.Counters;
import org.pragmatica.temper.domain.task.LeaseState;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.Status;
import org.pragmatica.temper.domain.task.TaskBrief;
import org.pragmatica.temper.domain.task.TaskState;
import org.pragmatica.temper.domain.task.Waiting;

import static org.pragmatica.lang.Option.none;


/// The jsonb forms of the task tables (m3-design §5.3): `tasks.brief` and the `task_events.payload`, which is the event's
/// facts plus the after-image of the task's projected state. Jackson constructs these; nothing else does.
sealed interface TaskJson {
    /// `tasks.brief`: `{path, sha?, design, templates}`.
    record BriefJson(String path, Option<String> sha, boolean design, Map<String, String> templates) {
        static BriefJson briefJson(TaskBrief brief) {
            return new BriefJson(brief.path(),
                                 brief.sha().map(Sha::value),
                                 brief.design(),
                                 brief.templates());
        }

        Result<TaskBrief> toDomain() {
            return TaskBrief.taskBrief(path,
                                       sha,
                                       design,
                                       Option.option(templates).or(Map.of()));
        }
    }

    /// `task_events.payload`: `{facts, state}`.
    record PayloadJson(Map<String, String> facts, StateImage state) {}

    /// Every projected column of `tasks` except `lease_hash` (a credential digest stays out of the readable log):
    /// folding the events of a task gives its row with the hash left out.
    record StateImage(String stage,
                      String status,
                      Option<String> raisedFrom,
                      int round,
                      int roundCap,
                      int ciRounds,
                      int visit,
                      int visitExpiries,
                      int visitFailures,
                      long epoch,
                      Option<String> holder,
                      Option<Instant> leaseUntil,
                      Option<Instant> stepDeadline,
                      Option<String> readPoint,
                      Option<String> head,
                      Option<Integer> pr,
                      Option<Instant> readySince,
                      List<Long> waitJobs,
                      boolean waitCi,
                      String audit,
                      Option<Instant> closedAt) {
        static StateImage stateImage(TaskState state) {
            var counters = state.counters();
            var lease = state.lease();

            return new StateImage(state.stage().name(),
                                  state.status().name(),
                                  state.raisedFrom().map(Stage::name),
                                  counters.round(),
                                  counters.roundCap(),
                                  counters.ciRounds(),
                                  counters.visit(),
                                  counters.visitExpiries(),
                                  counters.visitFailures(),
                                  lease.epoch(),
                                  lease.holder(),
                                  lease.until(),
                                  lease.stepDeadline(),
                                  lease.readPoint().map(Sha::value),
                                  state.head().map(Sha::value),
                                  state.pr(),
                                  state.readySince(),
                                  state.waiting().jobs().stream().map(JobId::value).toList(),
                                  state.waiting().ci(),
                                  state.audit().wire(),
                                  state.closedAt());
        }

        Result<TaskState> toState() {
            return Result.all(Stage.stage(stage),
                              Status.status(status),
                              Values.optional(raisedFrom, Stage::stage),
                              Values.optional(readPoint, Sha::sha),
                              Values.optional(head, Sha::sha),
                              Result.allOf(waitJobs.stream().map(JobId::jobId).toList()),
                              Audit.audit(audit))
                         .map((parsedStage, parsedStatus, from, point, parsedHead, jobs, parsedAudit) -> TaskState.taskState(parsedStage,
                                                                                                                             parsedStatus,
                                                                                                                             from,
                                                                                                                             Counters.counters(round,
                                                                                                                                               roundCap,
                                                                                                                                               ciRounds,
                                                                                                                                               visit,
                                                                                                                                               visitExpiries,
                                                                                                                                               visitFailures),
                                                                                                                             LeaseState.leaseState(epoch,
                                                                                                                                                   none(),
                                                                                                                                                   holder,
                                                                                                                                                   leaseUntil,
                                                                                                                                                   stepDeadline,
                                                                                                                                                   point),
                                                                                                                             parsedHead,
                                                                                                                             pr,
                                                                                                                             readySince,
                                                                                                                             Waiting.waiting(jobs,
                                                                                                                                             waitCi),
                                                                                                                             parsedAudit,
                                                                                                                             closedAt));
        }
    }

    record unused() implements TaskJson {}
}
