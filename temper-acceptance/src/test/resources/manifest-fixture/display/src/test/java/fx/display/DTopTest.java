package fx.display;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Top level shown")
class DTopTest {
    @Test void top() {}

    @Nested class Plain { @Test void plain() {} }
}
