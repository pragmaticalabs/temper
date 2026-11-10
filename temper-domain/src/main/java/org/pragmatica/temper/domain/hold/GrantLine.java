package org.pragmatica.temper.domain.hold;

import java.util.regex.Pattern;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// bb-hold's grant announcement (m0-design §12.3, maintainer ruling M1-14): it prints `granted <ticket>` on stderr,
/// on a line of its own, once the port group is granted. Every other bb-hold message also goes to stderr, so
/// only a whole line of exactly that shape counts; a line that merely contains the word does not.
public sealed interface GrantLine {
    Pattern GRANTED = Pattern.compile("(?m)^granted (\\S+)\\r?$");

    /// The ticket of the first grant line in `stderrText`; none before the grant.
    static Option<String> ticket(String stderrText) {
        var matcher = GRANTED.matcher(stderrText);

        return matcher.find()
               ? some(matcher.group(1))
               : none();
    }

    record unused() implements GrantLine {}
}
