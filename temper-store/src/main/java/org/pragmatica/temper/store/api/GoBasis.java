package org.pragmatica.temper.store.api;

/// What a GO was computed for: the PR at `sha`, its `base` and the base tip the gate judged,
/// and `fleetSeq`, the fleet head read before the gate's server reads began. The match rules read only these.
public record GoBasis(String repo, long pr, String sha, String base, String baseTip, long fleetSeq) {
    public static GoBasis goBasis(String repo, long pr, String sha, String base, String baseTip, long fleetSeq) {
        return new GoBasis(repo, pr, sha, base, baseTip, fleetSeq);
    }
}
