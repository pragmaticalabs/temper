package org.pragmatica.temper.domain.manifest;

import java.util.List;
import java.util.Map;


/// One project of the effective POM. Every directory is workspace-relative, normalised, and never escapes
/// the workspace; `path` is the project's base directory (`.` for the workspace root).
///
/// The domain layer holds no file-system types (JBCT-MIX-01), so directories are strings; the worker
/// resolves them against its workspace.
public record PomModule(String id,
                        String path,
                        String packaging,
                        String testOutputDirectory,
                        String outputDirectory,
                        String testSourceDirectory,
                        Map<String, String> properties,
                        List<PluginUse> testPlugins) {
    public PomModule {
        properties = Map.copyOf(properties);
        testPlugins = List.copyOf(testPlugins);
    }

    public static PomModule pomModule(String id,
                                      String path,
                                      String packaging,
                                      String testOutputDirectory,
                                      String outputDirectory,
                                      String testSourceDirectory,
                                      Map<String, String> properties,
                                      List<PluginUse> testPlugins) {
        return new PomModule(id,
                             path,
                             packaging,
                             testOutputDirectory,
                             outputDirectory,
                             testSourceDirectory,
                             properties,
                             testPlugins);
    }
}
