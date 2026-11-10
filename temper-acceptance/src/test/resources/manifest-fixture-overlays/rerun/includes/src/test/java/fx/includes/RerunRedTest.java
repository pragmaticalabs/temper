package fx.includes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/// RerunIT (m2-design §5.6): class A, red on every run, in the Gate and in its rerun alike.
class RerunRedTest {
    @Test
    void failsEveryRun() {
        fail("red on every run");
    }
}
