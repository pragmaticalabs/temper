package fx.display;

import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DOuterTest {
    @Test void outer() {}

    @Nested @DisplayName("Verify class tests") class Shown { @Test @DisplayName("a shown method") void shown() {} }

    @Nested class Plain { @Test void plain() {} }

    @Nested @DisplayName("Same") class Same { @Test void same() {} }

    @Nested @DisplayName("Level one") class L1 {
        @Test void one() {}

        @Nested @DisplayName("Level two") class L2 { @Test void two() {} }

        @Nested class L2Plain { @Test void twoPlain() {} }
    }

    @Nested class M1 {
        @Test void m1() {}

        @Nested @DisplayName("Deep shown") class M2 { @Test void m2() {} }
    }

    @Nested @DisplayName("a.b$c d ü ✓ <&\"x\"") class Special { @Test void special() {} }

    @Nested @DisplayName(" Padded name ") class Padded { @Test void padded() {} }

    @Nested @Disabled @DisplayName("Off shown") class Off { @Test void off() {} }

    @Nested @DisplayName("Generated inside") class Generated {
        @ParameterizedTest @ValueSource(ints = {1, 2}) void param(int x) {}

        @TestFactory Stream<DynamicTest> factory() { return Stream.of(DynamicTest.dynamicTest("d", () -> {})); }
    }
}
