package fx.groups;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class AllSlowTest { @Test @Tag("fast") @Tag("slow") void one() {} @Test @Tag("fast") @Tag("slow") void two() {} }
