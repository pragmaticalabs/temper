package org.pragmatica.temper.domain.rerun;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.Recipe;

import static org.assertj.core.api.Assertions.assertThat;

/// maintainer ruling on the rerun bound: `max(30 min, Σ class durations × 1.5 + install + 5 min slack)`, capped at 6 h, rounded
/// up to the whole minute; history before weights before the 10 min default; the derivation is printed.
class RerunBoundsTest {
    private static final Duration INSTALL = Duration.ofMinutes(5);
    private static final String PACKAGE = "org.pragmatica.aether.ember.";
    /// The head and the three heaviest lines of `tools/ember-shard-weights.txt` at pragmatica 96a3eb3e3a, verbatim.
    private static final String WEIGHTS = """
        # Measured wall seconds per Ember test class (#1643). Source: CI run 38036277029 (rc4 7bfc7471), EmberUpgradeRunTest from run 38079013234.
        # Used only to balance shards; a class missing here still runs, at the default weight. Nested-class time is not in these lines, so a few weights read low.
        EmberAmnesiacRestartTest 162
        EmberClusterHermeticStorageTest 0
        EmberNodeReplacementTest 624
        EmberSameIdentityRelaunchTest 478
        EmberPartitionedCoreSelfFenceTest 403
        """;

    private static Recipe.Rerun.Bound bound(List<String> classes, Map<String, Duration> history) {
        return RerunBounds.bound(classes, history, RerunBounds.weights(WEIGHTS), INSTALL);
    }

    /// The maintainer's example: 624 + 478 + 403 s = 25.08 min; × 1.5 + 10 = 47.625 min, rounded up to 48.
    @Test
    void bound_threeHeaviestEmberClasses_fromWeights_is48Minutes() {
        var bound = bound(List.of(PACKAGE + "EmberNodeReplacementTest",
                                  PACKAGE + "EmberSameIdentityRelaunchTest",
                                  PACKAGE + "EmberPartitionedCoreSelfFenceTest"),
                          Map.of());

        assertThat(bound.value()).isEqualTo(Duration.ofMinutes(48));
        assertThat(bound.annotation()).isEqualTo("rerun-bound=48m (Σ 25.1m ×1.5 + install 5m + slack 5m)");
    }

    @Test
    void bound_smallClass_keepsTheFloor() {
        var bound = bound(List.of(PACKAGE + "EmberAmnesiacRestartTest"), Map.of());

        assertThat(bound.value()).isEqualTo(Duration.ofMinutes(30));
        assertThat(bound.annotation()).isEqualTo("rerun-bound=30m (floor; derived 14.1m)");
    }

    /// The stored history wins over a weight for the same class, even a far smaller one.
    @Test
    void bound_historyBeatsWeights() {
        var className = PACKAGE + "EmberNodeReplacementTest";

        var bound = bound(List.of(className), Map.of(className, Duration.ofSeconds(1200)));

        assertThat(RerunBounds.classTime(className, Map.of(className, Duration.ofSeconds(1200)), RerunBounds.weights(WEIGHTS))
                              .source()).isEqualTo(RerunBounds.Source.HISTORY);
        assertThat(bound.value()).isEqualTo(Duration.ofMinutes(40));
        assertThat(bound.annotation()).isEqualTo("rerun-bound=40m (Σ 20.0m ×1.5 + install 5m + slack 5m)");
    }

    /// Without history the weight of the outermost class's simple name is used, for a nested class too.
    @Test
    void bound_noHistory_usesTheWeights_byOutermostSimpleName() {
        var time = RerunBounds.classTime(PACKAGE + "EmberNodeReplacementTest$Restarts", Map.of(), RerunBounds.weights(WEIGHTS));

        assertThat(time.source()).isEqualTo(RerunBounds.Source.WEIGHTS);
        assertThat(time.duration()).isEqualTo(Duration.ofSeconds(624));
    }

    /// A weight of 0 is a weight, not an absence.
    @Test
    void bound_zeroWeight_isAWeight() {
        var time = RerunBounds.classTime(PACKAGE + "EmberClusterHermeticStorageTest", Map.of(), RerunBounds.weights(WEIGHTS));

        assertThat(time.source()).isEqualTo(RerunBounds.Source.WEIGHTS);
        assertThat(time.duration()).isEqualTo(Duration.ZERO);
    }

    /// Neither history nor weights: 10 min each, and the derivation names the classes.
    @Test
    void bound_neitherHistoryNorWeights_usesTheDefault_andSaysSo() {
        var bound = bound(List.of("org.x.BarTest", "org.x.FooTest"), Map.of());

        assertThat(bound.value()).isEqualTo(Duration.ofMinutes(40));
        assertThat(bound.annotation()).isEqualTo("rerun-bound=40m (Σ 20.0m ×1.5 + install 5m + slack 5m; default 10m: org.x.BarTest,org.x.FooTest)");
    }

    @Test
    void bound_aboveSixHours_isCapped() {
        var bound = bound(List.of("org.x.SlowTest"), Map.of("org.x.SlowTest", Duration.ofHours(5)));

        assertThat(bound.value()).isEqualTo(Duration.ofHours(6));
        assertThat(bound.annotation()).isEqualTo("rerun-bound=360m (cap; derived 460.0m)");
    }

    /// Exactly the floor is the Σ form, not "floor": 800 s × 1.5 + 10 min = 30 min.
    @Test
    void bound_exactlyTheFloor_isTheSumForm() {
        var bound = bound(List.of("org.x.FooTest"), Map.of("org.x.FooTest", Duration.ofSeconds(800)));

        assertThat(bound.annotation()).isEqualTo("rerun-bound=30m (Σ 13.3m ×1.5 + install 5m + slack 5m)");
    }

    /// Exactly the cap is the Σ form; a millisecond above it is "cap", shown as 360.0m.
    @Test
    void bound_atAndJustAboveTheCap() {
        var at = bound(List.of("org.x.FooTest"), Map.of("org.x.FooTest", Duration.ofSeconds(14_000)));
        var above = bound(List.of("org.x.FooTest"), Map.of("org.x.FooTest", Duration.ofSeconds(14_001)));

        assertThat(at.annotation()).isEqualTo("rerun-bound=360m (Σ 233.3m ×1.5 + install 5m + slack 5m)");
        assertThat(above.annotation()).isEqualTo("rerun-bound=360m (cap; derived 360.0m)");
    }

    /// An absurd history or weight cannot overflow: each class counts at most the cap.
    @Test
    void bound_absurdDurations_areCappedPerClass_neverOverflow() {
        var bound = bound(List.of("org.x.FooTest", "org.x.BarTest"),
                          Map.of("org.x.FooTest", Duration.ofSeconds(Long.MAX_VALUE / 2), "org.x.BarTest", Duration.ofDays(1000)));

        assertThat(bound.annotation()).isEqualTo("rerun-bound=360m (cap; derived 1090.0m)");
        assertThat(RerunBounds.weights("EmberHugeTest 99999999999999999999\nEmberFineTest 999999999\n"))
            .isEqualTo(Map.of("EmberFineTest", Duration.ofSeconds(999_999_999)));
    }

    /// The install allowance is the configured one.
    @Test
    void bound_usesTheGivenInstallAllowance() {
        var bound = RerunBounds.bound(List.of("org.x.FooTest"),
                                      Map.of("org.x.FooTest", Duration.ofMinutes(20)),
                                      Map.of(),
                                      Duration.ofSeconds(90));

        assertThat(bound.annotation()).isEqualTo("rerun-bound=37m (Σ 20.0m ×1.5 + install 1.5m + slack 5m)");
    }

    @Test
    void weights_skipCommentsBlankAndMalformedLines() {
        var weights = RerunBounds.weights("# EmberCommentedTest 99\n\nEmberGoodTest 12\nEmberBadTest twelve\nEmber Spaced Test 3\n  EmberIndentedTest 7  \r\n");

        assertThat(weights).isEqualTo(Map.of("EmberGoodTest", Duration.ofSeconds(12), "EmberIndentedTest", Duration.ofSeconds(7)));
    }

    @Test
    void simpleName_isTheOutermostClass() {
        assertThat(RerunBounds.simpleName("org.x.Outer$Inner$Deeper")).isEqualTo("Outer");
        assertThat(RerunBounds.simpleName("Outer")).isEqualTo("Outer");
    }
}
