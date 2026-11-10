package fx.inherit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class CmLocalNestTest extends fx.nested.CmAbsBase { @Nested public class Own { @Test void z() {} } }
