package fx.display352;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Old IT shown")
class OldShownIT {
    @Test void top() {}

    @Nested @DisplayName("Old IT inner") class Inner { @Test void inner() {} }
}
