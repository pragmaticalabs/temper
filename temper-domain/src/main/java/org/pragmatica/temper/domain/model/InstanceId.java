package org.pragmatica.temper.domain.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;


/// The controller instance a unit container belongs to: its `temper.instance` label (M1 WP7 verification, F2).
/// Reconcile ignores a container labelled for another instance, so a controller sharing a host with another one
/// never kills its units as orphans. A docker label value, so a short, plain alphabet; the cap is a guess.
public record InstanceId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    /// The live controller's instance unless `[server] instance` names another one.
    public static final InstanceId DEFAULT = new InstanceId("temper");

    public static Result<InstanceId> instanceId(String raw) {
        return Values.matching(raw, PATTERN, "instance").map(InstanceId::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
