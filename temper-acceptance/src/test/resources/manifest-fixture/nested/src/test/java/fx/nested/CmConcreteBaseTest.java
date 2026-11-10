package fx.nested;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class CmConcreteBaseTest { @Test void own() {} @Nested public class In2 { @Test void y() {} } }
