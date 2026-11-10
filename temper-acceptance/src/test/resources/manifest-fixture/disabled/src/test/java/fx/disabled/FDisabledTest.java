package fx.disabled;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("off")
class FDisabledTest { @Test void f1() {} @Test void f2() {} }
