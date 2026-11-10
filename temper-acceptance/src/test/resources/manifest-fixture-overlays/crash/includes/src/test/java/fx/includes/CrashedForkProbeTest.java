package fx.includes;

import org.junit.jupiter.api.Test;

/// T2 (m1-design §8.2): the fork dies without a report for this class; the module is first in the reactor, so the
/// Gate stops here.
class CrashedForkProbeTest {
    @Test
    void crashTheFork() {
        System.exit(3);
    }
}
