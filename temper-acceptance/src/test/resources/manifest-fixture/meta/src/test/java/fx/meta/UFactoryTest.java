package fx.meta;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.stream.Stream;

class UFactoryTest { @TestFactory Stream<DynamicTest> f() { return Stream.of(DynamicTest.dynamicTest("u", () -> {})); } }
