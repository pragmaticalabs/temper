package fx.includes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/// RerunIT `rerun-nocompile`: red in the Gate; the rerun never compiles it (its helper is excluded there).
class RerunUsesHelperTest {
    @Test
    void failsInTheGate() {
        fail(RerunHelper.reason());
    }
}
