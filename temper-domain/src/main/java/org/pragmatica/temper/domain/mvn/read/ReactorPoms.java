package org.pragmatica.temper.domain.mvn.read;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.mvn.ReactorError;
import org.pragmatica.temper.domain.mvn.ReactorModules;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Reads the [ReactorModules] of a tree (m1-design §1.6) through `pomAt`, which returns the bytes of
/// `<module>/pom.xml` at the job's SHA (`""` is the root, so the root pom is `pom.xml`), or none when the tree
/// has no such file. The controller's `TreeReader` supplies it with `git show <sha>:<path>`.
///
/// Every module's `<modules>`, and those of its `<profile>`s that have an `<activation>`, are followed
/// breadth-first; a module is read once. StAX is namespace-unaware with DTDs and external entities off (as
/// `ReportParser`). Any surprise (a missing or malformed pom, an entry that leaves the repository or names a
/// file, more than [#MAX_MODULES] modules) fails the read: the caller then treats the reactor as unknown,
/// which is the conservative M0 behaviour.
public sealed interface ReactorPoms {
    /// Bound on the modules followed, so a pathological tree cannot cost unbounded `git show` calls.
    int MAX_MODULES = 5000;
    String ROOT = "";

    static Result<ReactorModules> parse(Fn1<Option<byte[]>, String> pomAt) {
        return collect(pomAt, List.of(ROOT), Set.of()).map(ReactorModules::reactorModules);
    }

    /// One breadth-first level: reads every pending module, then follows the entries not seen yet.
    private static Result<Set<String>> collect(Fn1<Option<byte[]>, String> pomAt,
                                               List<String> pending,
                                               Set<String> seen) {
        var known = union(seen, pending);

        return pending.isEmpty()
               ? success(known)
               : nextLevel(pomAt, pending, known);
    }

    private static Result<Set<String>> nextLevel(Fn1<Option<byte[]>, String> pomAt,
                                                 List<String> pending,
                                                 Set<String> known) {
        return pending.stream()
                      .reduce(success(List.<List<String>> of()),
                              (read, module) -> readNext(pomAt, read, module),
                              (first, _) -> first)
                      .map(levels -> unseen(levels, known))
                      .flatMap(next -> bounded(next, known))
                      .flatMap(next -> collect(pomAt, next, known));
    }

    /// The level read so far plus `module`'s children; stops reading at the first failure, so a failed level
    /// costs no further `pomAt` calls.
    private static Result<List<List<String>>> readNext(Fn1<Option<byte[]>, String> pomAt,
                                                       Result<List<List<String>>> read,
                                                       String module) {
        return read.flatMap(levels -> withChildren(pomAt, levels, module));
    }

    private static Result<List<List<String>>> withChildren(Fn1<Option<byte[]>, String> pomAt,
                                                           List<List<String>> levels,
                                                           String module) {
        return children(pomAt, module).map(children -> appended(levels, children));
    }

    private static List<List<String>> appended(List<List<String>> levels, List<String> children) {
        return Stream.concat(levels.stream(),
                             Stream.of(children))
                     .toList();
    }

    private static Result<List<String>> bounded(List<String> next, Set<String> known) {
        return next.size() + known.size() > MAX_MODULES
               ? ReactorError.TooManyModules.FACTORY.apply(MAX_MODULES).result()
               : success(next);
    }

    private static List<String> unseen(List<List<String>> levels, Set<String> known) {
        return levels.stream()
                     .flatMap(List::stream)
                     .filter(module -> !known.contains(module))
                     .distinct()
                     .toList();
    }

    private static Set<String> union(Set<String> seen, List<String> pending) {
        return Stream.concat(seen.stream(),
                             pending.stream())
                     .collect(Collectors.toUnmodifiableSet());
    }

    /// The modules `module`'s pom names, resolved against `module`.
    private static Result<List<String>> children(Fn1<Option<byte[]>, String> pomAt, String module) {
        return pomAt.apply(module)
                    .toResult(ReactorError.MissingPom.FACTORY.apply(module))
                    .flatMap(bytes -> entries(module, bytes))
                    .flatMap(entries -> childPaths(module, entries));
    }

    private static Result<List<String>> childPaths(String module, List<String> entries) {
        return Result.firstFailureOf(entries.stream().map(raw -> childPath(module, raw)).toList());
    }

    /// `entry` resolved against the directory `module`: `.` segments dropped, `..` applied. Refused when the
    /// entry is blank or absolute, names a file, or climbs above the repository root.
    private static Result<String> childPath(String module, String entry) {
        return some(entry).filter(ReactorPoms::isDirectoryEntry)
                   .flatMap(directory -> segments(module + "/" + directory))
                   .toResult(ReactorError.UnsupportedModule.FACTORY.apply(module, entry));
    }

    private static Result<List<String>> entries(String module, byte[] pom) {
        return Result.lift(thrown -> ReactorError.MalformedPom.FACTORY.apply(module, Causes.fromThrowable(thrown)),
                           () -> modules(factory().createXMLEventReader(new ByteArrayInputStream(pom))));
    }

    /// Feeds every event to a fresh scan. `XMLEventReader` iteration reports XML errors as unchecked
    /// exceptions, which the caller's `lift` turns into `MalformedPom`. The reader reads an in-memory array,
    /// so it holds no resource that needs closing.
    private static List<String> modules(XMLEventReader events) {
        var scan = new PomModulesScan();

        events.forEachRemaining(scan::accept);

        return scan.modules();
    }

    /// Namespace-unaware, DTDs and external entities off (XXE closed), as the report parser.
    private static XMLInputFactory factory() {
        var factory = XMLInputFactory.newDefaultFactory();

        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        return factory;
    }

    private static boolean isDirectoryEntry(String entry) {
        return ! entry.isBlank()
               && !entry.startsWith("/")
               && !entry.contains("\\")
               && !entry.endsWith(".xml");
    }

    private static Option<String> segments(String path) {
        return Stream.of(path.split("/"))
                     .filter(segment -> !segment.isEmpty() && !segment.equals("."))
                     .reduce(some(List.<String> of()),
                             (resolved, segment) -> resolved.flatMap(prefix -> applied(prefix, segment)),
                             (first, _) -> first)
                     .map(ReactorPoms::asPath);
    }

    private static String asPath(List<String> segments) {
        return String.join("/", segments);
    }

    /// The path after one more segment; none when `..` would climb above the root.
    private static Option<List<String>> applied(List<String> prefix, String segment) {
        if (!segment.equals("..")) {
            return some(Stream.concat(prefix.stream(), Stream.of(segment)).toList());
        }

        return prefix.isEmpty()
               ? none()
               : some(prefix.subList(0, prefix.size() - 1));
    }

    record unused() implements ReactorPoms {}
}
