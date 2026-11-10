package fx.failsafe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DisplayIT {
    @Test void it() {}

    @Nested @DisplayName("Integration shown") class Shown { @Test void shown() {} }
}
