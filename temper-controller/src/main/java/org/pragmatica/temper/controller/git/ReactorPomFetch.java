package org.pragmatica.temper.controller.git;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.controller.flow.Batches;
import org.pragmatica.temper.domain.mvn.ReactorModules;

import static org.pragmatica.lang.utils.Causes.cause;


/// The reactor of a tree (m1-design §1.6) through an asynchronous file reader (`file(path)` is the file's bytes at
/// the job's SHA, none when the tree has no such file): `.mvn/maven.config` first, then the poms level by level
/// ([ReactorRead]), at most `batch` poms fetched at once.
///
/// Fails when the reactor cannot be judged: the config selects another reactor, a pom is missing or malformed, or
/// the tree is deeper than [#MAX_ROUNDS] levels. The caller treats every failure as an unknown reactor.
public sealed interface ReactorPomFetch {
    String MAVEN_CONFIG = ".mvn/maven.config";
    /// Bound on the levels followed; [org.pragmatica.temper.domain.mvn.read.ReactorPoms] bounds the module count.
    int MAX_ROUNDS = 64;

    static Promise<Option<ReactorModules>> reactor(Fn1<Promise<Option<byte[]>>, String> file, int batch) {
        return file.apply(MAVEN_CONFIG)
                   .map(config -> config.map(ReactorPomFetch::text))
                   .flatMap(config -> fromRoot(file, batch, config))
                   .map(Option::some);
    }

    private static Promise<ReactorModules> fromRoot(Fn1<Promise<Option<byte[]>>, String> file,
                                                    int batch,
                                                    Option<String> config) {
        return ReactorRead.buildsDefaultReactor(config)
               ? rounds(file, batch, Map.of(), 1)
               : cause(MAVEN_CONFIG + " selects another reactor (-f, -P or -pl)").promise();
    }

    private static Promise<ReactorModules> rounds(Fn1<Promise<Option<byte[]>>, String> file,
                                                  int batch,
                                                  Map<String, Option<byte[]>> poms,
                                                  int round) {
        return switch (ReactorRead.attempt(poms)) {
            case ReactorRead.Done done -> done.reactor().async();
            case ReactorRead.Missing missing -> nextRound(file, batch, poms, missing.modules(), round);
        };
    }

    private static Promise<ReactorModules> nextRound(Fn1<Promise<Option<byte[]>>, String> file,
                                                     int batch,
                                                     Map<String, Option<byte[]>> poms,
                                                     Set<String> missing,
                                                     int round) {
        return round >= MAX_ROUNDS
               ? cause("reactor deeper than " + MAX_ROUNDS + " levels").promise()
               : fetched(file, batch, missing).flatMap(more -> rounds(file, batch, merged(poms, more), round + 1));
    }

    private static Promise<List<Map.Entry<String, Option<byte[]>>>> fetched(Fn1<Promise<Option<byte[]>>, String> file,
                                                                            int batch,
                                                                            Set<String> modules) {
        return Batches.values(modules.stream().sorted().toList(),
                              batch,
                              module -> pomOf(file, module));
    }

    private static Promise<Map.Entry<String, Option<byte[]>>> pomOf(Fn1<Promise<Option<byte[]>>, String> file,
                                                                    String module) {
        return file.apply(ReactorRead.pomPath(module))
                   .map(bytes -> Map.entry(module, bytes));
    }

    private static Map<String, Option<byte[]>> merged(Map<String, Option<byte[]>> poms,
                                                      List<Map.Entry<String, Option<byte[]>>> more) {
        return Stream.concat(poms.entrySet().stream(),
                             more.stream())
                     .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                           Map.Entry::getValue,
                                                           (first, _) -> first));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    record unused() implements ReactorPomFetch {}
}
