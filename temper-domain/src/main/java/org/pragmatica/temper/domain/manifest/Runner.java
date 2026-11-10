package org.pragmatica.temper.domain.manifest;

/// The test plugin an execution belongs to (spec §5.2): surefire runs unit tests in `test`, failsafe runs
/// integration tests in `integration-test`.
public enum Runner {
    SUREFIRE("maven-surefire-plugin", "test", "test"),
    FAILSAFE("maven-failsafe-plugin", "integration-test", "it.test");
    private final String artifactId;
    private final String goal;
    private final String selectionProperty;
    Runner(String artifactId, String goal, String selectionProperty) {
        this.artifactId = artifactId;
        this.goal = goal;
        this.selectionProperty = selectionProperty;
    }
    /// The plugin's artifactId (`org.apache.maven.plugins` group).
    public String artifactId() {
        return artifactId;
    }
    /// The goal that runs tests; its default phase has the same name.
    public String goal() {
        return goal;
    }
    /// The user property behind the plugin's `test` parameter: `test` for surefire, `it.test` for failsafe.
    public String selectionProperty() {
        return selectionProperty;
    }
}
