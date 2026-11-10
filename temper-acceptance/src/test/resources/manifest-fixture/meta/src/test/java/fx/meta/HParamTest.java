package fx.meta;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HParamTest { @ParameterizedTest @ValueSource(ints = {1, 2}) void h(int x) {} }
