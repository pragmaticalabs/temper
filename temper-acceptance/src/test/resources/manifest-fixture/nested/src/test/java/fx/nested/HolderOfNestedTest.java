package fx.nested;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HolderOfNestedTest { @Test void w() {} static class Holder { @Nested class X { @Test void x() {} } } }
