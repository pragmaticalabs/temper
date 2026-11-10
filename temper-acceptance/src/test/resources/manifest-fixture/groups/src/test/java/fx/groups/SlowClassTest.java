package fx.groups;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("slow")
class SlowClassTest { @Test @Tag("fast") void fastButClassSlow() {} }
