package fx.display;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DGenNestedTest {
    @Test void outer() {}

    @Nested @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class) class Under_Score { @Test void under_score() {} }

    @Nested @MetaShown class Meta { @Test void meta() {} }

    @Nested @DisplayNameGeneration(DisplayNameGenerator.IndicativeSentences.class) class Sentence { @Test void sentence() {} }
}
