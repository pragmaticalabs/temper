package org.pragmatica.kiln.service.store.pg;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/// Which stored paths a pass's roots cover (§3.4: a COMPLETE pass deletes absent rows only within its roots). A root is a
/// word of `scan` (§3.2): `/abs/...` or `~/...`, where `*` matches within one path component. A root's matches are the
/// checkout paths themselves (`/home/builder/work/co/*` lists `/home/builder/work/co/<name>`).
///
/// Only an ABSOLUTE root covers anything (lead ruling, WP5 verification). A `~` root is expanded by the script on the
/// host, and kiln does not know that host's home: matching `~` to any home prefix let `~/ticketing` cover
/// `/home/builder/src/ticketing`, listed under `~/src/*`, and delete it. So a pass under a root that
/// still starts with `~` upserts and deletes nothing; the caller (WP8) expands `~` from the scan trailer's `home` (WP4)
/// before [CheckoutStore#applyPass][org.pragmatica.kiln.service.store.CheckoutStore#applyPass].
public sealed interface RootPattern {
    String ABSOLUTE = "/";
    String WITHIN_COMPONENT = "[^/]*";

    /// Every literal piece is quoted ([#glob]), so the expression always compiles: `Pattern.compile` cannot fail here.
    static Pattern pattern(String root) {
        return Pattern.compile("^" + glob(root) + "$");
    }

    /// Whether any of `roots` covers `path`.
    static boolean covered(List<Pattern> roots, String path) {
        return roots.stream()
                    .anyMatch(root -> root.matcher(path)
                                          .matches());
    }

    /// The patterns of the absolute roots among `roots`; a `~` root (or any other) gives none, so it covers nothing. The
    /// filter states the rule: a literal pattern of such a root would match no stored path either, since every
    /// `CheckoutPath` starts with `/` (so PIT reads dropping the filter as equivalent).
    static List<Pattern> patterns(List<String> roots) {
        return roots.stream()
                    .filter(root -> root.startsWith(ABSOLUTE))
                    .map(RootPattern::pattern)
                    .toList();
    }

    /// Literal text quoted, each `*` within one component.
    private static String glob(String text) {
        return Arrays.stream(text.split("\\*", -1))
                     .map(Pattern::quote)
                     .collect(Collectors.joining(WITHIN_COMPONENT));
    }

    record unused() implements RootPattern {}
}
