package org.pragmatica.temper.controller.api;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.store.api.JobManifest;


/// `GET /jobs/{id}/manifest` (m1-design §6; the T1 harness reads it). Pure. `missing` (required, not reported) and
/// `drift` (reported, neither required nor optional) compare class names exactly as the verdict does (`Verdicts`,
/// ruling R-2), uncapped and sorted. They are judged only when the manifest is computed, because only then does the
/// verdict judge coverage; otherwise both are empty and `computed` says why. `pruned` (ruling R-7) is the store's.
public sealed interface ManifestViews {
    static ManifestView manifestView(JobManifest manifest, List<TestcaseRow> rows) {
        var required = classNames(manifest.entries().stream());
        var expected = classNames(Stream.concat(manifest.entries().stream(),
                                                manifest.optional().stream()));
        var reported = rows.stream().map(TestcaseRow::className).collect(Collectors.toUnmodifiableSet());

        return ManifestView.manifestView(manifest.computed(),
                                         manifest.pruned(),
                                         wire(manifest.entries()),
                                         wire(manifest.optional()),
                                         judged(manifest, required, reported),
                                         judged(manifest, reported, expected),
                                         manifest.notReached());
    }

    private static Set<String> classNames(Stream<ManifestEntry> entries) {
        return entries.map(ManifestEntry::className)
                      .collect(Collectors.toUnmodifiableSet());
    }

    private static List<ManifestEntryDto> wire(List<ManifestEntry> entries) {
        return entries.stream()
                      .map(ManifestEntryDto::manifestEntryDto)
                      .toList();
    }

    /// `from − without`, sorted; empty when the manifest is not computed.
    private static List<String> judged(JobManifest manifest, Set<String> from, Set<String> without) {
        return manifest.computed()
               ? List.copyOf(from.stream()
                                 .filter(name -> !without.contains(name))
                                 .collect(Collectors.toCollection(TreeSet::new)))
               : List.of();
    }

    record unused() implements ManifestViews {}
}
