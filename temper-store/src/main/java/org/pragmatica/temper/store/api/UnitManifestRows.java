package org.pragmatica.temper.store.api;

import org.pragmatica.temper.domain.manifest.ManifestOutcome;


/// A unit's manifest as delivered by its folded `UnitManifest` chunks (m1-design §2.5). A computed outcome becomes
/// one `manifests` row per entry plus a `manifest_units` row (`computed`, `not_reached`, `unresolved`); an
/// unsupported one becomes a `manifest_units` row (`unsupported`, `detail` = the reason) and the unit's error
/// reason `manifest: <reason>`.
public record UnitManifestRows(ManifestOutcome outcome) {
    public static UnitManifestRows unitManifestRows(ManifestOutcome outcome) {
        return new UnitManifestRows(outcome);
    }
}
