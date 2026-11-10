package org.pragmatica.temper.domain.manifest;

import java.util.List;


/// One plugin configuration value from the effective POM: a leaf element's trimmed text, or the trimmed
/// texts of a list element's children (`<includes><include>…</include></includes>`).
public sealed interface ConfigValue {
    record Text(String value) implements ConfigValue {
        public static Text text(String value) {
            return new Text(value);
        }
    }

    record Items(List<String> values) implements ConfigValue {
        public Items {
            values = List.copyOf(values);
        }

        public static Items items(List<String> values) {
            return new Items(values);
        }
    }
}
