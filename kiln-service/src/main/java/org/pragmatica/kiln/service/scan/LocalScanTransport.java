package org.pragmatica.kiln.service.scan;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.scan.ScanTransportError.Failed;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// [ScanTransport] that runs `kiln-scan` on this machine as the forced command runs it (kiln-f1-design §7.1 WP4): the
/// request in `SSH_ORIGINAL_COMMAND`, `HOME` set to `home`, the full-facts list on stdin, and an environment holding
/// only `PATH` and `USER` (this JVM's), then `environment` (a test puts fake tools first on `PATH` there), then `HOME`
/// and `SSH_ORIGINAL_COMMAND`, which `environment` cannot replace. For the fixture tests (K-1, K-3, K-14); hosts are
/// reached over ssh ([SshScanTransport]). The process is run by [ScanExec].
public record LocalScanTransport(Path script, Path home, Map<String, String> environment, TimeSpan timeout) implements ScanTransport {
    private static final ProcessRunner RUNNER = ProcessRunner.processRunner();
    private static final String PATH = Option.option(System.getenv("PATH")).or("/usr/bin:/bin");
    private static final String USER = System.getProperty("user.name");
    /// Characters of stderr, from its end, kept in [ScanTransportError.NonZero].
    static final int STDERR_TAIL_CHARS = ScanExec.STDERR_TAIL_CHARS;

    public LocalScanTransport {
        environment = Map.copyOf(environment);
    }

    public static LocalScanTransport localScanTransport(Path script,
                                                        Path home,
                                                        Map<String, String> environment,
                                                        TimeSpan timeout) {
        return new LocalScanTransport(script, home, environment, timeout);
    }

    @Override
    public Promise<List<String>> scan(List<String> roots, List<CheckoutPath> full) {
        return run("scan", roots, stdin(full));
    }

    /// The SHAs in order, so one set is always one request.
    @Override
    public Promise<List<String>> reach(Set<Sha> shas) {
        return run("reach",
                   shas.stream().map(Sha::value).sorted().toList(),
                   "");
    }

    @Override
    public Promise<List<String>> size(String root, CheckoutPath path) {
        return run("size",
                   List.of(root, path.value()),
                   "");
    }

    /// A word kiln-scan cannot carry ([ScanExec#carried]) fails the call before anything runs.
    private Promise<List<String>> run(String verb, List<String> words, String input) {
        return ScanExec.carried(verb, words)
                       .async()
                       .flatMap(_ -> command(verb, words, input));
    }

    private Promise<List<String>> command(String verb, List<String> words, String input) {
        return Command.command(List.of("bash",
                                       script.toString()),
                               environment(request(verb, words)),
                               timeout)
                      .async()
                      .mapError(origin -> Failed.FACTORY.apply(verb, origin))
                      .flatMap(command -> ScanExec.run(RUNNER, command, verb, input, timeout));
    }

    private Map<String, String> environment(String request) {
        return Stream.of(Map.of("PATH", PATH, "USER", USER),
                         environment,
                         Map.of("HOME",
                                home.toString(),
                                "SSH_ORIGINAL_COMMAND",
                                request))
                     .flatMap(map -> map.entrySet()
                                        .stream())
                     .collect(Collectors.toMap(Map.Entry::getKey,
                                               Map.Entry::getValue,
                                               (_, later) -> later));
    }

    private static String request(String verb, List<String> words) {
        return Stream.concat(Stream.of(verb),
                             words.stream())
                     .collect(Collectors.joining(" "));
    }

    /// One path per line, each ending in a newline; no path at all is empty stdin (full facts for every checkout).
    private static String stdin(List<CheckoutPath> full) {
        return full.stream()
                   .map(path -> path.value() + "\n")
                   .collect(Collectors.joining());
    }
}
