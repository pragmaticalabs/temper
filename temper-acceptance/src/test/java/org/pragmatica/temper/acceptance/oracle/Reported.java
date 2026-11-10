package org.pragmatica.temper.acceptance.oracle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.temper.domain.report.ParsedTestcase;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.protocol.report.ReportParser;

/// The oracle's side of A12 (m1-design §9.2): what surefire and failsafe really reported, read straight from the
/// workspace's `target/*-reports/TEST-*.xml` (not through temper's report collection), and the comparison with a
/// manifest: missing = required − reported, drift = reported − (required ∪ optional) (ruling R-2).
final class Reported {
    private Reported() {}

    /// Every `<testcase>` classname of the report XML under `workspace` (the `.m2-local` repository skipped).
    static Set<String> classnames(Path workspace) throws IOException {
        try (Stream<Path> walk = Files.walk(workspace)) {
            var files = walk.filter(file -> !file.startsWith(workspace.resolve(".m2-local")))
                            .filter(file -> file.getFileName().toString().startsWith("TEST-") && file.toString().endsWith(".xml"))
                            .filter(file -> file.getParent().getFileName().toString().endsWith("-reports"))
                            .toList();
            var names = new TreeSet<String>();
            for (var file : files) {
                ReportParser.parse(file.getFileName().toString(), Files.readAllBytes(file))
                            .map(ReportFile::testcases)
                            .onFailure(cause -> {
                                throw new AssertionError(file + ": " + cause.message());
                            })
                            .onSuccess(testcases -> names.addAll(testcases.stream().map(ParsedTestcase::className).toList()));
            }
            return names;
        }
    }

    record Disagreement(Set<String> missing, Set<String> drift) {
        boolean none() {
            return missing.isEmpty() && drift.isEmpty();
        }
    }

    static Disagreement compare(Set<String> required, Set<String> optional, Set<String> reported) {
        var missing = required.stream().filter(name -> !reported.contains(name)).collect(Collectors.toCollection(TreeSet::new));
        var drift = reported.stream()
                            .filter(name -> !required.contains(name) && !optional.contains(name))
                            .collect(Collectors.toCollection(TreeSet::new));
        return new Disagreement(missing, drift);
    }
}
