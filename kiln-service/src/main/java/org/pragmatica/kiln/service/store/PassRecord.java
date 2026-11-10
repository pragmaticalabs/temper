package org.pragmatica.kiln.service.store;

/// What [CheckoutStore#applyPass] recorded: the outcome and the rows written. The ingest answers it as
/// `{"outcome":…,"rows":N}`.
public record PassRecord(PassOutcome outcome, int rows) {}
