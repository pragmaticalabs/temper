package org.pragmatica.kiln.service.scan;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.scan.ScanTransportError.Failed;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// [ScanTransport] over ssh to a host's forced command (kiln-f1-design §3.2): the argv is [SshCommand]'s, the
/// full-facts list goes on stdin, and the process is run by [ScanExec], so ssh's own exit 255 (no route, refused key,
/// unknown host key, a dropped session), the script's nonzero rc, a timeout and an empty answer each fail the call
/// (§3.4, K-5). ssh sees only `PATH` and `HOME` (this JVM's): no token of kiln's environment reaches it.
public record SshScanTransport(KilnConfig.Host host, ProcessRunner runner, TimeSpan timeout) implements ScanTransport {
    private static final String PATH = Option.option(System.getenv("PATH")).or("/usr/bin:/bin");
    private static final String HOME = System.getProperty("user.home");

    public static SshScanTransport sshScanTransport(KilnConfig.Host host, ProcessRunner runner, TimeSpan timeout) {
        return new SshScanTransport(host, runner, timeout);
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
        return Command.command(SshCommand.scan(host,
                                               Stream.concat(Stream.of(verb),
                                                             words.stream()).toList()),
                               Map.of("PATH", PATH, "HOME", HOME),
                               timeout)
                      .async()
                      .mapError(origin -> Failed.FACTORY.apply(verb, origin))
                      .flatMap(command -> ScanExec.run(runner, command, verb, input, timeout));
    }

    /// One path per line, each ending in a newline; no path at all is empty stdin (full facts for every checkout).
    private static String stdin(List<CheckoutPath> full) {
        return full.stream()
                   .map(path -> path.value() + "\n")
                   .collect(Collectors.joining());
    }
}
