package org.pragmatica.temper.acceptance.trust;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;


/// The T1 criterion for one SHA (spec T1, m1-design §8.1 step 4):
///
/// - `countsEqual`: temper's `<testcase>` count equals bb-report's `<testcase> elements` (never the `tests=` sum);
/// - `failedOnlyBb` / `failedOnlyTemper`: the class-level failed sets, both empty when they agree;
/// - `emptyEqual`: temper's EMPTY verdict iff bb-report exit 3;
/// - `manifestComputed`: the manifest was computed, so `missing` and `drift` were judged at all (a WP7 addition: a
///   manifest that was not computed judges nothing, and must not read as zero disagreements);
/// - `drift` and `missing` must be empty;
/// - `comparable`: neither side shows a fork crash (ruling R-14). `crashEvidence` holds the fork-crash lines of
///   temper's log tail, `bbCrashEvidence` those of bb's `.bb/build.log`. A SHA with either is NOT COMPARABLE: it never
///   passes, it does not count toward T1's three SHAs, and its crash lines and missing classes are listed for the
///   explanation a human writes (§8.1 step 4: "recorded and explained, never waved away"). Crashed forks are T2's
///   subject (§8.2).
///
/// `caseLevelDiff` is the diagnostic case-level diff (temper's rows against bb's XML), not part of the criterion.
public record ShaComparison(String sha,
                            boolean countsEqual,
                            Set<String> failedOnlyBb,
                            Set<String> failedOnlyTemper,
                            boolean emptyEqual,
                            boolean manifestComputed,
                            Set<String> missing,
                            Set<String> drift,
                            Option<String> crashEvidence,
                            Option<String> bbCrashEvidence,
                            List<String> caseLevelDiff) {
    public ShaComparison {
        failedOnlyBb = Set.copyOf(failedOnlyBb);
        failedOnlyTemper = Set.copyOf(failedOnlyTemper);
        missing = Set.copyOf(missing);
        drift = Set.copyOf(drift);
        caseLevelDiff = List.copyOf(caseLevelDiff);
    }

    public static ShaComparison shaComparison(String sha,
                                              boolean countsEqual,
                                              Set<String> failedOnlyBb,
                                              Set<String> failedOnlyTemper,
                                              boolean emptyEqual,
                                              boolean manifestComputed,
                                              Set<String> missing,
                                              Set<String> drift,
                                              Option<String> crashEvidence,
                                              Option<String> bbCrashEvidence,
                                              List<String> caseLevelDiff) {
        return new ShaComparison(sha,
                                 countsEqual,
                                 failedOnlyBb,
                                 failedOnlyTemper,
                                 emptyEqual,
                                 manifestComputed,
                                 missing,
                                 drift,
                                 crashEvidence,
                                 bbCrashEvidence,
                                 caseLevelDiff);
    }

    /// The failed sets agree.
    public boolean failedEqual() {
        return failedOnlyBb.isEmpty() && failedOnlyTemper.isEmpty();
    }

    /// Zero manifest disagreements: computed, no drift, no missing class.
    public boolean manifestAgrees() {
        return manifestComputed
               && drift.isEmpty()
               && missing.isEmpty();
    }

    /// Neither temper's log tail nor bb's build log shows a fork crash (R-14).
    public boolean comparable() {
        return crashEvidence.isEmpty() && bbCrashEvidence.isEmpty();
    }

    /// Comparable, and every check agrees.
    public boolean passed() {
        return comparable()
               && countsEqual
               && failedEqual()
               && emptyEqual
               && manifestAgrees();
    }
}
