package org.pragmatica.kiln.core.summary;

/// The checkout counts the summary lists (spec §7): `NEEDS-SALVAGE` and `STALE-UNOWNED`.
public record Counts(int needsSalvage, int staleUnowned) {}
