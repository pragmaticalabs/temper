package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.Audit;
import org.pragmatica.temper.domain.task.Counters;
import org.pragmatica.temper.domain.task.LeaseState;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.Status;
import org.pragmatica.temper.domain.task.TaskBrief;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskState;
import org.pragmatica.temper.domain.task.Waiting;
import org.pragmatica.temper.store.api.TaskReport;
import org.pragmatica.temper.store.api.TaskRow;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// `hbAge` and `stale` (lane registry): stale is STRICTLY more than the limit since the last heartbeat or claim of a
/// LEASED task. Mutation: change `age > staleAfter` to `>=` → the exactly-at-the-limit case is red; drop the LEASED
/// condition in `hbAge` → the READY case shows an age.
class TaskViewsTest {
    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");
    private static final Duration LIMIT = Duration.ofMinutes(20);

    private static TaskRow row(Status status) {
        return TaskRow.taskRow(TaskId.taskId(7).unwrap(),
                               "s",
                               "t",
                               List.of(),
                               TaskBrief.taskBrief("b.md", none(), false, java.util.Map.of()).unwrap(),
                               "a/b",
                               Ref.ref("main").unwrap(),
                               Ref.ref("agent/s").unwrap(),
                               false,
                               0,
                               List.of(),
                               OwnerTag.ownerTag("cto").unwrap(),
                               T0,
                               TaskState.taskState(Stage.CODE,
                                                   status,
                                                   none(),
                                                   Counters.counters(0, 3, 0, 1, 0, 0),
                                                   LeaseState.NONE,
                                                   none(),
                                                   none(),
                                                   none(),
                                                   Waiting.NOTHING,
                                                   Audit.NONE,
                                                   none()));
    }

    private static TaskReport heartbeatAt(Instant at) {
        return new TaskReport(some(at), none(), none());
    }

    @Test
    void stale_flipsStrictlyAfterTheLimit() {
        var heartbeat = heartbeatAt(T0);
        var leased = row(Status.LEASED);
        var atLimit = TaskViews.taskView(leased, none(), heartbeat, T0.plus(LIMIT), LIMIT);
        var oneSecondOver = TaskViews.taskView(leased,
                                               none(),
                                               heartbeat,
                                               T0.plus(LIMIT).plusSeconds(1),
                                               LIMIT);
        var fresh = TaskViews.taskView(leased, none(), heartbeat, T0.plusSeconds(30), LIMIT);

        assertThat(atLimit.hbAge().unwrap()).isEqualTo(1200L);
        assertThat(atLimit.stale()).as("exactly N minutes is not yet stale").isFalse();
        assertThat(oneSecondOver.stale()).as("N minutes and a second is").isTrue();
        assertThat(fresh.stale()).isFalse();
        assertThat(fresh.hbAge().unwrap()).isEqualTo(30L);
    }

    @Test
    void stale_followsTheConfiguredLimit() {
        var view = TaskViews.taskView(row(Status.LEASED),
                                      none(),
                                      heartbeatAt(T0),
                                      T0.plus(Duration.ofMinutes(25)),
                                      Duration.ofMinutes(30));

        assertThat(view.stale()).isFalse();
    }

    @Test
    void aTaskThatIsNotLeased_showsNoAge_andIsNeverStale() {
        var view = TaskViews.taskView(row(Status.READY),
                                      none(),
                                      heartbeatAt(T0),
                                      T0.plus(Duration.ofDays(2)),
                                      LIMIT);

        assertThat(view.hbAge().isEmpty()).isTrue();
        assertThat(view.stale()).isFalse();
    }

    @Test
    void aLeasedTaskWithoutAReportRow_showsNoAge_andIsNotReportedStale() {
        var view = TaskViews.taskView(row(Status.LEASED),
                                      none(),
                                      TaskReport.NONE,
                                      T0.plus(Duration.ofDays(2)),
                                      LIMIT);

        assertThat(view.hbAge().isEmpty()).isTrue();
        assertThat(view.stale()).isFalse();
    }

    @Test
    void reportAndObservation_areShownAsStored() {
        var report = new TaskReport(some(T0),
                                    some(new TaskReport.Reported("a".repeat(40), 2, 1, 4, T0)),
                                    some(new TaskReport.Observed(150, 140, T0)));
        var view = TaskViews.taskView(row(Status.LEASED), none(), report, T0, LIMIT);

        assertThat(view.report().unwrap().dirty()).isEqualTo(2);
        assertThat(view.report().unwrap().epoch()).isEqualTo(4);
        assertThat(view.observed().unwrap().ctxK()).isEqualTo(150);
        assertThat(view.observed().unwrap().turns()).isEqualTo(140);
    }
}
