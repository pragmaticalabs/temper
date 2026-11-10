package fx.includes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/// RerunIT, two modules and both plugins (ruling R-M2-16): a surefire class red only on its first, unselected run (the
/// Gate runs the module without `-Dtest`; a rerun selects this class with it).
class RerunFlakyTest {
    @Test
    void passesOnlyWhenSelected() {
        assertNotNull(System.getProperty("test"), "red on its first, unselected run");
    }
}
