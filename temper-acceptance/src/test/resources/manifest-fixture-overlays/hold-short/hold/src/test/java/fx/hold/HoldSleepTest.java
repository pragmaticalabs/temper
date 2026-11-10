package fx.hold;

import org.junit.jupiter.api.Test;

/// A11: keeps the unit's container running for 5 s while it holds the port group.
class HoldSleepTest {
    @Test
    void sleeps() throws InterruptedException {
        Thread.sleep(5_000L);
    }
}
