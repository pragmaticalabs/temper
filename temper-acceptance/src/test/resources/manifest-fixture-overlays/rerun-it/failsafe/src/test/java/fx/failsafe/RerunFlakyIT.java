package fx.failsafe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/// RerunIT (ruling R-M2-16): a failsafe class red only on its first, unselected run (the Gate runs the module's
/// integration tests without `-Dit.test`; a rerun selects this class with it).
class RerunFlakyIT {
    @Test
    void passesOnlyWhenSelected() {
        assertNotNull(System.getProperty("it.test"), "red on its first, unselected run");
    }
}
