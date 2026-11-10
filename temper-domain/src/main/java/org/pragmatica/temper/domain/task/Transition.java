package org.pragmatica.temper.domain.task;

import java.util.List;

import org.pragmatica.lang.Option;


/// What [TaskMachine#apply] decided: the new state, the events to append in order, and the NEEDS-RULING request to
/// store when the transition parks the task (T8, T7, T9′, T10, T12).
public record Transition(TaskState state, List<TaskEvent> events, Option<ParkRequest> parked) {
    public Transition {
        events = List.copyOf(events);
    }

    public static Transition transition(TaskState state, List<TaskEvent> events, Option<ParkRequest> parked) {
        return new Transition(state, events, parked);
    }
}
