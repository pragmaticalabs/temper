package org.pragmatica.temper.worker.unit;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.MarkerEpoch;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.report.ReportCandidate;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.domain.report.ReportSelection;
import org.pragmatica.temper.protocol.report.ReportParser;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Report collection at unit exit (spec §4.4, m0-design §7.4): walk the workspace for
/// `**/target/{surefire,failsafe}-reports/TEST-*.xml`, keep only files strictly newer than the marker's TEXT epoch
/// ([ReportSelection#newerThan]; the marker file's own mtime is never read), copy them to the unit's `reports/`
/// (relative paths preserved, so equal file names from two modules cannot collide), and parse the copies.
public record ReportCollector(WorkerPaths paths, UnitFiles files) {
    private static final Logger LOG = LoggerFactory.getLogger(ReportCollector.class);
    /// Workspace subtrees never searched: the Maven repository, git metadata and the container home.
    public static final Set<String> SKIPPED = Set.of(".m2-local", ".git", ".home");
    public static final Set<String> REPORT_DIRS = Set.of("surefire-reports", "failsafe-reports");
    public static final Pattern REPORT_FILE = Pattern.compile("^TEST-.+\\.xml$");

    public static ReportCollector reportCollector(WorkerPaths paths, UnitFiles files) {
        return new ReportCollector(paths, files);
    }

    /// Selects, copies and parses the unit's reports. An unreadable marker selects nothing (`errorReason=marker`).
    public Promise<ReportOutcome> collect(UnitRef ref) {
        return marker(ref).onFailure(cause -> LOG.error("unit {}: run marker unreadable, no report selected: {}",
                                                        ref.containerName(),
                                                        cause))
                     .fold(_ -> Promise.success(ReportOutcome.MARKER_UNREADABLE),
                           marker -> selectedAndCopied(ref, marker));
    }

    /// Parses the reports already copied to `reports/` (a resend after a lost ack, or a later worker).
    public Promise<ReportOutcome> parseCopied(UnitRef ref) {
        var reports = paths.reportsDir(ref);

        return candidates(reports).map(found -> located(reports, found))
                         .map(ReportCollector::outcomeOf)
                         .async();
    }

    private Result<MarkerEpoch> marker(UnitRef ref) {
        return files.text(ref, UnitFile.MARKER)
                    .flatMap(text -> text.toResult(missingMarker(ref)))
                    .flatMap(MarkerEpoch::markerEpoch);
    }

    private WorkerError.MissingState missingMarker(UnitRef ref) {
        return WorkerError.MissingState.FACTORY.apply(files.path(ref, UnitFile.MARKER));
    }

    private Promise<ReportOutcome> selectedAndCopied(UnitRef ref, MarkerEpoch marker) {
        var workspace = paths.workspace(ref.job());

        return candidates(workspace).map(all -> ReportSelection.newerThan(marker, all))
                         .flatMap(selected -> copies(workspace,
                                                     paths.reportsDir(ref),
                                                     selected))
                         .map(ReportCollector::outcomeOf)
                         .async();
    }

    private static List<Path> located(Path root, List<ReportCandidate> found) {
        return found.stream()
                    .map(candidate -> root.resolve(candidate.path()))
                    .toList();
    }

    private static Result<List<Path>> copies(Path workspace, Path reports, List<ReportCandidate> selected) {
        return Result.allOf(selected.stream()
                                    .map(candidate -> copied(workspace.resolve(candidate.path()),
                                                             reports.resolve(candidate.path())))
                                    .toList());
    }

    private static Result<Path> copied(Path source, Path target) {
        return FileOps.createDirectories(target.getParent())
                      .flatMap(_ -> FileOps.copyReplace(source, target))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("copy " + source + " to " + target, origin));
    }

    /// Each copy parsed independently: one malformed file does not hide the rows of the others.
    private static ReportOutcome outcomeOf(List<Path> copies) {
        return ReportOutcome.reportOutcome(copies.stream().map(ReportCollector::reportFile).toList());
    }

    private static Result<ReportFile> reportFile(Path copy) {
        return FileOps.readBytes(copy).flatMap(bytes -> reportOf(copy, bytes));
    }

    private static Result<ReportFile> reportOf(Path copy, byte[] bytes) {
        return ReportParser.parse(fileName(copy), bytes);
    }

    /// Report files under `root` with their mtimes in epoch nanoseconds; paths relative to `root`. A missing root
    /// has no reports.
    static Result<List<ReportCandidate>> candidates(Path root) {
        var visitor = new ReportVisitor(root);

        return FileOps.isDirectory(root)
               ? Result.lift(walkError(root),
                             () -> Files.walkFileTree(root, visitor))
                       .map(_ -> visitor.found())
               : success(List.of());
    }

    private static Fn1<WorkerError.Io, Throwable> walkError(Path root) {
        return thrown -> WorkerError.Io.FACTORY.apply("search reports under " + root, Causes.fromThrowable(thrown));
    }

    private static String fileName(Path path) {
        return option(path.getFileName()).map(Path::toString)
                     .or("");
    }

    private static Option<Path> parentOf(Path path) {
        return option(path.getParent());
    }

    /// `…/target/{surefire,failsafe}-reports/TEST-*.xml`; the copies under `reports/` keep that relative path.
    static boolean isReport(Path file) {
        return REPORT_FILE.matcher(fileName(file)).matches()
               && inReportDirectory(file)
               && underTarget(file);
    }

    private static boolean inReportDirectory(Path file) {
        return parentOf(file).map(ReportCollector::fileName)
                       .filter(REPORT_DIRS::contains)
                       .isPresent();
    }

    private static boolean underTarget(Path file) {
        return parentOf(file).flatMap(ReportCollector::parentOf)
                       .map(ReportCollector::fileName)
                       .filter("target"::equals)
                       .isPresent();
    }

    /// Collects report files, pruning the skipped subtrees. Thread-confined to one walk.
    static final class ReportVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final List<ReportCandidate> found = new ArrayList<>();

        ReportVisitor(Path root) {
            this.root = root;
        }

        List<ReportCandidate> found() {
            return List.copyOf(found);
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            return ! directory.equals(root) && SKIPPED.contains(fileName(directory))
                   ? FileVisitResult.SKIP_SUBTREE
                   : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isRegularFile() && isReport(file)) {
                found.add(ReportCandidate.reportCandidate(root.relativize(file).toString(),
                                                          attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)));
            }

            return FileVisitResult.CONTINUE;
        }
    }
}
