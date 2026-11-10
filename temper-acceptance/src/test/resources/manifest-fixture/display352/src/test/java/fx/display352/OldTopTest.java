package fx.display352;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Old top shown")
class OldTopTest {
    @Test void top() {}

    @Nested @DisplayName("Old inner shown") class Inner { @Test void inner() {} }

    @Nested class Plain { @Test void plain() {} }

    @Nested @DisplayName("Old level one") class L1 {
        @Nested @DisplayName("Old level two") class L2 { @Test void two() {} }
    }
}
