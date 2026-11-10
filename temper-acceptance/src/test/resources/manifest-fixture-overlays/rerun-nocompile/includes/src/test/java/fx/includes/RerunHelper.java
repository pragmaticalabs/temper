package fx.includes;

import java.util.ArrayList;
import java.util.List;

/// RerunIT `rerun-nocompile`: a test helper with a raw type, which the module's rerun build (`-Werror`) refuses.
final class RerunHelper {
    private RerunHelper() {}

    @SuppressWarnings("unchecked")
    static String reason() {
        List raw = new ArrayList();
        raw.add("red in the gate");
        return (String) raw.get(0);
    }
}
