package org.pragmatica.temper.store.api;

/// An evidence document to store with its acceptance (m3-design §7.1, `task_evidence`): its schema, the document and the
/// per-claim checks as JSON text, and the document's sha256. WP-1 stores accepted documents only; validation (and the
/// stored refusals) are WP-2's.
public record NewEvidence(String schema, String document, String checks, String sha256) {
    public static NewEvidence newEvidence(String schema, String document, String checks, String sha256) {
        return new NewEvidence(schema, document, checks, sha256);
    }
}
