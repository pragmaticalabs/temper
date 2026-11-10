package org.pragmatica.temper.domain.refusal;

import org.pragmatica.temper.domain.mvn.MvnStep;


/// The test plugin that runs a class, and the system property that narrows that plugin's selection.
/// Failsafe ignores `-Dtest`; surefire ignores `-Dit.test`.
public enum TestRunner {
    SUREFIRE("test"),
    FAILSAFE("it.test");
    private final String selectionProperty;
    TestRunner(String selectionProperty) {
        this.selectionProperty = selectionProperty;
    }
    /// `test` for surefire, `it.test` for failsafe.
    public String selectionProperty() {
        return selectionProperty;
    }
    /// Whether this plugin runs in `step`.
    public boolean runsIn(MvnStep step) {
        return switch (this) {
            case SUREFIRE -> step.runsSurefire();
            case FAILSAFE -> step.runsFailsafe();
        };
    }
}
