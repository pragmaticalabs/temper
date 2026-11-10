package org.pragmatica.temper.cli.alarm;

/// One condition's state at this check: firing, with what was seen, or clear. A condition that could not be
/// evaluated has no state at all (it is not a [Condition]), so no sink acts on it.
public sealed interface Condition {
    AlarmCode code();

    record Firing(AlarmCode code, String detail) implements Condition {}

    record Clear(AlarmCode code) implements Condition {}

    static Condition firing(AlarmCode code, String detail) {
        return new Firing(code, detail);
    }

    static Condition clear(AlarmCode code) {
        return new Clear(code);
    }
}
