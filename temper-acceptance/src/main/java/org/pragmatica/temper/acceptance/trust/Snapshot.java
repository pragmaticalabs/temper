package org.pragmatica.temper.acceptance.trust;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.report.ReportParser;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// T1 step 2 (m1-design §8.1): the bb evidence of one SHA, copied BEFORE temper runs, because bb checkouts are
/// reused (risk R4). `dir/bb/` receives `.bb/` and the report XML newer than the marker at their checkout-relative
/// paths with their modification times, so `dir/bb/` is itself a checkout bb-report can read: the live run and a
/// verifier's re-run (§8.1 step 5, §9.3's doctored snapshot) both run bb-report on these bytes.
public record Snapshot(Path dir) {
    public static final String BB_DIR = "bb";
    public static final String BB_REPORT_OUT = "bb-report.out";
    public static final String BB_REPORT_EXIT = "bb-report.exit";
    public static final String BUILD_LOG = "build.log";
    private static final TimeSpan BB_REPORT_TIMEOUT = timeSpan(10).minutes();

    public static Snapshot snapshot(Path dir) {
        return new Snapshot(dir.toAbsolutePath().normalize());
    }

    public BbCheckout checkout() {
        return BbCheckout.bbCheckout(dir.resolve(BB_DIR));
    }

    /// Copies `.bb/` (its regular files) and the marker-newer XML of `source` into this snapshot.
    public Promise<Snapshot> take(Processes processes, BbCheckout source) {
        return source.reportXml(processes)
                     .flatMap(xml -> copied(source, xml))
                     .map(_ -> this);
    }

    private Promise<Integer> copied(BbCheckout source, List<Path> xml) {
        return FileOps.list(source.bb())
                      .map(bb -> withXml(bb, xml))
                      .flatMap(files -> copiedAll(source.root(),
                                                  files))
                      .map(List::size)
                      .async();
    }

    /// The regular files of `.bb/` and the XML.
    private static List<Path> withXml(List<Path> bb, List<Path> xml) {
        return Stream.concat(bb.stream().filter(Snapshot::regular),
                             xml.stream())
                     .toList();
    }

    private static boolean regular(Path file) {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    private Result<List<Path>> copiedAll(Path root, List<Path> files) {
        return Result.allOf(files.stream().map(file -> copy(root, file)).toList());
    }

    private Result<Path> copy(Path root, Path file) {
        var target = checkout().root().resolve(root.relativize(file).toString());

        return FileOps.createDirectories(target.getParent()).flatMap(_ -> copyKeepingTimes(file, target));
    }

    /// The copy keeps the modification time: bb-report selects the XML newer than the marker.
    private static Result<Path> copyKeepingTimes(Path file, Path target) {
        return Result.lift(thrown -> TrustError.Io.FACTORY.apply("copy " + file, Causes.fromThrowable(thrown)),
                           () -> Files.copy(file,
                                            target,
                                            StandardCopyOption.COPY_ATTRIBUTES,
                                            StandardCopyOption.REPLACE_EXISTING));
    }

    /// `bb-report <snapshot>`: its stdout and exit code, also kept as `bb-report.out` / `bb-report.exit`.
    public Promise<Completed> bbReport(Processes processes, Path bbReport) {
        return processes.run(List.of(bbReport.toString(),
                                     checkout().root().toString()),
                             Map.of(),
                             BB_REPORT_TIMEOUT)
                        .flatMap(completed -> kept(completed).async());
    }

    private Result<Completed> kept(Completed completed) {
        return FileOps.writeString(dir.resolve(BB_REPORT_OUT),
                                   completed.stdoutText())
                      .flatMap(_ -> FileOps.writeString(dir.resolve(BB_REPORT_EXIT),
                                                        completed.rc() + "\n"))
                      .map(_ -> completed);
    }

    /// The fork-crash lines of the snapshot's `.bb/build.log` (R-14: bb's side of a crash); none when the log has none,
    /// or when there is no log. Decoded as ISO-8859-1, which accepts any byte: the marker itself is ASCII.
    public Result<Option<String>> bbCrashEvidence() {
        var log = checkout().bb().resolve(BUILD_LOG);

        return Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)
               ? FileOps.readBytes(log).map(Snapshot::crashLines)
               : success(none());
    }

    private static Option<String> crashLines(byte[] log) {
        return TrustComparison.crashEvidence(new String(log, StandardCharsets.ISO_8859_1));
    }

    /// The snapshot's report XML, read with temper's own `ReportParser`: the diagnostic case diff, and the report file
    /// each failed row of temper's belongs to ([TrustComparison#failedClasses]).
    public Result<List<ReportFile>> bbReports() {
        return FileOps.walk(checkout().root(),
                            Snapshot::isReportXml)
                      .flatMap(Snapshot::reportFiles);
    }

    private static Result<List<ReportFile>> reportFiles(List<Path> files) {
        return Result.allOf(files.stream().map(Snapshot::reportFile).toList());
    }

    private static boolean isReportXml(Path file) {
        var name = file.getFileName().toString();

        return regular(file)
               && name.endsWith(".xml")
               && file.toString()
                      .contains("-reports/");
    }

    private static Result<ReportFile> reportFile(Path file) {
        var name = file.getFileName().toString();

        return FileOps.readBytes(file).flatMap(bytes -> reportOf(name, bytes));
    }

    /// One report file's testcases, read with temper's own `ReportParser`.
    private static Result<ReportFile> reportOf(String name, byte[] xml) {
        return ReportParser.parse(name, xml);
    }
}
