package fx.groups;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PartSlowTest { @Test @Tag("fast") void kept() {} @Test @Tag("fast") @Tag("slow") void dropped() {} }
