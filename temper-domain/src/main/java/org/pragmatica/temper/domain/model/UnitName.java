package org.pragmatica.temper.domain.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;


/// Unit name within a job; lowercase so that it is valid inside docker container and network names.
public record UnitName(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");
    /// The single unit of an `Mvn` or `ModuleTest` job.
    public static final UnitName MAIN = new UnitName("main");
    /// The single unit of an unsharded Gate (m1-design §1.1): `gate` keeps `units.name` meaningful once M3
    /// adds the `build`/`rest`/`ember-k` units.
    public static final UnitName GATE = new UnitName("gate");
    /// The one unit of a rerun job (ruling R-M2-16): every target class runs in it, since the units of a job share the
    /// job's workspace and the worker takes a unit's reports by time, so units of one job side by side would report each
    /// other's classes.
    public static final UnitName RERUN = new UnitName("rerun");

    public static Result<UnitName> unitName(String raw) {
        return Values.matching(raw, PATTERN, "unit name").map(UnitName::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
