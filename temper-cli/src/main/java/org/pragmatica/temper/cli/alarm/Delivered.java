package org.pragmatica.temper.cli.alarm;

/// A condition as a sink delivered it, with what the sink did (`#12 opened`, `#12 closed`, `printed`).
public record Delivered(Condition condition, String action) {
    public static Delivered delivered(Condition condition, String action) {
        return new Delivered(condition, action);
    }
}
