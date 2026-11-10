package fx.display;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DGen_ClassTest {
    @Test void top_level() {}

    @Nested class Inner_Gen { @Test void inner_gen() {} }
}
