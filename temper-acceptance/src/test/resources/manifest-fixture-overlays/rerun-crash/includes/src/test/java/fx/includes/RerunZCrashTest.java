package fx.includes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/// RerunIT `rerun-crash`: red in the Gate; in the rerun (selected by `-Dtest`) it kills its fork, so it leaves no report.
class RerunZCrashTest {
    @Test
    void crashesTheForkWhenSelected() {
        if (System.getProperty("test") != null) {
            Runtime.getRuntime().halt(3);
        }
        fail("red in the gate");
    }
}
