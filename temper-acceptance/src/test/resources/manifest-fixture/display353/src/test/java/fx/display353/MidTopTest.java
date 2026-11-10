package fx.display353;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Mid top shown")
class MidTopTest {
    @Test void top() {}

    @Nested @DisplayName("Mid inner shown") class Inner { @Test void inner() {} }
}
