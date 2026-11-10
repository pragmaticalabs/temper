package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.temper.protocol.message.ManifestEntryDto;


/// `GET /api/v1/jobs/{id}/manifest` (m1-design §6; the T1 harness reads it): whether every unit with a manifest
/// spec delivered a computed manifest; whether retention pruned its rows (`pruned`, ruling R-7: computed, not pruned
/// and empty always means "no tests"); the required classes (`entries`) and the `optional` ones (ruling R-2); and the
/// uncapped `missing`, `drift` and `notReached` lists.
public record ManifestView(boolean computed,
                           boolean pruned,
                           List<ManifestEntryDto> entries,
                           List<ManifestEntryDto> optional,
                           List<String> missing,
                           List<String> drift,
                           List<String> notReached) {
    public ManifestView {
        entries = List.copyOf(entries);
        optional = List.copyOf(optional);
        missing = List.copyOf(missing);
        drift = List.copyOf(drift);
        notReached = List.copyOf(notReached);
    }

    public static ManifestView manifestView(boolean computed,
                                            boolean pruned,
                                            List<ManifestEntryDto> entries,
                                            List<ManifestEntryDto> optional,
                                            List<String> missing,
                                            List<String> drift,
                                            List<String> notReached) {
        return new ManifestView(computed, pruned, entries, optional, missing, drift, notReached);
    }
}
