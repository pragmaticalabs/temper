package org.pragmatica.temper.store.api;

/// A GO a T2 transaction invalidated: its id and the PR, head and base it was computed for, what
/// its `go-invalidated` event carries (a sibling of a consumed GO can be another PR's).
public record InvalidatedGo(String id, long pr, String sha, String base) {
    public static InvalidatedGo invalidatedGo(String id, long pr, String sha, String base) {
        return new InvalidatedGo(id, pr, sha, base);
    }
}
