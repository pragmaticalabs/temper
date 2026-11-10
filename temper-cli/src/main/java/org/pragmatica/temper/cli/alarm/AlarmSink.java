package org.pragmatica.temper.cli.alarm;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Promise;


/// Where `alarm-check` delivers the conditions that have a state (config `alarm.sink`). Each comes back with what
/// the sink did about it. A sink that cannot deliver fails with a [org.pragmatica.temper.cli.CliError] of exit 2.
@FunctionalInterface
public interface AlarmSink {
    Promise<List<Delivered>> deliver(Instant now, List<Condition> conditions);

    /// `stdout`: delivers nowhere. The command prints every condition it is handed back, which is the whole dry run.
    static AlarmSink stdout() {
        return (_, conditions) -> Promise.success(printed(conditions));
    }

    private static List<Delivered> printed(List<Condition> conditions) {
        return conditions.stream()
                         .map(condition -> Delivered.delivered(condition, "printed"))
                         .toList();
    }
}
