package fx.includes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/// RerunIT (m2-design §5.6): class B, red only on its first run. The design's marker file cannot outlive a run here: a
/// unit container mounts only its own job's workspace, so no path survives from the Gate's container to the rerun's.
/// The first run is told apart instead by the selection surefire passes to the test JVM: the Gate runs the module
/// without `-Dtest`, a rerun selects this class with it.
class RerunFlakyTest {
    @Test
    void passesOnlyWhenSelected() {
        assertNotNull(System.getProperty("test"), "red on its first, unselected run");
    }
}
