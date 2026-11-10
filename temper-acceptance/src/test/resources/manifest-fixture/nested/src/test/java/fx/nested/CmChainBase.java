package fx.nested;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public abstract class CmChainBase { @Nested public class In { @Test void x() {} @Nested public class Deep { @Test void d() {} } } }
