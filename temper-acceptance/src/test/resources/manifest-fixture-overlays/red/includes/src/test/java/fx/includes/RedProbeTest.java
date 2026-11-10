package fx.includes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/// A red SHA for T1 (spec T1 "including ≥ 1 red", F7): one plain test failure, reported, no fork crash.
class RedProbeTest {
    @Test
    void failsOnPurpose() {
        fail("red on purpose");
    }
}
