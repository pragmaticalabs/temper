package org.pragmatica.temper.controller.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.task.ClaimTask;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskState;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.ParkRequestDto;
import org.pragmatica.temper.protocol.api.TaskBriefDto;
import org.pragmatica.temper.protocol.api.TaskEventDto;
import org.pragmatica.temper.protocol.api.TaskView;
import org.pragmatica.temper.store.api.TaskDetail;
import org.pragmatica.temper.store.api.TaskEventRow;
import org.pragmatica.temper.store.api.TaskRow;


/// The task store's rows in their wire form (m3-design §11.1): [TaskView], [TaskEventDto], [ClaimResponse].
sealed interface TaskViews {
    static TaskView taskView(TaskRow row, Option<TaskDetail> detail) {
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
                            detail.map(TaskViews::detailOf));
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
                                 jobs(detail));
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
