package fx.nested;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OuterTest {
    @Test void outer() {}

    @Nested class Inner {
        @Test void inner() {}

        @Nested class Deep { @Test void deep() {} }
    }
}
