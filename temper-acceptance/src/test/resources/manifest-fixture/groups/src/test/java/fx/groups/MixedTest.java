package fx.groups;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class MixedTest { @Test @Tag("fast") void tagged() {} @Test void untagged() {} }
