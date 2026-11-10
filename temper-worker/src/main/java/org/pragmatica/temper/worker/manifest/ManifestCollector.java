package org.pragmatica.temper.worker.manifest;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.manifest.ClassIndex;
import org.pragmatica.temper.domain.manifest.ClassInfo;
import org.pragmatica.temper.domain.manifest.ManifestError;
import org.pragmatica.temper.domain.manifest.ManifestOutcome;
import org.pragmatica.temper.domain.manifest.ManifestRules;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.manifest.PomModule;
import org.pragmatica.temper.domain.manifest.read.ClassInfos;
import org.pragmatica.temper.domain.manifest.read.EffectivePom;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.protocol.message.ManifestOutcomeDto;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.unit.UnitFiles;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// A unit's manifest at exit (m1-design §2.5), on the host, after the container exited. Only a unit started with
/// a `ManifestSpec` (`manifest-spec.json`) has one.
///
/// 1. Parse `units/<u>/manifest/effective-pom.xml` with [EffectivePom#parse] against the workspace.
/// 2. For every module, walk its `testOutputDirectory` and its `outputDirectory` WITHOUT following symlinks,
///    only where the directory's real path lies under the workspace's; read each `.class` file with
///    [ClassInfos#read]. Test classes go into the index AND `testClassesByModule` (keyed by the module path);
///    main classes into the index only.
/// 3. `notReached`: modules with a non-empty `testSourceDirectory` and no `testOutputDirectory`.
/// 4. [ManifestRules#manifest].
/// 5. `manifest.json` (the outcome's wire form, evidence and the resend copy).
///
/// Any failure of steps 1-3 (a malformed pom or class file, a directory escaping the workspace, an unreadable
/// file) is the outcome `Unsupported(<cause>)`, which the controller reports as `ERROR(manifest: <reason>)`: a
/// unit never passes without the coverage it asked for (Q-M1-7).
public record ManifestCollector(WorkerPaths paths, UnitFiles files, JsonMapper mapper) {
    public static final String CLASS_SUFFIX = ".class";
    public static final Set<String> NOT_CLASSES = Set.of("module-info.class", "package-info.class");

    public static ManifestCollector manifestCollector(WorkerPaths paths, UnitFiles files, JsonMapper mapper) {
        return new ManifestCollector(paths, files, mapper);
    }

    /// The unit's manifest outcome as sent, computed and recorded; none when the unit has no `ManifestSpec`. Never
    /// fails: a spec that cannot be read, a scan that fails and an outcome that cannot be recorded are all the
    /// outcome `Unsupported`, which the controller reports as `ERROR(manifest: <reason>)` (Q-M1-7). Runs on a virtual
    /// thread: thousands of class files are read and parsed.
    public Promise<Option<ManifestOutcomeDto>> collect(UnitRef ref) {
        return Promise.<Option<ManifestOutcome>> promise(() -> success(asked(ref)))
                      .map(outcome -> outcome.map(ManifestOutcomeDto::manifestOutcomeDto))
                      .map(outcome -> outcome.map(dto -> written(ref, dto)));
    }

    /// The recorded outcome (`manifest.json`) of an exit processed before; none when the unit has no
    /// `ManifestSpec`. A unit that asked for a manifest whose outcome was never recorded resends `Unsupported`, never
    /// nothing: a unit does not pass without the coverage it asked for.
    public Result<Option<ManifestOutcomeDto>> recorded(UnitRef ref) {
        return UnitFiles.optionalText(paths.manifestFile(ref))
                        .flatMap(text -> Values.optional(text, this::outcomeOf))
                        .map(found -> found.orElse(() -> notRecorded(ref)));
    }

    private Option<ManifestOutcomeDto> notRecorded(UnitRef ref) {
        return files.exists(ref, UnitFile.MANIFEST_SPEC)
               ? some(ManifestOutcomeDto.manifestOutcomeDto(ManifestOutcome.Unsupported.unsupported(WorkerError.General.MANIFEST_NOT_RECORDED)))
               : none();
    }

    private Result<ManifestOutcomeDto> outcomeOf(String json) {
        return mapper.readString(json, ManifestOutcomeDto.class);
    }

    /// The unit's `ManifestSpec`, stored at start; none for a unit without one.
    public Result<Option<ManifestSpec>> spec(UnitRef ref) {
        return files.text(ref, UnitFile.MANIFEST_SPEC)
                    .flatMap(text -> Values.optional(text, this::specOf));
    }

    private Result<ManifestSpec> specOf(String json) {
        return mapper.readString(json, ManifestSpecDto.class)
                     .flatMap(ManifestSpecDto::toDomain);
    }

    /// Not an absorption: a spec that cannot be read belongs to a unit that asked for a manifest, so its failure IS
    /// the outcome (Q-M1-7).
    Option<ManifestOutcome> asked(UnitRef ref) {
        return spec(ref).fold(cause -> some(unsupported(cause)), spec -> spec.map(asked -> outcome(ref, asked)));
    }

    /// Not an absorption: a failure to scan IS the outcome the unit reports, `ERROR(manifest: <reason>)` (Q-M1-7).
    ManifestOutcome outcome(UnitRef ref, ManifestSpec spec) {
        return scanned(ref, spec).fold(ManifestCollector::unsupported, outcome -> outcome);
    }

    /// The first failure found names the reason: a scan of thousands of class files collects every failure, and
    /// the composite's own text (`Composite: …`) is no reason a reader can act on.
    private static ManifestOutcome unsupported(Cause cause) {
        return ManifestOutcome.Unsupported.unsupported(firstCause(cause));
    }

    static Cause firstCause(Cause cause) {
        return cause instanceof Causes.CompositeCause composite
               ? Option.from(composite.stream().findFirst())
                       .map(ManifestCollector::firstCause)
                       .or(cause)
               : cause;
    }

    private Result<ManifestOutcome> scanned(UnitRef ref, ManifestSpec spec) {
        var workspace = paths.workspace(ref.job());

        return FileOps.readBytes(paths.effectivePom(ref))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("read the effective POM", origin))
                      .flatMap(xml -> EffectivePom.parse(xml,
                                                         workspace.toString()))
                      .flatMap(modules -> withClasses(workspace, modules))
                      .map(scan -> ManifestRules.manifest(scan.modules(),
                                                          spec,
                                                          scan.testClassesByModule(),
                                                          scan.index(),
                                                          scan.notReached()));
    }

    private Result<Scan> withClasses(Path workspace, List<PomModule> modules) {
        return realPath(workspace).flatMap(realWorkspace -> allModuleClasses(workspace, realWorkspace, modules))
                       .map(classes -> Scan.scan(modules, classes));
    }

    private static Result<List<ModuleClasses>> allModuleClasses(Path workspace,
                                                                Path realWorkspace,
                                                                List<PomModule> modules) {
        return Result.allOf(modules.stream().map(module -> moduleClasses(workspace, realWorkspace, module)).toList());
    }

    private static Result<ModuleClasses> moduleClasses(Path workspace, Path realWorkspace, PomModule module) {
        return Result.all(classesUnder(workspace,
                                       realWorkspace,
                                       module.testOutputDirectory()),
                          classesUnder(workspace,
                                       realWorkspace,
                                       module.outputDirectory()),
                          hasFiles(workspace,
                                   realWorkspace,
                                   module.testSourceDirectory()))
                     .map((tests, main, sources) -> ModuleClasses.moduleClasses(module.path(),
                                                                                tests,
                                                                                main.or(List.of()),
                                                                                sources));
    }

    /// The classes compiled into `relative`; none when that directory does not exist (or is not a directory, a
    /// symlink included). A directory whose real path leaves the workspace is refused.
    private static Result<Option<List<ClassInfo>>> classesUnder(Path workspace, Path realWorkspace, String relative) {
        var directory = workspace.resolve(relative).normalize();

        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
               ? confined(directory, realWorkspace).flatMap(ManifestCollector::classesIn)
                         .map(Option::some)
               : success(none());
    }

    /// The classes under `realDirectory`, named by their path relative to it.
    private static Result<List<ClassInfo>> classesIn(Path realDirectory) {
        return classFiles(realDirectory).flatMap(found -> classInfos(realDirectory, found));
    }

    private static Result<Path> confined(Path directory, Path realWorkspace) {
        return realPath(directory).filter(ManifestError.OutsideWorkspace.FACTORY.apply(directory.toString()),
                                          real -> real.startsWith(realWorkspace));
    }

    private static Result<Path> realPath(Path path) {
        return Result.lift(thrown -> WorkerError.Io.FACTORY.apply("resolve " + path, Causes.fromThrowable(thrown)),
                           () -> path.toRealPath());
    }

    /// Regular `.class` files under `directory`; symlinks are neither followed nor read.
    private static Result<List<Path>> classFiles(Path directory) {
        var visitor = new ClassFileVisitor();

        return Result.lift(thrown -> WorkerError.Io.FACTORY.apply("walk " + directory,
                                                                  Causes.fromThrowable(thrown)),
                           () -> Files.walkFileTree(directory, visitor))
                     .map(_ -> visitor.found());
    }

    private static Result<List<ClassInfo>> classInfos(Path directory, List<Path> found) {
        return Result.allOf(found.stream().map(file -> classInfo(directory, file)).toList());
    }

    private static Result<ClassInfo> classInfo(Path directory, Path file) {
        var name = directory.relativize(file).toString();

        return FileOps.readBytes(file)
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("read " + file, origin))
                      .flatMap(bytes -> classInfoOf(name, bytes));
    }

    private static Result<ClassInfo> classInfoOf(String name, byte[] bytes) {
        return ClassInfos.read(name, bytes);
    }

    /// Whether `relative` holds at least one regular file anywhere below it (symlinks not followed); false when it
    /// does not exist. A directory whose real path leaves the workspace is refused, as for the class directories.
    private static Result<Boolean> hasFiles(Path workspace, Path realWorkspace, String relative) {
        var directory = workspace.resolve(relative).normalize();

        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
               ? confined(directory, realWorkspace).flatMap(ManifestCollector::anyRegularFile)
               : success(false);
    }

    private static Result<Boolean> anyRegularFile(Path directory) {
        return FileOps.walk(directory, ManifestCollector::isRegularFile)
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("walk " + directory, origin))
                      .map(regularFiles -> !regularFiles.isEmpty());
    }

    private static boolean isRegularFile(Path entry) {
        return Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS);
    }

    /// `manifest.json`, the evidence and the resend copy. Not an absorption: an outcome that cannot be recorded is
    /// sent as `Unsupported` with the write's failure (a resend then reports [WorkerError.General#MANIFEST_NOT_RECORDED]),
    /// so the verdict never rests on a manifest nobody can show.
    private ManifestOutcomeDto written(UnitRef ref, ManifestOutcomeDto dto) {
        return mapper.writeAsString(dto)
                     .flatMap(json -> UnitFiles.storeAtomic(paths.manifestFile(ref),
                                                            json))
                     .fold(cause -> ManifestOutcomeDto.manifestOutcomeDto(unsupported(cause)),
                           _ -> dto);
    }

    /// One module's compiled classes: `testClasses` none when its test output does not exist.
    record ModuleClasses(String path,
                         Option<List<ClassInfo>> testClasses,
                         List<ClassInfo> mainClasses,
                         boolean hasTestSources) {
        static ModuleClasses moduleClasses(String path,
                                           Option<List<ClassInfo>> testClasses,
                                           List<ClassInfo> mainClasses,
                                           boolean hasTestSources) {
            return new ModuleClasses(path, testClasses, mainClasses, hasTestSources);
        }

        boolean notReached() {
            return hasTestSources && testClasses.isEmpty();
        }

        Stream<ClassInfo> all() {
            return Stream.concat(testClasses.or(List.of()).stream(),
                                 mainClasses.stream());
        }
    }

    /// The scan's inputs to [ManifestRules#manifest].
    record Scan(List<PomModule> modules,
                Map<String, List<ClassInfo>> testClassesByModule,
                ClassIndex index,
                Set<String> notReached) {
        static Scan scan(List<PomModule> modules, List<ModuleClasses> classes) {
            return new Scan(modules,
                            classes.stream()
                                   .filter(module -> module.testClasses()
                                                           .isPresent())
                                   .collect(Collectors.toMap(ModuleClasses::path,
                                                             module -> module.testClasses()
                                                                             .or(List.of()),
                                                             Scan::concatenated)),
                            ClassIndex.classIndex(classes.stream().flatMap(ModuleClasses::all).toList()),
                            classes.stream()
                                   .filter(ModuleClasses::notReached)
                                   .map(ModuleClasses::path)
                                   .collect(Collectors.toUnmodifiableSet()));
        }

        private static List<ClassInfo> concatenated(List<ClassInfo> first, List<ClassInfo> second) {
            return Stream.concat(first.stream(),
                                 second.stream())
                         .toList();
        }
    }

    /// Collects regular `.class` files; `walkFileTree` without `FOLLOW_LINKS` reports a symlink as a link, which
    /// is skipped, and never descends through a symlinked directory.
    static final class ClassFileVisitor extends SimpleFileVisitor<Path> {
        private final List<Path> found = new ArrayList<>();

        List<Path> found() {
            return List.copyOf(found);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isRegularFile() && isClassFile(file)) {
                found.add(file);
            }

            return FileVisitResult.CONTINUE;
        }

        private static boolean isClassFile(Path file) {
            var name = file.getFileName().toString();

            return name.endsWith(CLASS_SUFFIX) && !NOT_CLASSES.contains(name);
        }
    }
}
