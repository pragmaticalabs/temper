package fx.meta;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.stream.Stream;

class IFactoryTest { @TestFactory Stream<DynamicTest> f() { return Stream.empty(); } }
