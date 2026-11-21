package org.pragmatica.temper.dist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The vbt9 READ INVARIANT of ops/bb/bb-test, checked on the script's own source (the source text is the authority, so a static
/// check is the right instrument). Definition, as the script's header states it: a CHECKOUT VARIABLE is a root named on the
/// header's `# checkout-roots:` line, or any variable assigned (transitively) from a word that expands one. Every simple command
/// (command substitutions included, each its own command) whose words or redirect targets expand a checkout variable is classified:
/// BOUNDED (runs through bounded()), one of the named exemptions below (each with its reason), or VIOLATION. OWN-TEMP (a checkout
/// variable assigned only from `$(mktemp ...)` or empty: RD, OUT) has no exemption: named by a bounded() call other than as its <out>, the
/// call must be the CAP_PY read (the byte cap). NO HIDDEN READS: a cd/pushd onto a checkout variable (but `$(cd "$X" && pwd)`) and a
/// relative path word of a command or redirect, outside bounded(), are VIOLATIONs; so is any cd/pushd/popd but `cd /` and the pwd-only
/// form, and the __launch mode must leave the checkout cwd (`CO=$(pwd); cd /`), since a bare relative word cannot be told from text.
/// Comments and heredoc bodies are skipped. Not a full shell parser: separators ; && || | & ( ) { } and newlines split commands; $(..) nests; $((..)),
/// ${..} and quotes are opaque words; single-quoted text expands nothing.
final class BbTestReadAudit {
    record Call(int line, String verdict, String text) {
        @Override
        public String toString() {
            return "line " + line + ": " + verdict + ": " + text;
        }
    }

    record Audit(List<String> roots, Set<String> checkoutVars, Set<String> ownTemp, List<Call> calls, List<String> problems) {}

    /// Commands that open no file content: their checkout-derived words are names or text, never read. A redirect on any of them
    /// is classified on its own.
    static final Map<String, String> NO_CONTENT = new LinkedHashMap<>();

    static {
        NO_CONTENT.put("[", "a test: compares strings or stats a path; opens no file content");
        NO_CONTENT.put("[[", "a test: compares strings or stats a path; opens no file content");
        NO_CONTENT.put("echo", "prints its words; opens nothing (a redirect is classified on its own)");
        NO_CONTENT.put("printf", "prints its words; opens nothing (a redirect is classified on its own)");
        NO_CONTENT.put("mkdir", "creates a directory; opens no file content");
        NO_CONTENT.put("mktemp", "creates a new file O_EXCL; opens no existing content");
        NO_CONTENT.put("rm", "unlinks; opens no file content");
        NO_CONTENT.put("mv", "renames; opens no file content");
        NO_CONTENT.put("refuse", "this script's own function: its body is audited command by command like the rest");
        NO_CONTENT.put("exit", "shell builtin; opens nothing");
        NO_CONTENT.put("return", "shell builtin; opens nothing");
        NO_CONTENT.put("local", "shell builtin (assignments); opens nothing");
    }

    static final String CAPPED_READ = "python3 -I -c \"$CAP_PY\" \"$READ_CAP\" ";
    static final Pattern OWN_TEMP_VALUE = Pattern.compile("^(\\$\\(mktemp [^()]*\\))?$");
    static final Pattern PWD_ONLY_CD = Pattern.compile("\\$\\(cd \"\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?\" && pwd\\)");
    static final Pattern RELATIVE = Pattern.compile("^(\\.\\.?/|[A-Za-z0-9_.][A-Za-z0-9_.+-]*/)");
    static final Set<String> CD = Set.of("cd", "pushd", "popd");
    static final Pattern LEAVES_CHECKOUT = Pattern.compile("(?m)^  CO=\\$\\(pwd\\); cd /; ");
    static final String ENQUEUE = "ENQUEUE (exec bb-build: hands the checkout to the queue, which runs this script's bounded __launch mode)";
    static final Pattern ROOTS = Pattern.compile("(?m)^# checkout-roots:((?: [A-Z_][A-Z0-9_]*)+)$");
    static final Pattern ASSIGN = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\+?=(.*)$", Pattern.DOTALL);
    static final Pattern BOUNDED_DEF = Pattern.compile("(?ms)^bounded\\(\\) \\{(.*?)^\\}$");
    static final String SUB = "\u0001";
    static final Set<String> KEYWORDS = Set.of("if", "then", "else", "elif", "do", "while", "until", "!", "time");

    private record Seg(int line, List<String> words, List<String> full) {}

    static Audit audit(String source) {
        var problems = new ArrayList<String>();
        var rootLine = ROOTS.matcher(source);
        if (!rootLine.find()) {
            return new Audit(List.of(), Set.of(), Set.of(), List.of(), List.of("no '# checkout-roots: <VAR>...' line in the header"));
        }
        var roots = List.of(rootLine.group(1).strip().split(" "));
        var def = BOUNDED_DEF.matcher(source);
        if (!def.find()) {
            problems.add("no top-level bounded() definition");
        } else {
            var body = def.group(1);
            var code = String.join("\n", body.lines().map(BbTestReadAudit::stripComment).toList());
            var runs = count(code.replaceAll("'[^']*'", "''"), "\"$@\"");
            var timed = count(code, "(cd \"$d\" && exec timeout -k 10 \"$s\" ");
            if (runs == 0 || runs != timed || runs != count(code, "(cd \"$d\" && exec ")) {
                problems.add("bounded() runs its command " + runs + " time(s), " + timed + " of them under exec timeout -k 10 \"$s\"");
            }
            if (Pattern.compile("\\)\\s*[0-9&]*>").matcher(code).find()) {
                problems.add("bounded() opens its <out> outside the timeout (a redirect on the subshell): a FIFO there would hang it");
            }
        }
        var defLines = def.reset().find() ? new int[] {lineOf(source, def.start(1)), lineOf(source, def.end(1))} : new int[] {0, -1};
        if (!LEAVES_CHECKOUT.matcher(source).find()) {
            problems.add("the __launch mode does not leave the checkout cwd (`  CO=$(pwd); cd /; `): a bare relative word (cat x) would read"
                         + " checkout content through no checkout variable");
        }
        var segs = segments(source);
        var vars = new LinkedHashSet<>(roots);
        for (var grown = true; grown; ) {
            grown = false;
            for (var seg : segs) {
                for (var i = 0; i < seg.words().size(); i++) {
                    var a = assigned(seg, i);
                    if (a != null && !vars.contains(a[0]) && mentions(a[1], vars)) {
                        vars.add(a[0]);
                        grown = true;
                    }
                }
            }
        }
        var ownTemp = new LinkedHashSet<>(vars);
        for (var seg : segs) {
            for (var i = 0; i < seg.words().size(); i++) {
                var a = assigned(seg, i);
                if (a != null && !OWN_TEMP_VALUE.matcher(a[1]).matches()) {
                    ownTemp.remove(a[0]);
                }
            }
        }
        ownTemp.removeAll(roots);
        var pwdOnly = new LinkedHashMap<Integer, Integer>();
        var lines = source.split("\n", -1);
        for (var n = 0; n < lines.length; n++) {
            var m = PWD_ONLY_CD.matcher(stripComment(lines[n]));
            while (m.find()) {
                pwdOnly.merge(n + 1, 1, Integer::sum);
            }
        }
        var calls = new ArrayList<Call>();
        for (var seg : segs) {
            var verdict = classify(seg, vars, ownTemp, pwdOnly, defLines);
            if (verdict != null) {
                calls.add(new Call(seg.line(), verdict, String.join(" ", seg.full())));
            }
        }
        for (var call : calls) {
            if (call.verdict().startsWith("VIOLATION")) {
                problems.add(call.toString());
            }
        }
        return new Audit(roots, vars, ownTemp, calls, problems);
    }

    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    private static int count(String text, String needle) {
        var n = 0;
        for (var at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    /// {name, value as written} if word i assigns a variable: a word of the leading assignment prefix (after keywords and `local`),
    /// or the variable of `for NAME in <words>` (its value: those words).
    private static String[] assigned(Seg seg, int i) {
        var w = seg.words();
        var j = 0;
        while (j < w.size() && KEYWORDS.contains(w.get(j))) {
            j++;
        }
        if (j < w.size() && (w.get(j).equals("local") || w.get(j).equals("export"))) {
            j++;
        }
        if (j + 2 < w.size() && w.get(j).equals("for") && w.get(j + 2).equals("in")) {
            return i == j + 1 ? new String[] {w.get(i), String.join(" ", seg.full().subList(j + 3, w.size()))} : null;
        }
        for (; j <= i && j < w.size(); j++) {
            var m = ASSIGN.matcher(w.get(j));
            if (!m.matches()) {
                return null;
            }
            if (j == i) {
                var full = ASSIGN.matcher(seg.full().get(i));
                return full.matches() ? new String[] {full.group(1), full.group(2)} : null;
            }
        }
        return null;
    }

    static boolean mentions(String text, Set<String> vars) {
        for (var v : vars) {
            if (Pattern.compile("\\$\\{?[#!]?" + Pattern.quote(v) + "(?![A-Za-z0-9_])").matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> mentioned(String text, Set<String> vars) {
        var hit = new LinkedHashSet<String>();
        for (var v : vars) {
            if (mentions(text, Set.of(v))) {
                hit.add(v);
            }
        }
        return hit;
    }

    private static final Pattern REDIRECT = Pattern.compile("^([0-9]*|&)(<|>>|>)(&?)(.*)$", Pattern.DOTALL);

    /// null: the command expands no checkout variable (outside the definition).
    private static String classify(Seg seg, Set<String> vars, Set<String> ownTemp, Map<Integer, Integer> pwdOnly, int[] defLines) {
        var words = new ArrayList<String>();
        var opens = new ArrayList<String[]>();   // {op, target}
        for (var i = 0; i < seg.words().size(); i++) {
            var w = seg.words().get(i);
            var m = REDIRECT.matcher(w);
            if (m.matches() && !w.startsWith("<<")) {
                if (!m.group(3).isEmpty()) {
                    continue;                                   // >&2, 2>&1: a dup, opens nothing
                }
                var target = m.group(4).isEmpty() && i + 1 < seg.words().size() ? seg.words().get(++i) : m.group(4);
                opens.add(new String[] {m.group(2), target});
                continue;
            }
            words.add(w);
        }
        var k = 0;
        while (k < words.size() && (KEYWORDS.contains(words.get(k)) || ASSIGN.matcher(words.get(k)).matches())) {
            k++;
        }
        var c = k < words.size() && (words.get(k).equals("builtin") || words.get(k).equals("command")) ? k + 1 : k;
        if (c < words.size() && CD.contains(words.get(c))) {
            if (seg.line() >= defLines[0] && seg.line() <= defLines[1]) {
                return null;                                    // bounded()'s own (cd "$d" && exec timeout ...): the definition rule checks it
            }
            if (pwdOnly.getOrDefault(seg.line(), 0) > 0) {
                pwdOnly.merge(seg.line(), -1, Integer::sum);
                return "NO-CONTENT cd (the pwd-only $(cd \"$X\" && pwd): its subshell runs nothing else)";
            }
            if (words.size() == c + 2 && words.get(c).equals("cd") && words.get(c + 1).equals("/")) {
                return "NO-CONTENT cd / (leaves the checkout cwd: a bare relative word then resolves under /, never in the checkout)";
            }
            return "VIOLATION (" + String.join(" ", words.subList(c, words.size())) + " outside bounded(): a relative read after it names no checkout variable)";
        }
        var bounded = k < words.size() && words.get(k).equals("bounded");
        if (!bounded) {
            for (var o : opens) {
                if (RELATIVE.matcher(o[1].replace("\"", "")).find()) {
                    return "VIOLATION (redirect " + o[0] + " onto the relative path " + o[1] + ": it resolves against the cwd, not through a checkout variable)";
                }
            }
            for (var w : words.subList(Math.min(k, words.size()), words.size())) {
                if (RELATIVE.matcher(w.replace("\"", "")).find()) {
                    return "VIOLATION (the relative path " + w + ", not through bounded: the launch's cwd is the checkout)";
                }
            }
        }
        var all = String.join(" ", seg.words());
        if (!mentions(all, vars)) {
            return null;
        }
        for (var o : opens) {
            if (!mentioned(o[1], vars).isEmpty()) {
                return "VIOLATION (redirect " + o[0] + " onto checkout content " + o[1] + ", not through bounded)";
            }
        }
        k = 0;
        while (k < words.size() && (KEYWORDS.contains(words.get(k)) || ASSIGN.matcher(words.get(k)).matches())) {
            k++;
        }
        if (k < words.size() && words.get(k).equals("for")) {
            return null;
        }
        if (k == words.size()) {
            return "ASSIGN (no command; derives a checkout variable)";
        }
        var cmd = words.get(k);
        var args = String.join(" ", words.subList(k, words.size()));
        if (cmd.equals("bounded")) {
            var read = words.subList(Math.min(k + 4, words.size()), words.size());
            var head = String.join(" ", words.subList(k + 1, Math.min(k + 3, words.size())));
            var temp = mentioned(String.join(" ", read) + " " + head, ownTemp);
            if (temp.isEmpty()) {
                return "BOUNDED";
            }
            var capped = k + 3 < words.size() && words.get(k + 3).equals("-") && read.size() == 6
                         && String.join(" ", read).startsWith(CAPPED_READ) && ownTemp.stream().anyMatch(v -> read.get(5).equals("\"$" + v + "\""));
            return capped ? "BOUNDED (capped own-temp read: CAP_PY, BB_TEST_READ_CAP bytes)"
                          : "VIOLATION (bounded() reads own-temp " + temp + " without the byte cap: it must be - " + CAPPED_READ + "\"$V\")";
        }
        if (cmd.equals("exec") && k + 1 < words.size() && words.get(k + 1).equals("\"$HERE/bb-build\"")) {
            return ENQUEUE;
        }
        if (NO_CONTENT.containsKey(cmd)) {
            return "NO-CONTENT " + cmd + " (" + NO_CONTENT.get(cmd) + ")";
        }
        var hit = mentioned(args, vars);
        if (hit.isEmpty()) {
            return null;
        }
        return "VIOLATION (" + cmd + " opens checkout content " + hit + ", not through bounded)";
    }

    /// Simple commands, each with its flat words (command substitutions replaced by SUB, single-quoted text blanked) and its
    /// full words (as written, for the printed list and assignment values).
    private static List<Seg> segments(String source) {
        var lines = source.split("\n", -1);
        var out = new ArrayList<Seg>();
        String heredoc = null;
        var logical = new StringBuilder();
        var start = 0;
        for (var n = 0; n < lines.length; n++) {
            var line = lines[n];
            if (heredoc != null) {
                if (line.strip().equals(heredoc)) {
                    heredoc = null;
                }
                continue;
            }
            if (logical.isEmpty()) {
                start = n + 1;
            }
            var hd = Pattern.compile("<<-?\\s*'?([A-Za-z_]+)'?").matcher(stripComment(line));
            if (hd.find()) {
                heredoc = hd.group(1);
            }
            if (line.endsWith("\\") && !stripComment(line).isEmpty() && stripComment(line).endsWith("\\")) {
                logical.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            logical.append(line);
            parse(stripComment(logical.toString()), start, out);
            logical.setLength(0);
        }
        return out;
    }

    /// The line without its comment: a # at a word start, outside quotes and expansions.
    static String stripComment(String line) {
        var q = 0;   // 0 none, 1 single, 2 double
        var depth = 0;
        for (var i = 0; i < line.length(); i++) {
            var c = line.charAt(i);
            if (q == 1) {
                if (c == '\'') {
                    q = 0;
                }
                continue;
            }
            if (c == '\\') {
                i++;
                continue;
            }
            if (q == 2) {
                if (c == '"') {
                    q = 0;
                }
                continue;
            }
            if (c == '\'') {
                q = 1;
            } else if (c == '"') {
                q = 2;
            } else if (c == '$' && i + 1 < line.length() && (line.charAt(i + 1) == '{' || line.charAt(i + 1) == '#')) {
                i++;
                if (line.charAt(i) == '{') {
                    depth++;
                }
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == '#' && depth == 0 && (i == 0 || Character.isWhitespace(line.charAt(i - 1)) || line.charAt(i - 1) == ';')) {
                return line.substring(0, i).stripTrailing();
            }
        }
        return line;
    }

    /// Splits text into simple commands; each $(..) is parsed as its own command(s) and stands as SUB in its parent.
    private static void parse(String text, int line, List<Seg> out) {
        var words = new ArrayList<String>();
        var full = new ArrayList<String>();
        var flat = new StringBuilder();
        var raw = new StringBuilder();
        var i = 0;
        var n = text.length();
        while (i <= n) {
            var c = i < n ? text.charAt(i) : '\n';
            if (separator(text, i, c, raw)) {
                if (!raw.isEmpty()) {
                    words.add(flat.toString());
                    full.add(raw.toString());
                    flat.setLength(0);
                    raw.setLength(0);
                }
                if (c != ' ' && c != '\t') {
                    if (!words.isEmpty()) {
                        out.add(new Seg(line, List.copyOf(words), List.copyOf(full)));
                    }
                    words.clear();
                    full.clear();
                }
                i++;
                continue;
            }
            var end = wordPart(text, i, flat, raw, line, out);
            i = end;
        }
    }

    private static boolean separator(String text, int i, char c, StringBuilder raw) {
        if (c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '|' || c == '(' || c == ')') {
            return true;
        }
        if (c == '&') {
            return raw.isEmpty() || raw.charAt(raw.length() - 1) != '>';   // 2>&1 and >&2 are redirect words
        }
        var alone = i + 1 >= text.length() || text.charAt(i + 1) == ' ' || text.charAt(i + 1) == ';';
        return (c == '{' || c == '}') && raw.isEmpty() && alone;
    }

    /// Consumes one word part at i (a quoted string, an expansion or one plain char); appends its flat and raw forms.
    private static int wordPart(String text, int i, StringBuilder flat, StringBuilder raw, int line, List<Seg> out) {
        var c = text.charAt(i);
        if (c == '\\') {
            var e = Math.min(i + 2, text.length());
            flat.append(text, i, e);
            raw.append(text, i, e);
            return e;
        }
        if (c == '\'') {
            var e = text.indexOf('\'', i + 1);
            e = e < 0 ? text.length() : e + 1;
            flat.append("''");
            raw.append(text, i, e);
            return e;
        }
        if (c == '"') {
            var j = i + 1;
            flat.append('"');
            raw.append('"');
            while (j < text.length() && text.charAt(j) != '"') {
                if (text.charAt(j) == '\\') {
                    var e = Math.min(j + 2, text.length());
                    flat.append(text, j, e);
                    raw.append(text, j, e);
                    j = e;
                } else if (text.charAt(j) == '$') {
                    j = dollar(text, j, flat, raw, line, out);
                } else {
                    flat.append(text.charAt(j));
                    raw.append(text.charAt(j));
                    j++;
                }
            }
            flat.append('"');
            raw.append('"');
            return Math.min(j + 1, text.length());
        }
        if (c == '$') {
            return dollar(text, i, flat, raw, line, out);
        }
        flat.append(c);
        raw.append(c);
        return i + 1;
    }

    private static int dollar(String text, int i, StringBuilder flat, StringBuilder raw, int line, List<Seg> out) {
        if (text.startsWith("$((", i)) {
            var e = close(text, i + 1);
            flat.append(text, i, e);
            raw.append(text, i, e);
            return e;
        }
        if (text.startsWith("$(", i)) {
            var e = close(text, i + 1);
            parse(text.substring(i + 2, e - 1), line, out);
            flat.append(SUB);
            raw.append(text, i, e);
            return e;
        }
        if (text.startsWith("${", i)) {
            var e = close(text, i + 1);
            flat.append(text, i, e);
            raw.append(text, i, e);
            return e;
        }
        if (text.startsWith("$'", i)) {
            var e = text.indexOf('\'', i + 2);
            e = e < 0 ? text.length() : e + 1;
            flat.append("''");
            raw.append(text, i, e);
            return e;
        }
        flat.append('$');
        raw.append('$');
        return i + 1;
    }

    /// The index after the bracket that closes the one at open ('(' or '{'), skipping quoted text.
    private static int close(String text, int open) {
        var o = text.charAt(open);
        var c = o == '(' ? ')' : '}';
        var depth = 0;
        var q = 0;
        for (var i = open; i < text.length(); i++) {
            var ch = text.charAt(i);
            if (q == 1) {
                if (ch == '\'') {
                    q = 0;
                }
            } else if (ch == '\\') {
                i++;
            } else if (q == 2) {
                if (ch == '"') {
                    q = 0;
                } else if (ch == '$' && i + 1 < text.length() && text.charAt(i + 1) == o) {
                    i = close(text, i + 1) - 1;
                }
            } else if (ch == '\'') {
                q = 1;
            } else if (ch == '"') {
                q = 2;
            } else if (ch == o) {
                depth++;
            } else if (ch == c && --depth == 0) {
                return i + 1;
            }
        }
        return text.length();
    }

    private BbTestReadAudit() {}
}
