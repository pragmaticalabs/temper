package fx.includes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/// RerunIT `rerun-crash`: red on its first, unselected run; in the rerun it passes, and runs before `RerunZCrashTest`
/// in the same fork (the module's runOrder is alphabetical), so its report is written before the fork dies.
class RerunEarlyTest {
    @Test
    void passesOnlyWhenSelected() {
        assertNotNull(System.getProperty("test"), "red on its first, unselected run");
    }
}
