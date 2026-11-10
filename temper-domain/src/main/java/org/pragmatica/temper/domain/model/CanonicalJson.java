package org.pragmatica.temper.domain.model;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;


/// Minimal canonical JSON writer for dedup input (m0-design §3.5): keys sorted, no whitespace, lists in
/// submitted order. Absent options are omitted by the callers, never written as `null`.
public sealed interface CanonicalJson {
    /// A JSON string literal with RFC 8259 escaping.
    static String string(String value) {
        return value.chars()
                    .mapToObj(CanonicalJson::escape)
                    .collect(Collectors.joining("", "\"", "\""));
    }

    /// A JSON array of string literals, in the given order.
    static String stringArray(List<String> values) {
        return values.stream()
                     .map(CanonicalJson::string)
                     .collect(Collectors.joining(",", "[", "]"));
    }

    /// A JSON object from already-rendered member values; keys are emitted in sorted order.
    static String object(Map<String, String> renderedMembers) {
        return new TreeMap<>(renderedMembers).entrySet()
                                             .stream()
                                             .map(CanonicalJson::member)
                                             .collect(Collectors.joining(",", "{", "}"));
    }

    private static String member(Map.Entry<String, String> member) {
        return string(member.getKey()) + ":" + member.getValue();
    }

    private static String escape(int ch) {
        return switch (ch) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> ch < 0x20
                       ? String.format("\\u%04x", ch)
                       : String.valueOf((char) ch);
        };
    }

    record unused() implements CanonicalJson {}
}
