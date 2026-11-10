package fx.nested;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OnlyNestedTest { @Nested class Child { @Test void child() {} } }
