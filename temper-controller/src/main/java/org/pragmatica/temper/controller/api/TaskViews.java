package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.task.ClaimTask;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.LeaseTerms;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.Status;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskState;
import org.pragmatica.temper.protocol.api.CheckpointDto;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.ObservedDto;
import org.pragmatica.temper.protocol.api.ParkRequestDto;
import org.pragmatica.temper.protocol.api.TaskBriefDto;
import org.pragmatica.temper.protocol.api.TaskEventDto;
import org.pragmatica.temper.protocol.api.TaskView;
import org.pragmatica.temper.protocol.api.TreeReportDto;
import org.pragmatica.temper.store.api.Checkpoint;
import org.pragmatica.temper.store.api.TaskDetail;
import org.pragmatica.temper.store.api.TaskEventRow;
import org.pragmatica.temper.store.api.TaskReport;
import org.pragmatica.temper.store.api.TaskRow;

import static org.pragmatica.lang.Option.none;


/// The task store's rows in their wire form (m3-design §11.1): [TaskView], [TaskEventDto], [ClaimResponse].
sealed interface TaskViews {
    /// `hbAge` and `stale` only for a LEASED task that has a heartbeat clock (a claim or heartbeat since V20); stale is
    /// strictly more than `staleAfter` without one.
    static TaskView taskView(TaskRow row,
                             Option<TaskDetail> detail,
                             TaskReport report,
                             Instant now,
                             Duration staleAfter) {
        var state = row.state();
        var counters = state.counters();
        var lease = state.lease();

        return new TaskView(row.id().value(),
                            row.slug(),
                            row.title(),
                            row.issues(),
                            TaskBriefDto.taskBriefDto(row.brief()),
                            row.repo(),
                            row.baseRef().value(),
                            row.branch().value(),
                            state.pr(),
                            row.critical(),
                            row.priority(),
                            row.after().stream().map(TaskId::value).toList(),
                            state.stage().name(),
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
                            state.readySince(),
                            state.waiting().jobs().stream().map(JobId::value).toList(),
                            state.waiting().ci(),
                            row.createdBy().value(),
                            row.createdAt(),
                            state.closedAt(),
                            detail.map(TaskViews::detailOf),
                            hbAge(row, report, now),
                            hbAge(row, report, now).filter(age -> age > staleAfter.toSeconds()).isPresent(),
                            report.reported().map(TaskViews::treeReport),
                            report.observed()
                                  .map(observed -> new ObservedDto(observed.ctxK(),
                                                                   observed.turns(),
                                                                   observed.at())));
    }

    /// The clock is the report's `lastHbAt`; a LEASED task with no report row (every lease granted before V20) falls back
    /// to the lease's own TTL clock: `until` minus the stage's TTL, which is the last accepted lease-token call (a claim,
    /// a heartbeat or a linked submit) as the lease recorded it. That is not the last heartbeat strictly (a submit also
    /// restarts it), and the controller-start extension moves it forward; both only make a lane look fresher, never staler.
    private static Option<Long> hbAge(TaskRow row, TaskReport report, Instant now) {
        return row.state()
                  .status() == Status.LEASED
               ? report.lastHbAt()
                       .orElse(() -> leaseClock(row))
                       .map(at -> Math.max(0,
                                           Duration.between(at, now).toSeconds()))
               : none();
    }

    private static Option<Instant> leaseClock(TaskRow row) {
        var ttl = LeaseTerms.forStage(row.state().stage()).ttl();

        return row.state()
                  .lease()
                  .until()
                  .map(until -> until.minus(ttl));
    }

    private static TreeReportDto treeReport(TaskReport.Reported reported) {
        return new TreeReportDto(reported.head(), reported.dirty(), reported.unpushed(), reported.epoch(), reported.at());
    }

    static TaskView.Detail detailOf(TaskDetail detail) {
        return new TaskView.Detail(items(detail),
                                   detail.rulings().stream().map(TaskViews::rulingDto).toList(),
                                   detail.evidence(),
                                   jobs(detail));
    }

    static TaskEventDto taskEventDto(TaskEventRow row) {
        var state = row.state();

        return new TaskEventDto(row.seq(),
                                row.at(),
                                row.event().kind().wire(),
                                state.stage().name(),
                                state.status().name(),
                                state.counters().visit(),
                                state.epoch(),
                                row.actor(),
                                row.event().facts());
    }

    /// The rulings a claim returns are the ones given (§5.5: "the task's applicable rulings").
    static ClaimResponse claimResponse(ClaimTask.Claimed claimed) {
        var row = claimed.row();
        var state = row.state();
        var detail = claimed.detail();

        return new ClaimResponse(row.id().value(),
                                 row.slug(),
                                 state.stage().name(),
                                 state.counters().visit(),
                                 state.epoch(),
                                 claimed.token(),
                                 readPoint(state),
                                 state.counters().round(),
                                 state.lease().until().or(row.createdAt()),
                                 state.lease().stepDeadline().or(row.createdAt()),
                                 row.repo(),
                                 claimed.cloneUrl(),
                                 items(detail),
                                 detail.rulings()
                                       .stream()
                                       .filter(ruling -> ruling.ruling()
                                                               .isPresent())
                                       .map(TaskViews::rulingDto)
                                       .toList(),
                                 detail.evidence(),
                                 jobs(detail),
                                 claimed.checkpoint().map(TaskViews::checkpointDto).toOptional().orElse(null),
                                 claimed.warnings());
    }

    static CheckpointDto checkpointDto(Checkpoint checkpoint) {
        return new CheckpointDto(checkpoint.seq(),
                                 checkpoint.epoch(),
                                 checkpoint.pushedSha(),
                                 checkpoint.checklist()
                                           .stream()
                                           .map(item -> new CheckpointDto.Item(item.item(),
                                                                               item.done()))
                                           .toList(),
                                 checkpoint.readMap()
                                           .stream()
                                           .map(entry -> new CheckpointDto.Entry(entry.path(),
                                                                                 entry.lines()))
                                           .toList(),
                                 checkpoint.note(),
                                 checkpoint.at());
    }

    /// A LEASED task always has its issued read point; the empty text only keeps the function total.
    private static String readPoint(TaskState state) {
        return state.lease()
                    .readPoint()
                    .map(Sha::value)
                    .or("");
    }

    private static List<TaskView.Item> items(TaskDetail detail) {
        return detail.items()
                     .stream()
                     .map(item -> new TaskView.Item(item.item(),
                                                    item.raisedRound(),
                                                    item.status()))
                     .toList();
    }

    private static List<TaskView.LinkedJob> jobs(TaskDetail detail) {
        return detail.jobs()
                     .stream()
                     .map(job -> new TaskView.LinkedJob(job.job().value(),
                                                        job.epoch(),
                                                        job.state()))
                     .toList();
    }

    private static TaskView.RulingDto rulingDto(TaskDetail.Ruling ruling) {
        var given = ruling.ruling();

        return new TaskView.RulingDto(ruling.id(),
                                      ruling.raisedFrom().name(),
                                      ruling.raisedVisit(),
                                      ruling.kind().wire(),
                                      ParkRequestDto.parkRequestDto(ruling.request()),
                                      ruling.raisedAt(),
                                      ruling.raisedBy(),
                                      given.map(TaskDetail.Given::at),
                                      given.map(TaskDetail.Given::by),
                                      given.map(TaskDetail.Given::option),
                                      given.map(TaskDetail.Given::text),
                                      given.flatMap(TaskDetail.Given::know).map(Sha::value));
    }

    record unused() implements TaskViews {}
}
