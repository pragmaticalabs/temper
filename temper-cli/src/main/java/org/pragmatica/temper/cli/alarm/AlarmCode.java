package org.pragmatica.temper.cli.alarm;

import java.util.Arrays;

import org.pragmatica.lang.Option;


/// The conditions `alarm-check` evaluates. `wire()` is the stable code that names a condition's issue and its output
/// line. `check-blind` is the alarm's own: it fires when any other condition could not be evaluated.
public enum AlarmCode {
    CONTROLLER_DOWN("controller-down"),
    DUMP_FAILED("dump-failed"),
    DUMP_STALE("dump-stale"),
    DISK_LOW("disk-low"),
    WORKER_LOST("worker-lost"),
    HOST_ALARMS("host-alarms"),
    DUMP_FILE_STALE("dump-file-stale"),
    CHECK_BLIND("check-blind");
    private final String wire;
    AlarmCode(String wire) {
        this.wire = wire;
    }
    public String wire() {
        return wire;
    }
    /// The code named `wire`, if any.
    public static Option<AlarmCode> alarmCode(String wire) {
        return Option.from(Arrays.stream(values()).filter(code -> code.wire.equals(wire)).findFirst());
    }
}
