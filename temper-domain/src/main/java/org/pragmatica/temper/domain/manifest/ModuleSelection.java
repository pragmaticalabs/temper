package org.pragmatica.temper.domain.manifest;

/// One reached test-plugin execution of a module, with its resolved parameters and the way its plugin version names
/// a class in the report ([ReportNaming]). A module has one selection per reached execution; the manifest is their
/// union.
public record ModuleSelection(PomModule module, Runner runner, TestParams params, ReportNaming naming) {
    public static ModuleSelection moduleSelection(PomModule module,
                                                  Runner runner,
                                                  TestParams params,
                                                  ReportNaming naming) {
        return new ModuleSelection(module, runner, params, naming);
    }
}
