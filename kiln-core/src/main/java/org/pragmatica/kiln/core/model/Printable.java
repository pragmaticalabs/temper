package org.pragmatica.kiln.core.model;

import java.util.regex.Pattern;


/// The characters no kiln text value may hold: every Unicode control character (`\p{Cc}`: C0, DEL and C1, which
/// includes U+0085 NEL) and the separators U+2028 and U+2029, which some readers take as a line end. `\p{Cntrl}` is
/// ASCII only, so it is not used.
public sealed interface Printable {
    /// A whole value free of those characters; the empty string matches, so callers check presence first.
    Pattern TEXT = Pattern.compile("^[^\\p{Cc}\\u2028\\u2029]*$");

    record unused() implements Printable {}
}
