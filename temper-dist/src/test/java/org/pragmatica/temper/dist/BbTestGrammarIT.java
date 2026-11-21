package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/// GRAMMAR of ops/bb/bb-test (bto26, CTO ruling on vbt10): bb-test uses only constructs the audit fully classifies; anything else is a
/// violation. A future miss is answered by NARROWING the grammar, never by adding a detection case.
/// G3: one positive control per excluded class, vbt10's M1-M9 among them: a fixture copy of bb-test with one bare instance added in the
/// launch, which BbTestGrammar must flag at that line (the read audit runs on the same copy and must fail too). No maven-in-maven: the
/// audit runs directly. G4: the allowlist is shrink-only, its size pinned here; a stale entry is a problem of the audit.
class BbTestGrammarIT {
    /// The allowlist's pinned size. SHRINK-ONLY: lower it when an entry is removed; raising it needs a CTO ruling naming the entry.
    static final int ALLOWLIST_PIN = 1;

    private static final String ANCHOR = "  guard launcher_ok\n";

    static Stream<Arguments> excludedClasses() {
        return Stream.of(Arguments.of("M1 eval", "eval 'cat \"$CO/f\"'"),
                         Arguments.of("M1 trap with a body", "trap 'cat \"$CO/f\"' EXIT"),
                         Arguments.of("M2 heredoc", "sed -n 1p <<EOF\n$(cat \"$CO/f\")\nEOF"),
                         Arguments.of("M2 heredoc <<-", "sed -n 1p <<-EOF\n$(cat \"$CO/f\")\nEOF"),
                         Arguments.of("M2 here-string", "sed -n 1p <<< \"$CO\""),
                         Arguments.of("M2 $(< f)", "X=$(< \"$CO/f\")"),
                         Arguments.of("M2 backticks", "echo `cat \"$CO/f\"`"),
                         Arguments.of("M3 $(..) in ${..}", "echo ${x:-$(cat \"$CO/f\")}"),
                         Arguments.of("M3 $(< f) in quoted ${..}", "echo \"${x:-$(< \"$CO/f\")}\""),
                         Arguments.of("M3 $(..) in $((..))", "echo $(( $(cat \"$CO/f\") ))"),
                         Arguments.of("M4 $(..) in a quoted argument", "echo x > \"$(echo \"$CO\")/f\""),
                         Arguments.of("M4 $(..) in a bare argument", "mkdir $(printf %s \"$CO\")/f"),
                         Arguments.of("M5 printf -v", "printf -v V '%s' \"$CO/f\""),
                         Arguments.of("M5 ${!..}", "n=CO; echo \"${!n}/f\""),
                         Arguments.of("M5 declare -n", "declare -n r=CO"),
                         Arguments.of("M5 local -n", "local -n r=CO"),
                         Arguments.of("M5 declare -g", "declare -g V=\"$CO/f\""),
                         Arguments.of("M5 local -g", "local -g V=\"$CO/f\""),
                         Arguments.of("M6 exec with a redirection", "exec 3< \"$CO/f\""),
                         Arguments.of("M6 <>", "echo hi <> \"$CO/f\""),
                         Arguments.of("M6 >& onto a path", "echo hi >&\"$CO/f\""),
                         Arguments.of("M6 >& onto a word", "echo hi >& file"),
                         Arguments.of("M6 a redirection with no command", "> \"$CO/f\""),
                         Arguments.of("M7 $OLDPWD", "echo \"$OLDPWD/x\""),
                         Arguments.of("M7 ~-", "echo ~-/x"),
                         Arguments.of("M8 $'..'", "echo $'a\\'b'"),
                         Arguments.of("M8 a phantom heredoc in quotes", "echo \"<<ZZ\"\n  cat \"$CO/f\""),
                         Arguments.of("M9 glob", "echo \"$CO\"/.bb/*"),
                         Arguments.of("path: an unquoted expansion", "echo $CO"),
                         Arguments.of("path: ~", "echo ~/x"),
                         Arguments.of("path: brace expansion", "echo \"$CO\"/{a,b}"),
                         Arguments.of("path: process substitution", "sed -n 1p <(echo x)"),
                         Arguments.of("statement: (( .. ))", "(( x = 1 ))"));
    }

    private static String source() throws Exception {
        var source = Files.readString(BbFixtures.OPS_BB.resolve("bb-test"));

        assertThat(source).contains(ANCHOR);

        return source;
    }

    /// Probe: a class's rule removed from BbTestGrammar -> red (its row).
    @ParameterizedTest(name = "{0}")
    @MethodSource("excludedClasses")
    void aBareInstanceOfAnExcludedClass_isAGrammarViolationAtItsLine(String name, String form) throws Exception {
        var source = source();
        var line = (int) source.substring(0, source.indexOf(ANCHOR) + ANCHOR.length()).chars().filter(c -> c == '\n').count() + 1;
        var fixture = source.replace(ANCHOR, ANCHOR + "  " + form + "\n");
        var last = line + (int) form.chars().filter(c -> c == '\n').count();

        assertThat(BbTestGrammar.check(source).violations()).as("bb-test itself is inside the grammar").isEmpty();
        assertThat(BbTestGrammar.check(fixture).violations())
                  .as(name + ": a grammar violation, with its line and reason, at lines " + line + ".." + last)
                  .anyMatch(v -> v.line() >= line && v.line() <= last && v.toString().contains("VIOLATION (grammar, "));
        assertThat(BbTestReadAudit.audit(fixture).problems()).as(name + ": the audit fails on it").isNotEmpty();
    }

    /// Probe: ALLOWLIST_PIN bumped, or an entry added -> red.
    @Test
    void theAllowlist_isNamedReasonedAndPinned() {
        assertThat(BbTestGrammar.ALLOWLIST).as("the allowlist size is pinned (shrink-only)").hasSize(ALLOWLIST_PIN);
        assertThat(BbTestGrammar.ALLOWLIST).allMatch(a -> !a.name().isBlank() && a.reason().length() > 40);
    }

    /// Probe: an entry's target deleted from bb-test -> the entry is stale -> the audit fails (everyCheckoutRead_isBounded red).
    @Test
    void aStaleAllowlistEntry_failsTheAudit() throws Exception {
        var source = source();
        var scan = "  for f in \"$Q\"/running/*; do [ -f \"$f\" ] || continue; tco=$(field \"$f\" co); [ \"$tco\" != \"$CO\" ] || { TICKET=${f##*/};"
                   + " N=$((N + 1)); }; done\n";

        assertThat(source).contains(scan);
        assertThat(BbTestGrammar.check(source).stale()).as("bb-test uses every entry").isEmpty();
        var stale = BbTestGrammar.check(source.replace(scan, "  TICKET=x; N=1\n"));

        assertThat(stale.stale()).containsExactly("queue-scan");
        assertThat(BbTestReadAudit.audit(source.replace(scan, "  TICKET=x; N=1\n")).problems())
                  .anyMatch(p -> p.contains("stale allowlist entry queue-scan"));
    }
}
