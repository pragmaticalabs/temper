package org.pragmatica.temper.store.api;

/// A merge as temper dedupes it (`merges` primary key): `sha` is the PR head kiln reported, or
/// `unknown` when its `pr-state` row carried none.
public record MergeKey(String repo, long pr, String sha) {
    public static final String UNKNOWN = "unknown";

    public static MergeKey mergeKey(String repo, long pr, String sha) {
        return new MergeKey(repo, pr, sha);
    }
}
