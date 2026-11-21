package org.pragmatica.temper.dist;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/// GRAMMAR of ops/bb/bb-test (bto25, CTO ruling on vbt10): bb-test uses only constructs the audit fully classifies; anything else is a
/// violation. A future miss is answered by NARROWING the grammar, never by adding a detection case.
///
/// The classified subset, as closed lists (anything outside them is a VIOLATION with its line and reason):
/// - QUOTING: '...' (no escapes, may span lines), "..." (escapes \" \\ \$ \`, and the expansions below), a backslash escape, a
///   backslash-newline continuation. Not: $'...', $"...", backticks.
/// - EXPANSIONS: $NAME, $1-$9, $@ $* $# $? $$ $! $-; ${NAME}, ${#NAME}, ${NAME[@]} / [*] / [digits], ${NAME<op>word} with op one of
///   :- - :+ + # ## % %% / //, whose word holds only text, quotes and nested ${..}/$NAME; $((..)) holding only names, numbers, operators
///   and parentheses (its names are returned, so the audit can refuse a checkout variable there); $(..) ONLY as the whole value of a pure
///   assignment statement (NAME=$(..) or NAME="$(..)", no command word). Not: ${!..}, := = :? ? ^ , @ and substring operators, a
///   subscript that is not @ * or digits, $(..) or $((..)) inside ${..} or $((..)), $OLDPWD, ~ expansion, brace expansion, process
///   substitution. An argument word (not an assignment, not inside [[ ]], not a case subject or pattern) must not glob (unquoted * ? [)
///   and must not hold an unquoted parameter expansion (its value would be split and globbed), but $? $# $$ $! ${#..} $((..)) and the
///   ${NAME[@]+"${NAME[@]}"} form.
/// - REDIRECTIONS: [n]> [n]>> [n]< onto a word, and the dups [n]>&m [n]<&m [n]>&- (m a number). Not: << <<- <<< <> >| &> &>> |&, a
///   >& or <& onto a word, any redirection of exec, a redirection with no command word ($(< f), > f) but on a ( .. ) subshell.
/// - STATEMENTS: simple commands (assignment prefix, a command word from COMMANDS or a function this file defines, arguments), pipes
///   | , lists ; && || newline, ( .. ) subshells, { .. } groups, if/then/elif/else/fi, while/until/do/done, for NAME in words, case
///   WORD in pattern|pattern) .. ;; esac, [[ .. ]] (no arithmetic comparison: -eq -ne -lt -le -gt -ge evaluate their operands as
///   arithmetic), NAME() { .. } definitions, NAME=( words ) arrays. Not: (( .. )), for (( .. )), select, function, coproc, time, a
///   background &, ;& ;;&.
/// - COMMAND WORDS: a literal name from COMMANDS, a function defined in this file, "$HERE/<file>" (this script's own tools), "$@" only
///   inside bounded() and the RUNNERS, and exec (no redirection) or a RUNNER before any of those. bounded()'s own body is checked
///   lexically only: the audit's definition rule classifies what it runs.
/// The ALLOWLIST holds the named exceptions; it is shrink-only: an entry that matches nothing is reported stale, and its size is pinned
/// in BbTestGrammarIT (ALLOWLIST_PIN).
final class BbTestGrammar {
    record Violation(int line, String kind, String reason, String text) {
        @Override
        public String toString() {
            return "line " + line + ": VIOLATION (grammar, " + kind + ": " + reason + "): " + text;
        }
    }

    record Allowed(String name, String kind, Pattern line, String reason) {}

    record Result(List<Violation> violations, List<String> allowed, List<String> stale, List<String[]> arithNames) {}

    static final Map<String, String> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("[", "a test: compares strings or stats a path");
        COMMANDS.put("[[", "a test (its words are the conditional grammar: no arithmetic comparison)");
        COMMANDS.put("echo", "prints its words");
        COMMANDS.put("printf", "prints its words (not -v: an assignment the audit cannot follow)");
        COMMANDS.put("mkdir", "creates a directory");
        COMMANDS.put("mktemp", "creates a new file O_EXCL");
        COMMANDS.put("rm", "unlinks");
        COMMANDS.put("mv", "renames");
        COMMANDS.put("exit", "builtin");
        COMMANDS.put("return", "builtin");
        COMMANDS.put("continue", "builtin");
        COMMANDS.put("true", "builtin");
        COMMANDS.put("local", "names and NAME=value only (no -n nameref, no option)");
        COMMANDS.put("unset", "names only");
        COMMANDS.put("shift", "a count only");
        COMMANDS.put("set", "exactly `set -euo pipefail`");
        COMMANDS.put("cd", "classified by the audit's cd rule (cd / and the pwd-only form)");
        COMMANDS.put("pwd", "prints the cwd");
        COMMANDS.put("readlink", "resolves this script's own path ($0)");
        COMMANDS.put("dirname", "text");
        COMMANDS.put("command", "only `command -v NAME`");
        COMMANDS.put("sha256sum", "the launcher jar's digest (a host-seed path, never a checkout variable)");
        COMMANDS.put("shasum", "the launcher jar's digest (a host-seed path, never a checkout variable)");
        COMMANDS.put("cut", "a filter on its stdin");
        COMMANDS.put("sed", "a filter on its stdin (a file operand is classified by the audit)");
        COMMANDS.put("tr", "a filter on its stdin");
        COMMANDS.put("flock", "an fd number only (no command to run)");
        COMMANDS.put(".", "only `. \"$HERE/<file>\"`: this script's own library");
        COMMANDS.put("field", "bb-queue-lib.sh's ticket-field reader (sourced from $HERE): reads a queue ticket, never checkout content");
    }

    /// Why a command outside COMMANDS is excluded, for the commonest ones (any other gets the generic reason).
    static final Map<String, String> EXCLUDED = Map.ofEntries(Map.entry("eval", "runs a string as code the audit cannot see"),
                                                              Map.entry("trap", "a trap body is code run later, unseen"),
                                                              Map.entry("declare", "-n namerefs and -g globals move taint unseen"),
                                                              Map.entry("typeset", "-n namerefs and -g globals move taint unseen"),
                                                              Map.entry("readonly", "an assignment form the audit does not follow"),
                                                              Map.entry("export", "an assignment form the audit does not follow"),
                                                              Map.entry("read", "reads input into a variable"),
                                                              Map.entry("mapfile", "reads input into a variable"),
                                                              Map.entry("readarray", "reads input into a variable"),
                                                              Map.entry("source", "runs a file as code"),
                                                              Map.entry("alias", "renames a command unseen"),
                                                              Map.entry("builtin", "outside the grammar"),
                                                              Map.entry("sh", "a command-string interpreter outside bounded()"),
                                                              Map.entry("bash", "a command-string interpreter outside bounded()"),
                                                              Map.entry("python3", "a code interpreter outside bounded()"),
                                                              Map.entry("cat", "opens a file outside bounded()"));

    static final Set<String> RUNNERS = Set.of("guard");   // this script's functions that run "$@": their first argument is a command word

    static final List<Allowed> ALLOWLIST = List.of(
            new Allowed("queue-scan",
                        "glob",
                        Pattern.compile("^  for f in \"\\$Q\"/running/\\*; do "),
                        "the launch names its own ticket by listing the queue's running/ dir: Q is the host queue root (BB_TEST_QUEUE_ROOT or"
                        + " $HOME/work/queue, absolute, refused if relative), never the checkout, and each entry is only tested and read by field"));

    private static final Set<String> RESERVED = Set.of("if", "then", "else", "elif", "fi", "do", "done", "while", "until", "!", "{", "}");
    private static final Set<String> FORBIDDEN_RESERVED = Set.of("select", "function", "coproc", "time");
    private static final Set<String> ARITH_TESTS = Set.of("-eq", "-ne", "-lt", "-le", "-gt", "-ge");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ASSIGN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\+?=");
    private static final Pattern HERE_FILE = Pattern.compile("\"\\$HERE/[A-Za-z0-9._-]+\"");
    private static final Pattern FUNCTION_DEF = Pattern.compile("(?m)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\(\\) \\{");

    private static final int NORMAL = 0;
    private static final int COND = 1;
    private static final int PATTERN = 2;

    private static final class Word {
        int start;
        int end;
        final StringBuilder lit = new StringBuilder();
        boolean quoted;
        boolean glob;
        boolean bracket;
        boolean brace;
        boolean tilde;
        boolean unquotedParam;
        int subs;
        int subStart = -1;
        int subEnd = -1;
        int valueStart = -1;
        String assignName;
        final List<Word> elements = new ArrayList<>();
    }

    private final String s;
    private final int n;
    private final List<Integer> lineStarts = new ArrayList<>();
    private final List<Violation> out = new ArrayList<>();
    private final List<String[]> arith = new ArrayList<>();
    private final Set<String> functions = new LinkedHashSet<>();
    private final Deque<Object[]> fn = new ArrayDeque<>();   // {name, depth}
    private String pendingFn;
    private int depth;
    private int i;

    private BbTestGrammar(String source) {
        s = source;
        n = source.length();
        lineStarts.add(0);
        for (var k = 0; k < n; k++) {
            if (s.charAt(k) == '\n') {
                lineStarts.add(k + 1);
            }
        }
        var m = FUNCTION_DEF.matcher(source);
        while (m.find()) {
            functions.add(m.group(1));
        }
    }

    static Result check(String source) {
        var g = new BbTestGrammar(source);
        g.list(false, false);
        var kept = new ArrayList<Violation>();
        var allowed = new ArrayList<String>();
        var used = new LinkedHashMap<String, Integer>();
        ALLOWLIST.forEach(a -> used.put(a.name(), 0));
        for (var v : g.out) {
            var physical = g.physical(v.line());
            var hit = ALLOWLIST.stream().filter(a -> a.kind().equals(v.kind()) && a.line().matcher(physical).find()).findFirst();
            if (hit.isPresent()) {
                used.merge(hit.get().name(), 1, Integer::sum);
                allowed.add("line " + v.line() + ": ALLOWED " + hit.get().name() + " (" + v.kind() + "; " + hit.get().reason() + "): " + v.text());
            } else {
                kept.add(v);
            }
        }
        var stale = used.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList();
        return new Result(kept, allowed, stale, g.arith);
    }

    // ---- positions and reporting ----

    private int lineOf(int offset) {
        var lo = 0;
        var hi = lineStarts.size() - 1;
        while (lo < hi) {
            var mid = (lo + hi + 1) / 2;
            if (lineStarts.get(mid) <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    private String physical(int line) {
        var from = lineStarts.get(line - 1);
        var to = line < lineStarts.size() ? lineStarts.get(line) - 1 : n;
        return s.substring(from, Math.max(from, to));
    }

    private void v(int offset, String kind, String reason) {
        var line = lineOf(Math.min(offset, Math.max(0, n - 1)));
        var text = physical(line).strip();
        out.add(new Violation(line, kind, reason, text.length() > 200 ? text.substring(0, 200) + "..." : text));
    }

    private char at(int k) {
        return k < n ? s.charAt(k) : '\0';
    }

    private void skipBlank() {
        while (i < n) {
            var c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
            } else if (c == '\\' && at(i + 1) == '\n') {
                i += 2;
            } else {
                return;
            }
        }
    }

    private void skipComment() {
        while (i < n && s.charAt(i) != '\n') {
            i++;
        }
    }

    // ---- lists and commands ----

    /// Commands until EOF, or the closing ) when sub, or before ;; / esac when inCase.
    private void list(boolean sub, boolean inCase) {
        while (true) {
            skipBlank();
            if (i >= n) {
                if (sub) {
                    v(n - 1, "syntax", "an unclosed ( or $(");
                }
                return;
            }
            var c = s.charAt(i);
            if (c == '#') {
                skipComment();
            } else if (c == ';' && at(i + 1) == ';') {
                if (at(i + 2) == '&') {
                    v(i, "statement", ";;& is outside the grammar");
                    i++;
                }
                if (inCase) {
                    i += 2;
                    return;
                }
                v(i, "syntax", ";; outside a case");
                i += 2;
            } else if (c == ';' && at(i + 1) == '&') {
                v(i, "statement", ";& is outside the grammar");
                i += 2;
            } else if (c == '\n' || c == ';') {
                i++;
            } else if (c == ')') {
                if (sub) {
                    i++;
                    return;
                }
                v(i, "syntax", "an unmatched )");
                i++;
            } else if (c == '&' && at(i + 1) == '&' || c == '|' && at(i + 1) == '|') {
                i += 2;
            } else if (c == '|') {
                if (at(i + 1) == '&') {
                    v(i, "redirect", "|& is outside the grammar");
                    i++;
                }
                i++;
            } else if (c == '&') {
                v(i, "statement", "a background & is outside the grammar (a job that outlives its statement)");
                i++;
            } else {
                if (inCase && peekWord().equals("esac")) {
                    return;
                }
                command();
            }
        }
    }

    private String peekWord() {
        var k = i;
        while (k < n && " \t\n;&|()<>".indexOf(s.charAt(k)) < 0) {
            k++;
        }
        return s.substring(i, k);
    }

    private boolean terminator(char c) {
        return c == '\0' || c == '\n' || c == ';' || c == '&' || c == '|' || c == ')';
    }

    private void command() {
        var start = i;
        var assigns = new ArrayList<Word>();
        var args = new ArrayList<Word>();
        var redirs = new ArrayList<String>();
        Word cw = null;
        var compound = false;   // a ( .. ) subshell: its redirections apply to it, a command the grammar classified
        while (true) {
            skipBlank();
            var c = at(i);
            if (c == '&' && at(i + 1) == '>') {
                redirect(redirs);
                continue;
            }
            if (terminator(c)) {
                break;
            }
            if (c == '#' && (i == start || Character.isWhitespace(at(i - 1)))) {
                skipComment();
                break;
            }
            if (c == '(') {
                if (cw == null && args.isEmpty()) {
                    if (at(i + 1) == '(') {
                        v(i, "statement", "(( .. )) is outside the grammar");
                    }
                    i++;
                    list(true, false);
                    compound = true;
                    continue;
                }
                v(i, "syntax", "an unexpected (");
                i++;
                continue;
            }
            if (redirectAhead()) {
                redirect(redirs);
                continue;
            }
            var w = word(NORMAL);
            if (w.end == w.start) {
                v(i, "syntax", "an unexpected character " + c);
                i++;
                continue;
            }
            var raw = s.substring(w.start, w.end);
            if (cw == null) {
                if (!w.quoted && w.assignName == null && RESERVED.contains(raw)) {
                    keyword(raw);
                    continue;
                }
                if (!w.quoted && FORBIDDEN_RESERVED.contains(raw)) {
                    v(w.start, "statement", raw + " is outside the grammar");
                    continue;
                }
                if (!w.quoted && raw.equals("case")) {
                    caseClause();
                    return;
                }
                if (!w.quoted && raw.equals("for")) {
                    forClause();
                    return;
                }
                if (!w.quoted && raw.equals("[[")) {
                    cond();
                    return;
                }
                if (w.assignName != null) {
                    assigns.add(w);
                    continue;
                }
                if (s.startsWith("()", i)) {
                    i += 2;
                    pendingFn = raw;
                    continue;
                }
                cw = w;
                continue;
            }
            args.add(w);
        }
        simple(start, compound, assigns, cw, args, redirs);
    }

    private void keyword(String raw) {
        if (raw.equals("{")) {
            depth++;
            if (pendingFn != null) {
                fn.push(new Object[] {pendingFn, depth});
                pendingFn = null;
            }
        } else if (raw.equals("}")) {
            if (!fn.isEmpty() && (int) fn.peek()[1] == depth) {
                fn.pop();
            }
            depth--;
        }
    }

    private String currentFn() {
        return fn.isEmpty() ? "" : (String) fn.peek()[0];
    }

    private void simple(int start, boolean compound, List<Word> assigns, Word cw, List<Word> args, List<String> redirs) {
        for (var a : assigns) {
            assignment(a, cw != null);
        }
        var inBounded = currentFn().equals("bounded");
        if (cw == null) {
            if (!compound && !redirs.isEmpty()) {   // $(< f), > f: a redirection with no command word opens a file no classified command names
                v(start, "redirect", "a redirection with no command word (" + String.join(" ", redirs) + ") opens a file outside every classified command");
            }
            return;
        }
        var k = -1;   // index into args of the effective command word; -1 = cw itself
        var name = s.substring(cw.start, cw.end);
        if (name.equals("exec") && !cw.quoted) {
            if (!redirs.isEmpty()) {
                v(cw.start, "redirect", "exec with a redirection (" + String.join(" ", redirs) + ") opens a file for the rest of the script");
            }
            if (args.isEmpty()) {
                return;
            }
            k = 0;
        }
        if (!inBounded) {
            var word = k < 0 ? cw : args.get(k);
            commandWord(word, args.subList(k + 1, args.size()));
            var cname = s.substring(word.start, word.end);
            if (RUNNERS.contains(cname) && k + 1 < args.size()) {
                commandWord(args.get(k + 1), args.subList(k + 2, args.size()));
            }
        }
        var local = name.equals("local");
        for (var a : args) {
            if (local && a.assignName != null) {
                assignment(a, true);
            } else {
                argument(a);
            }
        }
    }

    private void commandWord(Word w, List<Word> args) {
        var raw = s.substring(w.start, w.end);
        if (raw.equals("\"$@\"")) {
            if (!RUNNERS.contains(currentFn())) {
                v(w.start, "command", "\"$@\" as a command word outside bounded() and the runners " + RUNNERS);
            }
            return;
        }
        if (HERE_FILE.matcher(raw).matches()) {
            return;
        }
        if (w.quoted || raw.contains("$")) {
            v(w.start, "command", "a computed command word (" + raw + ")");
            return;
        }
        if (!COMMANDS.containsKey(raw) && !functions.contains(raw)) {
            v(w.start, "command", "the command " + raw + " is outside the grammar (" + EXCLUDED.getOrDefault(raw, "not in its command list") + ")");
            return;
        }
        var a = args.stream().map(x -> s.substring(x.start, x.end)).toList();
        var bad = switch (raw) {
            case "printf" -> !a.isEmpty() && a.get(0).startsWith("-") ? "printf with an option (-v assigns, losing taint)" : null;
            case "local", "unset" -> a.stream().anyMatch(x -> x.startsWith("-")) ? raw + " with an option (-n is a nameref)" : null;
            case "set" -> a.equals(List.of("-euo", "pipefail")) ? null : "set other than `set -euo pipefail`";
            case "shift" -> a.size() > 1 || a.size() == 1 && !a.get(0).matches("[0-9]+") ? "shift other than a count" : null;
            case "flock" -> a.size() == 1 && a.get(0).matches("[0-9]") ? null : "flock other than one fd number";
            case "command" -> a.size() == 2 && a.get(0).equals("-v") && NAME.matcher(a.get(1)).matches() ? null : "command other than `command -v NAME`";
            case "." -> a.size() == 1 && HERE_FILE.matcher(a.get(0)).matches() ? null : "sourcing other than . \"$HERE/<file>\"";
            default -> null;
        };
        if (bad != null) {
            v(w.start, "command", bad);
        }
    }

    private void assignment(Word a, boolean withCommand) {
        if (a.tilde) {
            v(a.start, "tilde", "~ expansion (~- is $OLDPWD, the checkout after cd /)");
        }
        if (a.subs > 0) {
            if (!whole(a)) {
                v(a.start, "cmdsub", "a command substitution that is not the whole assignment value");
            } else if (withCommand) {
                v(a.start, "cmdsub", "a command-substitution assignment on a command (only a pure assignment statement)");
            }
        }
        for (var e : a.elements) {
            argument(e);
        }
    }

    private boolean whole(Word a) {
        if (a.subs != 1 || a.valueStart < 0) {
            return false;
        }
        if (a.subStart == a.valueStart && a.subEnd == a.end) {
            return true;
        }
        return at(a.valueStart) == '"' && a.subStart == a.valueStart + 1 && a.subEnd == a.end - 1 && at(a.end - 1) == '"';
    }

    private void argument(Word w) {
        if (w.subs > 0) {
            v(w.start, "cmdsub", "a command substitution inside an argument word (its result can name a path the audit cannot see)");
        }
        if (w.glob || w.bracket && !s.substring(w.start, w.end).equals("[")) {
            v(w.start, "glob", "a glob (lists a directory: its result names paths)");
        }
        if (w.brace) {
            v(w.start, "brace", "brace expansion");
        }
        if (w.tilde) {
            v(w.start, "tilde", "~ expansion (~- is $OLDPWD, the checkout after cd /)");
        }
        if (w.unquotedParam) {
            v(w.start, "unquoted", "an unquoted expansion (its value is split and globbed)");
        }
        for (var e : w.elements) {
            argument(e);
        }
    }

    private void caseClause() {
        skipBlank();
        var subject = word(NORMAL);
        if (subject.subs > 0) {
            v(subject.start, "cmdsub", "a command substitution as a case subject");
        }
        skipBlank();
        if (!peekWord().equals("in")) {
            v(i, "syntax", "case without in");
            return;
        }
        i += 2;
        while (true) {
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '#')) {
                if (s.charAt(i) == '#') {
                    skipComment();
                } else {
                    i++;
                }
            }
            if (i >= n) {
                v(n - 1, "syntax", "case without esac");
                return;
            }
            if (peekWord().equals("esac")) {
                i += 4;
                return;
            }
            if (at(i) == '(') {
                v(i, "statement", "a ( before a case pattern is outside the grammar");
                i++;
            }
            while (true) {
                skipBlank();
                var p = word(PATTERN);
                if (p.subs > 0) {
                    v(p.start, "cmdsub", "a command substitution in a case pattern");
                }
                skipBlank();
                if (at(i) == '|') {
                    i++;
                    continue;
                }
                if (at(i) == ')') {
                    i++;
                    break;
                }
                v(i, "syntax", "an unterminated case pattern");
                return;
            }
            list(false, true);
        }
    }

    private void forClause() {
        skipBlank();
        if (at(i) == '(') {
            v(i, "statement", "for (( .. )) is outside the grammar");
            skipComment();
            return;
        }
        var var = word(NORMAL);
        if (!NAME.matcher(s.substring(var.start, var.end)).matches()) {
            v(var.start, "statement", "for over a non-name");
        }
        skipBlank();
        if (!peekWord().equals("in")) {
            v(i, "statement", "for without in");
            return;
        }
        i += 2;
        while (true) {
            skipBlank();
            if (terminator(at(i))) {
                return;
            }
            argument(word(NORMAL));
        }
    }

    private void cond() {
        while (true) {
            skipBlank();
            if (i >= n || at(i) == '\n') {
                v(i - 1, "syntax", "[[ without ]]");
                return;
            }
            var w = word(COND);
            var raw = s.substring(w.start, w.end);
            if (raw.equals("]]")) {
                return;
            }
            if (ARITH_TESTS.contains(raw)) {
                v(w.start, "statement", "[[ " + raw + " ]] evaluates its operands as arithmetic (a[$(..)] in a value runs code)");
            }
            if (w.subs > 0) {
                v(w.start, "cmdsub", "a command substitution inside [[ ]]");
            }
        }
    }

    // ---- redirections ----

    private boolean redirectAhead() {
        var k = i;
        while (k < n && Character.isDigit(s.charAt(k))) {
            k++;
        }
        return at(k) == '<' || at(k) == '>';
    }

    private void redirect(List<String> redirs) {
        var start = i;
        while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
        }
        var opStart = i;
        while (i < n && "<>&|-(".indexOf(s.charAt(i)) >= 0 && i - opStart < 3) {
            if (s.charAt(i) == '-' && !(i > opStart && (s.charAt(i - 1) == '<' && s.startsWith("<<", i - 2)))) {
                break;
            }
            i++;
        }
        var op = s.substring(opStart, i);
        redirs.add(s.substring(start, i));
        switch (op) {
            case ">", ">>", "<" -> {
                skipBlank();
                argument(word(NORMAL));
            }
            case ">&", "<&" -> {
                var k = i;
                while (k < n && Character.isDigit(s.charAt(k))) {
                    k++;
                }
                if (k == i && at(k) == '-') {
                    k++;
                }
                if (k == i || !terminator(at(k)) && at(k) != ' ' && at(k) != '\t') {
                    v(start, "redirect", op + " onto a word (bash opens it as a file)");
                    skipBlank();
                    word(NORMAL);
                } else {
                    i = k;
                }
            }
            default -> {
                var why = op.startsWith("<<") ? "a heredoc or here-string (" + op + "): its body is input the audit does not classify"
                        : op.endsWith("(") ? "process substitution (" + op + ")"
                        : op + " is outside the grammar";
                v(start, op.startsWith("<<") ? "heredoc" : "redirect", why);
                if (op.endsWith("(")) {
                    list(true, false);
                } else if (op.startsWith("<<") && !op.equals("<<<")) {
                    skipBlank();
                    word(NORMAL);
                } else {
                    skipBlank();
                    word(NORMAL);
                }
            }
        }
    }

    // ---- words ----

    private Word word(int mode) {
        var w = new Word();
        w.start = i;
        var m = ASSIGN.matcher(s).region(i, n);
        if (mode == NORMAL && m.lookingAt()) {
            w.assignName = s.substring(i, m.end()).replaceAll("\\+?=$", "");
            i = m.end();
            w.valueStart = i;
            if (at(i) == '(') {
                i++;
                while (true) {
                    while (i < n && Character.isWhitespace(s.charAt(i))) {
                        i++;
                    }
                    if (i >= n || at(i) == ')') {
                        i++;
                        break;
                    }
                    w.elements.add(word(NORMAL));
                }
                w.end = i;
                return w;
            }
        }
        while (i < n) {
            var c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n') {
                break;
            }
            if (mode == NORMAL && "<>;&|()".indexOf(c) >= 0) {
                break;
            }
            if (mode == COND && c == ';' && s.substring(w.start, i).equals("]]")) {
                break;
            }
            if (mode == PATTERN && (c == '|' || c == ')')) {
                break;
            }
            switch (c) {
                case '\\' -> {
                    if (at(i + 1) == '\n') {
                        i += 2;
                    } else {
                        w.quoted = true;
                        w.lit.append(at(i + 1));
                        i += 2;
                    }
                }
                case '\'' -> {
                    var j = s.indexOf('\'', i + 1);
                    if (j < 0) {
                        v(i, "quoting", "an unterminated '");
                        i = n;
                    } else {
                        w.lit.append(s, i + 1, j);
                        i = j + 1;
                    }
                    w.quoted = true;
                }
                case '"' -> {
                    w.quoted = true;
                    dq(w);
                }
                case '$' -> dollar(w, false);
                case '`' -> backtick();
                default -> {
                    if (mode == NORMAL) {
                        if (c == '*' || c == '?') {
                            w.glob = true;
                        } else if (c == '[') {
                            w.bracket = true;
                        } else if (c == '{' || c == '}') {
                            w.brace = true;
                        } else if (c == '~') {
                            w.tilde = true;
                        }
                    }
                    w.lit.append(c);
                    i++;
                }
            }
        }
        w.end = i;
        var raw = s.substring(w.start, w.end);
        if (raw.equals("{") || raw.equals("}")) {
            w.brace = false;
        }
        if (raw.equals("[") || raw.equals("[[")) {
            w.bracket = false;
        }
        return w;
    }

    private void backtick() {
        v(i, "quoting", "a backtick command substitution");
        var j = s.indexOf('`', i + 1);
        i = j < 0 ? n : j + 1;
    }

    private void dq(Word w) {
        i++;
        while (i < n) {
            var c = s.charAt(i);
            if (c == '"') {
                i++;
                return;
            }
            if (c == '\\') {
                i += 2;
            } else if (c == '$') {
                dollar(w, true);
            } else if (c == '`') {
                backtick();
            } else {
                i++;
            }
        }
        v(n - 1, "quoting", "an unterminated \"");
    }

    private void dollar(Word w, boolean inDq) {
        var c1 = at(i + 1);
        if (c1 == '\'' && !inDq) {
            v(i, "quoting", "a $'..' string (the quoting forms are '..' and \"..\" only)");
            var j = i + 2;
            while (j < n && s.charAt(j) != '\'') {
                j += s.charAt(j) == '\\' ? 2 : 1;
            }
            i = Math.min(n, j + 1);
            return;
        }
        if (c1 == '"' && !inDq) {
            v(i, "quoting", "a $\"..\" locale string");
            i++;
            return;
        }
        if (s.startsWith("$((", i)) {
            arithmetic();
            return;
        }
        if (c1 == '(') {
            w.subs++;
            w.subStart = i;
            i += 2;
            list(true, false);
            w.subEnd = i;
            return;
        }
        if (c1 == '{') {
            brace(w, inDq);
            return;
        }
        if (Character.isLetter(c1) || c1 == '_') {
            var j = i + 1;
            while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                j++;
            }
            oldpwd(s.substring(i + 1, j));
            i = j;
            w.unquotedParam |= !inDq;
            return;
        }
        if (Character.isDigit(c1) || c1 == '@' || c1 == '*') {
            i += 2;
            w.unquotedParam |= !inDq;
            return;
        }
        if ("#?$!-".indexOf(c1) >= 0 && c1 != '\0') {
            i += 2;
            return;
        }
        i++;
    }

    private void oldpwd(String name) {
        if (name.equals("OLDPWD")) {
            v(i, "oldpwd", "$OLDPWD (after cd / it names the checkout)");
        }
    }

    private int closeBrace(int j) {
        var d = 0;
        while (j < n) {
            var c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == '{') {
                d++;
            } else if (c == '}') {
                if (d == 0) {
                    return j;
                }
                d--;
            }
            j++;
        }
        return n - 1;
    }

    private void brace(Word w, boolean inDq) {
        var start = i;
        var j = i + 2;
        if (at(j) == '!') {
            v(start, "expansion", "${!..} indirection (taint moves through a name the audit cannot see)");
            i = closeBrace(j) + 1;
            return;
        }
        var len = at(j) == '#' && (Character.isLetterOrDigit(at(j + 1)) || at(j + 1) == '_' || at(j + 1) == '@');
        if (len) {
            j++;
        }
        var nameStart = j;
        if (Character.isLetter(at(j)) || at(j) == '_') {
            while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                j++;
            }
        } else if (Character.isDigit(at(j))) {
            while (j < n && Character.isDigit(s.charAt(j))) {
                j++;
            }
        } else if ("@*#?$!-".indexOf(at(j)) >= 0) {
            j++;
        }
        var name = s.substring(nameStart, j);
        if (name.isEmpty()) {
            v(start, "expansion", "an unclassified ${..} form");
            i = closeBrace(j) + 1;
            return;
        }
        if (name.equals("OLDPWD")) {
            v(start, "oldpwd", "$OLDPWD (after cd / it names the checkout)");
        }
        if (at(j) == '[') {
            var k = s.indexOf(']', j);
            var sub = k < 0 ? "" : s.substring(j + 1, k);
            if (!sub.matches("@|\\*|[0-9]+")) {
                v(start, "expansion", "an array subscript other than @ * or a number (a subscript is arithmetic)");
            }
            j = k < 0 ? n - 1 : k + 1;
        }
        if (at(j) == '}') {
            i = j + 1;
            w.unquotedParam |= !inDq && !len;
            return;
        }
        if (len) {
            v(start, "expansion", "an unclassified ${#..} form");
            i = closeBrace(j) + 1;
            return;
        }
        String op = null;
        for (var o : List.of(":-", ":+", "##", "%%", "//", "-", "+", "#", "%", "/")) {
            if (s.startsWith(o, j)) {
                op = o;
                break;
            }
        }
        if (op == null) {
            v(start, "expansion", "a ${..} operator outside the grammar (:= = :? ? ^ , @ and substrings are excluded)");
            i = closeBrace(j) + 1;
            return;
        }
        j += op.length();
        var operandStart = j;
        var d = new Word();
        while (j < n && s.charAt(j) != '}') {
            var c = s.charAt(j);
            if (c == '\\') {
                j += 2;
            } else if (c == '\'') {
                v(j, "quoting", "a single quote inside a ${..} word");
                var k = s.indexOf('\'', j + 1);
                j = k < 0 ? n : k + 1;
            } else if (c == '`') {
                i = j;
                backtick();
                j = i;
            } else if (c == '$') {
                if (at(j + 1) == '(') {
                    v(j, "nested", "$(..) or $((..)) nested inside ${..}");
                    var dd = 0;
                    while (j < n) {
                        if (s.charAt(j) == '(') {
                            dd++;
                        } else if (s.charAt(j) == ')' && --dd == 0) {
                            break;
                        }
                        j++;
                    }
                    j++;
                } else {
                    i = j;
                    dollar(d, true);
                    j = i;
                }
            } else if (c == '"') {
                j++;
                while (j < n && s.charAt(j) != '"') {
                    if (s.charAt(j) == '\\') {
                        j += 2;
                    } else if (s.charAt(j) == '$') {
                        if (at(j + 1) == '(') {
                            v(j, "nested", "$(..) or $((..)) nested inside ${..}");
                            j += 2;
                        } else {
                            i = j;
                            dollar(d, true);
                            j = i;
                        }
                    } else if (s.charAt(j) == '`') {
                        i = j;
                        backtick();
                        j = i;
                    } else {
                        j++;
                    }
                }
                j++;
            } else {
                j++;
            }
        }
        var operand = s.substring(operandStart, Math.min(j, n));
        i = Math.min(n, j + 1);
        var arrayPlus = op.equals("+") && operand.equals("\"${" + name + "[@]}\"");
        w.unquotedParam |= !inDq && !arrayPlus;
        w.subs += d.subs;
    }

    private void arithmetic() {
        var start = i;
        var j = i + 3;
        var d = 0;
        while (j < n) {
            var c = s.charAt(j);
            if (c == '(') {
                d++;
                j++;
            } else if (c == ')') {
                if (d == 0) {
                    if (at(j + 1) == ')') {
                        j += 2;
                        break;
                    }
                    v(start, "arith", "an unbalanced $((..))");
                    j++;
                    break;
                }
                d--;
                j++;
            } else if (c == '$') {
                var k = j + 1;
                if (Character.isLetter(at(k)) || at(k) == '_' || Character.isDigit(at(k))) {
                    while (k < n && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) {
                        k++;
                    }
                    arith.add(new String[] {String.valueOf(lineOf(start)), s.substring(j + 1, k)});
                    oldpwd(s.substring(j + 1, k));
                    j = k;
                } else {
                    v(j, "nested", "only $NAME inside $((..)) ($(..), ${..} and $((..)) are excluded)");
                    j++;
                }
            } else if (Character.isLetter(c) || c == '_') {
                var k = j;
                while (k < n && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) {
                    k++;
                }
                arith.add(new String[] {String.valueOf(lineOf(start)), s.substring(j, k)});
                j = k;
            } else if (Character.isDigit(c) || " \t+-*/%<>=!&|^~?:,".indexOf(c) >= 0) {
                j++;
            } else {
                v(j, "arith", "the character " + c + " inside $((..)) is outside the arithmetic grammar");
                j++;
            }
        }
        i = j;
    }
}
