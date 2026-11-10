package org.pragmatica.temper.domain.manifest;

/// One expected test class: the `<testcase classname>` the report uses (the binary name, `Outer$Inner` for a
/// `@Nested` class, or the class's own `@DisplayName` value, by the plugin version: [ManifestRules], [ReportNaming]), the
/// workspace-relative directory of its module (`.` for the root project) and the plugin that runs it.
public record ManifestEntry(String module, String className, Runner runner) {
    public static ManifestEntry manifestEntry(String module, String className, Runner runner) {
        return new ManifestEntry(module, className, runner);
    }
}
