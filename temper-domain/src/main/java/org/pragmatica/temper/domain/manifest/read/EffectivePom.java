package org.pragmatica.temper.domain.manifest.read;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.manifest.ConfigValue;
import org.pragmatica.temper.domain.manifest.Execution;
import org.pragmatica.temper.domain.manifest.ManifestError;
import org.pragmatica.temper.domain.manifest.PluginUse;
import org.pragmatica.temper.domain.manifest.PomModule;
import org.pragmatica.temper.domain.manifest.Runner;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Parser of `help:effective-pom` output (m1-design §2.4). This package is the manifest engine's adapter
/// zone (`jbct.toml`): XML parsing can only fail by exception, so it is lifted here, and directories are
/// checked with the JDK path type; the rules themselves stay in the pure `manifest` package.
///
/// - The document is a `<projects>` wrapper (a reactor) or a single `<project>`.
/// - Per project: `groupId:artifactId` (the group may come from `<parent>`), `<packaging>` (default `jar`),
///   `<properties>`, and the `<build>` directories. The base directory is the parent of `<build><directory>`,
///   which must end in `/target`; anything else is [ManifestError.MissingElement] rather than a guess.
/// - Every directory must be absolute and lie under `workspace` once normalised, else
///   [ManifestError.OutsideWorkspace]; it is returned workspace-relative (`.` for the workspace itself).
/// - Only `<build><plugins>` is read (not `<pluginManagement>`, `<profiles>` or `<reporting>`), and only
///   surefire and failsafe in group `org.apache.maven.plugins` (the effective POM omits that default group), each with
///   its `<version>`: the effective POM resolves it from `<pluginManagement>` or the lifecycle's default binding.
/// - An execution's configuration is the plugin's merged with its own, the execution winning per parameter.
///   A configuration element with child elements is [ConfigValue.Items] (their trimmed texts), else
///   [ConfigValue.Text].
public sealed interface EffectivePom {
    String PLUGIN_GROUP = "org.apache.maven.plugins";

    static Result<List<PomModule>> parse(byte[] xml, String workspace) {
        return path(workspace).flatMap(root -> parse(xml, root));
    }

    private static Result<List<PomModule>> parse(byte[] xml, Path workspace) {
        return Result.lift(EffectivePom::malformed,
                           () -> document(factory().createXMLEventReader(new ByteArrayInputStream(xml))))
                     .flatMap(document -> modules(document, workspace));
    }

    private static ManifestError malformed(Throwable thrown) {
        return ManifestError.MalformedPom.FACTORY.apply(Causes.fromThrowable(thrown));
    }

    /// `Path.of` rejects some strings (a NUL, which XML 1.0 cannot carry anyway) by exception.
    private static Result<Path> path(String raw) {
        return Result.lift(EffectivePom::malformed,
                           () -> Path.of(raw).normalize());
    }

    /// Feeds every event to a fresh tree. `XMLEventReader` iteration reports XML errors as unchecked
    /// exceptions, which the caller's `lift` turns into `MalformedPom`. The reader reads an in-memory array,
    /// so it holds no resource that needs closing.
    private static XmlNode document(XMLEventReader events) {
        var tree = new XmlTree();

        events.forEachRemaining(tree::accept);

        return tree.document();
    }

    /// Namespace-unaware, DTDs and external entities off (XXE closed), as the report parser.
    private static XMLInputFactory factory() {
        var factory = XMLInputFactory.newDefaultFactory();

        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        return factory;
    }

    private static Result<List<PomModule>> modules(XmlNode document, Path workspace) {
        return Result.firstFailureOf(projects(document).stream().map(project -> module(project, workspace)).toList());
    }

    private static List<XmlNode> projects(XmlNode document) {
        return document.name()
                       .equals("projects")
               ? document.children("project")
               : List.of(document);
    }

    private static Result<PomModule> module(XmlNode project, Path workspace) {
        return id(project).flatMap(id -> module(id, project, workspace));
    }

    private static Result<PomModule> module(String id, XmlNode project, Path workspace) {
        return required(id, project, "build").flatMap(build -> module(id, project, build, workspace));
    }

    private static Result<PomModule> module(String id, XmlNode project, XmlNode build, Path workspace) {
        return directories(id, build, workspace).map(directories -> PomModule.pomModule(id,
                                                                                        directories.base(),
                                                                                        packaging(project),
                                                                                        directories.sources()
                                                                                                   .outputs()
                                                                                                   .testOutput(),
                                                                                        directories.sources()
                                                                                                   .outputs()
                                                                                                   .output(),
                                                                                        directories.sources()
                                                                                                   .testSource(),
                                                                                        properties(project),
                                                                                        testPlugins(build)));
    }

    /// The two output directories of a project.
    record Outputs(String testOutput, String output) {
        static Outputs outputs(String testOutput, String output) {
            return new Outputs(testOutput, output);
        }
    }

    /// The outputs plus the test source directory.
    record Sources(Outputs outputs, String testSource) {
        static Sources sources(Outputs outputs, String testSource) {
            return new Sources(outputs, testSource);
        }
    }

    /// Every directory of a project, plus its base directory.
    record Directories(Sources sources, String base) {
        static Directories directories(Sources sources, String base) {
            return new Directories(sources, base);
        }
    }

    /// One directory at a time, so a refused project reports its first offending directory, unwrapped.
    private static Result<Directories> directories(String id, XmlNode build, Path workspace) {
        return directory(id, build, "testOutputDirectory", workspace).mapWith(_ -> directory(id,
                                                                                             build,
                                                                                             "outputDirectory",
                                                                                             workspace),
                                                                              Outputs::outputs)
                        .mapWith(_ -> directory(id, build, "testSourceDirectory", workspace),
                                 Sources::sources)
                        .mapWith(_ -> baseDirectory(id, build, workspace),
                                 Directories::directories);
    }

    private static String packaging(XmlNode project) {
        return project.text("packaging")
                      .or("jar");
    }

    private static Result<String> id(XmlNode project) {
        return project.text("artifactId")
                      .map(artifactId -> groupId(project) + ":" + artifactId)
                      .toResult(missing(project.text("groupId").or("?"),
                                        "artifactId"));
    }

    private static String groupId(XmlNode project) {
        return project.text("groupId")
                      .orElse(() -> parentGroupId(project))
                      .or("");
    }

    private static Option<String> parentGroupId(XmlNode project) {
        return project.child("parent")
                      .flatMap(parent -> parent.text("groupId"));
    }

    private static Result<XmlNode> required(String id, XmlNode node, String name) {
        return node.child(name)
                   .toResult(missing(id, name));
    }

    private static ManifestError missing(String id, String element) {
        return ManifestError.MissingElement.FACTORY.apply(id, element);
    }

    /// The project base directory: the parent of a build directory named `target`.
    private static Result<String> baseDirectory(String id, XmlNode build, Path workspace) {
        return directory(id, build, "directory", workspace).flatMap(target -> parentOfTarget(id, target));
    }

    private static Result<String> parentOfTarget(String id, String target) {
        if (target.equals("target")) {
            return success(".");
        }

        return target.endsWith("/target")
               ? success(target.substring(0,
                                          target.length() - "/target".length()))
               : missing(id, "build/directory ending in /target").result();
    }

    private static Result<String> directory(String id, XmlNode build, String name, Path workspace) {
        return build.text(name)
                    .toResult(missing(id, "build/" + name))
                    .flatMap(raw -> underWorkspace(raw, workspace));
    }

    private static Result<String> underWorkspace(String raw, Path workspace) {
        return path(raw).flatMap(path -> inside(raw, path, workspace));
    }

    private static Result<String> inside(String raw, Path path, Path workspace) {
        return path.isAbsolute() && path.startsWith(workspace)
               ? success(relative(workspace, path))
               : ManifestError.OutsideWorkspace.FACTORY.apply(raw).result();
    }

    private static String relative(Path workspace, Path path) {
        var relative = workspace.relativize(path).toString();

        return relative.isEmpty()
               ? "."
               : relative;
    }

    private static Map<String, String> properties(XmlNode project) {
        return project.childrenOf("properties")
                      .stream()
                      .collect(Collectors.toMap(XmlNode::name,
                                                XmlNode::trimmed,
                                                (_, last) -> last));
    }

    private static List<PluginUse> testPlugins(XmlNode build) {
        return build.children("plugins", "plugin")
                    .stream()
                    .flatMap(plugin -> pluginUse(plugin).stream())
                    .toList();
    }

    private static Option<PluginUse> pluginUse(XmlNode plugin) {
        return runner(plugin).map(runner -> PluginUse.pluginUse(runner, plugin.text("version"), executions(plugin)));
    }

    private static Option<Runner> runner(XmlNode plugin) {
        return plugin.text("groupId")
                     .or(PLUGIN_GROUP)
                     .equals(PLUGIN_GROUP)
               ? plugin.text("artifactId")
                       .flatMap(EffectivePom::runnerOf)
               : none();
    }

    private static Option<Runner> runnerOf(String artifactId) {
        return Option.from(Stream.of(Runner.values())
                                 .filter(runner -> runner.artifactId()
                                                         .equals(artifactId))
                                 .findFirst());
    }

    private static List<Execution> executions(XmlNode plugin) {
        var pluginConfig = configuration(plugin);

        return plugin.children("executions", "execution")
                     .stream()
                     .map(execution -> execution(execution, pluginConfig))
                     .toList();
    }

    private static Execution execution(XmlNode execution, Map<String, ConfigValue> pluginConfig) {
        return Execution.execution(execution.text("id").or("default"),
                                   execution.text("phase"),
                                   execution.children("goals", "goal").stream().map(XmlNode::trimmed).toList(),
                                   merged(pluginConfig, configuration(execution)));
    }

    private static Map<String, ConfigValue> merged(Map<String, ConfigValue> plugin,
                                                   Map<String, ConfigValue> execution) {
        return Stream.concat(plugin.entrySet().stream(),
                             execution.entrySet().stream())
                     .collect(Collectors.toMap(Map.Entry::getKey,
                                               Map.Entry::getValue,
                                               (_, executionValue) -> executionValue));
    }

    private static Map<String, ConfigValue> configuration(XmlNode owner) {
        return owner.childrenOf("configuration")
                    .stream()
                    .collect(Collectors.toMap(XmlNode::name,
                                              EffectivePom::configValue,
                                              (_, last) -> last));
    }

    private static ConfigValue configValue(XmlNode node) {
        return node.hasChildren()
               ? ConfigValue.Items.items(node.children()
                                             .stream()
                                             .map(XmlNode::trimmed)
                                             .filter(text -> !text.isEmpty())
                                             .toList())
               : ConfigValue.Text.text(node.trimmed());
    }

    record unused() implements EffectivePom {}
}
