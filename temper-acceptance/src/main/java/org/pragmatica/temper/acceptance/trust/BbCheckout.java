package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// A bb checkout's last bb-build (m1-design §8.1): its `.bb/` directory and the report XML newer than
/// `.bb/run.marker`, selected exactly as bb-report selects it (`find`, pruning `.m2-local`).
public record BbCheckout(Path root) {
    public static final String BB = ".bb";
    public static final String RUN_MARKER = "run.marker";
    public static final String RC = "rc";
    public static final String ARGS = "args";
    private static final TimeSpan FIND_TIMEOUT = timeSpan(10).minutes();

    public static BbCheckout bbCheckout(Path root) {
        return new BbCheckout(root.toAbsolutePath().normalize());
    }

    public Path bb() {
        return root.resolve(BB);
    }

    /// The report XML newer than the run marker, as absolute paths.
    public Promise<List<Path>> reportXml(Processes processes) {
        return processes.output(List.of("find",
                                        root.toString(),
                                        "-path",
                                        root.resolve(".m2-local").toString(),
                                        "-prune",
                                        "-o",
                                        "(",
                                        "-path",
                                        "*/target/surefire-reports/*.xml",
                                        "-o",
                                        "-path",
                                        "*/target/failsafe-reports/*.xml",
                                        ")",
                                        "-newer",
                                        bb().resolve(RUN_MARKER).toString(),
                                        "-print"),
                                FIND_TIMEOUT)
                        .map(BbCheckout::paths);
    }

    private static List<Path> paths(String output) {
        return output.lines()
                     .filter(line -> !line.isBlank())
                     .map(Path::of)
                     .toList();
    }
}
