package fx.disabled;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SOuterTest { @Test void s() {} @Nested @Disabled class Off { @Test void x() {} } @Nested class On { @Test void y() {} } }
