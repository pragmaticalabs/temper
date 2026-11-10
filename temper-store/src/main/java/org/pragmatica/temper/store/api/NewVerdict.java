package org.pragmatica.temper.store.api;

/// A verdict to register, its fields already validated by the route. `by` is the design's
/// self-declared `declared_by`. Its [Basis] is either the report the body names, or a carry (F8) whose row copies the
/// report fields of the ACCEPT registered for (repo, pr, `from`) and is itself ACCEPT.
public record NewVerdict(String repo, long pr, String sha, String base, String verdict, String by, Basis basis) {
    /// Where the row's report fields come from.
    public sealed interface Basis {
        /// The report the body names: its absolute path and the sha256 of its raw bytes.
        record Report(String path, String sha256) implements Basis {}

        /// The ACCEPT registered at `from` (the carried SHA), whose report fields the row copies.
        record Carried(String from) implements Basis {}
    }

    public static NewVerdict newVerdict(String repo,
                                        long pr,
                                        String sha,
                                        String base,
                                        String verdict,
                                        String by,
                                        Basis basis) {
        return new NewVerdict(repo, pr, sha, base, verdict, by, basis);
    }
}
