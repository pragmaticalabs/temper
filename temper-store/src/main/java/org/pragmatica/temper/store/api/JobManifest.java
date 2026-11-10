package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.temper.domain.manifest.ManifestEntry;


/// A job's stored manifest (`GET /jobs/{id}/manifest`, m1-design §6): `computed` is true when every unit with a
/// manifest spec delivered a computed manifest; `pruned` is true once retention deleted the job's `manifests` rows
/// (ruling R-7), so empty `entries` with `pruned` false always means "no tests"; `entries` are the required classes of
/// all units and `optional` the classes that may or may not report (ruling R-2), each in (module, class, runner)
/// order; `notReached` the modules never reached (sorted, distinct); `unresolvedAnnotations` the number of distinct
/// annotation types the rules could not resolve.
public record JobManifest(boolean computed,
                          boolean pruned,
                          List<ManifestEntry> entries,
                          List<ManifestEntry> optional,
                          List<String> notReached,
                          int unresolvedAnnotations) {
    public JobManifest {
        entries = List.copyOf(entries);
        optional = List.copyOf(optional);
        notReached = List.copyOf(notReached);
    }

    public static JobManifest jobManifest(boolean computed,
                                          boolean pruned,
                                          List<ManifestEntry> entries,
                                          List<ManifestEntry> optional,
                                          List<String> notReached,
                                          int unresolvedAnnotations) {
        return new JobManifest(computed, pruned, entries, optional, notReached, unresolvedAnnotations);
    }
}
