package fx.hold;

import org.junit.jupiter.api.Test;

/// A11: keeps the unit's container running for 120 s while it holds the port group.
class HoldSleepTest {
    @Test
    void sleeps() throws InterruptedException {
        Thread.sleep(120_000L);
    }
}
