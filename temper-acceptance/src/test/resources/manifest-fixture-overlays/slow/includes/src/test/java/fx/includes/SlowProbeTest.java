package fx.includes;

import org.junit.jupiter.api.Test;

/// A unit that is still running when its host goes dark (m4-design §12.3, `MultiHostLostIT`, `SideEffectNotRequeuedIT`):
/// its first attempt's container outlives the worker, as on a host the network lost.
class SlowProbeTest {
    @Test
    void sleepsPastTheBlackhole() throws InterruptedException {
        Thread.sleep(20_000);
    }
}
