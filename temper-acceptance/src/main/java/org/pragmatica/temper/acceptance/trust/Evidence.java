package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.protocol.api.ResultRecordDto;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Members of a unit's small evidence tarball on the controller host (m1-design §3.1, §3.2):
/// `<evidence.path>/units/<unit>/a<n>/evidence.tar.gz` of attempt n (m4-design §4.6), or
/// `<evidence.path>/units/<unit>/evidence.tar.gz` for evidence stored before M4, holding `log.tail`, `.temper/facts-start.json`,
/// `.temper/facts-end.json`, the reports and the manifest. The T1/T2 tooling runs on the controller host, where
/// `ResultRecordDto.evidence.path` is a local directory.
public record Evidence(Processes processes) {
    public static final String LOG_TAIL = "log.tail";
    public static final String FACTS_START = ".temper/facts-start.json";
    public static final String FACTS_END = ".temper/facts-end.json";
    public static final String SMALL_TARBALL = "evidence.tar.gz";
    private static final TimeSpan TAR_TIMEOUT = timeSpan(2).minutes();
    private static final Pattern ATTEMPT = Pattern.compile("a([1-9][0-9]{0,8})");

    public static Evidence evidence(Processes processes) {
        return new Evidence(processes);
    }

    /// The tarball of `unit` when the record says the evidence is stored and the file is there.
    public static Option<Path> tarball(ResultRecordDto result, String unit) {
        return result.evidence()
                     .path()
                     .filter(_ -> result.evidence()
                                        .stored())
                     .map(path -> Path.of(path, "units", unit))
                     .flatMap(Evidence::unitTarball);
    }

    /// The tarball of the unit's highest attempt that has one, else the pre-M4 tarball directly under the unit. The
    /// record names no attempt; attempts of a unit only grow and the record is written for the attempt that settled
    /// it, its last, so the highest `n` holding a tarball is that attempt's. `n` is compared as a number (a10 > a9).
    private static Option<Path> unitTarball(Path unitDirectory) {
        return latestAttempt(unitDirectory).orElse(() -> regularFile(unitDirectory.resolve(SMALL_TARBALL)));
    }

    private static Option<Path> latestAttempt(Path unitDirectory) {
        return FileOps.list(unitDirectory)
                      .option()
                      .flatMap(entries -> Option.from(entries.stream()
                                                             .filter(entry -> attempt(entry).isPresent())
                                                             .filter(entry -> Files.isRegularFile(entry.resolve(SMALL_TARBALL)))
                                                             .max(Comparator.comparing(entry -> attempt(entry).or(0)))))
                      .map(entry -> entry.resolve(SMALL_TARBALL));
    }

    private static Option<Integer> attempt(Path directory) {
        var matcher = ATTEMPT.matcher(directory.getFileName().toString());

        return matcher.matches()
               ? Number.parseInt(matcher.group(1)).option()
               : Option.none();
    }

    private static Option<Path> regularFile(Path path) {
        return Option.some(path).filter(Files::isRegularFile);
    }

    /// The small tarball of the record's first unit that has one on this host.
    public static Option<Path> firstTarball(ResultRecordDto result) {
        return Option.from(result.units().stream().flatMap(unit -> unitTarball(result, unit)).findFirst());
    }

    private static Stream<Path> unitTarball(ResultRecordDto result, ResultRecordDto.UnitResultDto unit) {
        return tarball(result, unit.id()).stream();
    }

    /// One member's text (`tar -xzOf <tarball> <member>`).
    public Promise<String> member(Path tarball, String member) {
        return processes.output(List.of("tar", "-xzOf", tarball.toString(), member),
                                TAR_TIMEOUT);
    }
}
