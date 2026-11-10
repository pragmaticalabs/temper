package org.pragmatica.temper.store.api;

import java.nio.file.Path;


/// A tarball the controller pulled and renamed into the evidence store (m1-design §3.2): its final path, size and
/// sha256 (lower-case hex). The path follows the §3.1 layout `<root>/jobs/<job>/units/<unit>/<file>`; the result
/// record's `evidence.path` is the `<root>/jobs/<job>` directory derived from it.
public record StoredEvidence(Path path, long bytes, String sha256) {
    public static StoredEvidence storedEvidence(Path path, long bytes, String sha256) {
        return new StoredEvidence(path, bytes, sha256);
    }
}
