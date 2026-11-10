package org.pragmatica.temper.protocol.api;

import java.util.Locale;
import java.util.stream.Collectors;


/// One quoted field on one line (F6), shared by every one-line form (CLI lines, digest text, event lines): `\\`, `\"`,
/// `\n`, `\r`, `\t`, and `\\uXXXX` for every other control character, format character or line/paragraph separator
/// (ESC, U+0085, U+2028, U+202E, U+200B, U+FEFF, ...), so a value can neither break the line, reach the terminal as an
/// escape sequence, nor reorder or hide text with a bidi or zero-width character (A7).
public sealed interface LineText {
    static String quoted(String text) {
        return text.codePoints()
                   .mapToObj(LineText::escaped)
                   .collect(Collectors.joining("", "\"", "\""));
    }

    private static String escaped(int codePoint) {
        return switch (codePoint) {
            case '\\' -> "\\\\";
            case '"' -> "\\\"";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> staysRaw(codePoint)
                       ? Character.toString(codePoint)
                       : String.format(Locale.ROOT, "\\u%04x", codePoint);
        };
    }

    private static boolean staysRaw(int codePoint) {
        var type = Character.getType(codePoint);

        return type != Character.CONTROL
               && type != Character.FORMAT
               && type != Character.LINE_SEPARATOR
               && type != Character.PARAGRAPH_SEPARATOR;
    }

    record unused() implements LineText {}
}
