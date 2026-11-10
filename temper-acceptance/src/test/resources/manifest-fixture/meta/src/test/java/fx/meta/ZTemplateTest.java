package fx.meta;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import java.util.stream.Stream;

@ExtendWith(ZTemplateTest.None.class)
class ZTemplateTest {
    @TestTemplate void t() {}

    static class None implements TestTemplateInvocationContextProvider {
        public boolean supportsTestTemplate(ExtensionContext c) { return true; }

        public boolean mayReturnZeroTestTemplateInvocationContexts(ExtensionContext c) { return true; }

        public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext c) { return Stream.empty(); }
    }
}
