package org.pragmatica.kiln.core.model;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.kiln.core.model.ModelError.invalid;


/// A git branch name (a PR's base or head ref, a checkout's branch), under the rules of `git check-ref-format --branch`
/// plus two stricter ones, kept on purpose for argv safety: no C1 control character and no leading `+`. So: not blank;
/// not `HEAD` or `@`; no control character ([Printable]), space, `:`, `^`, `~`, `?`, `*`, `[` or `\`; no `@{`, `..`
/// or `//`; not starting with `-`, `+`, `.` or `/`; not ending with `/` or `.`; and no component starting with `.` or
/// ending with `.lock`. So it can never be read as an option or a refspec (`+main`, `main:refs/heads/x`) or a revision
/// expression (`main~1`, `@{-1}`) when it becomes a git argument. A name git accepts but this refuses fails its decode.
public record BranchName(String value) {
    private static final Pattern CHARS = Pattern.compile("^[^-+\\p{Cc}\\u2028\\u2029 :^~?*\\[\\\\][^\\p{Cc}\\u2028\\u2029 :^~?*\\[\\\\]*$");

    public static Result<BranchName> branchName(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             invalid("branch"))
                     .flatMap(present -> Verify.ensure(present,
                                                       Verify.Is::matches,
                                                       CHARS,
                                                       invalid("branch")))
                     .flatMap(shaped -> Verify.ensure(shaped,
                                                      BranchName::refFormat,
                                                      invalid("branch")))
                     .map(BranchName::new);
    }

    /// The whole-name rules of `git check-ref-format`; a leading or trailing `/` and `//` show as an empty component.
    private static boolean refFormat(String name) {
        return ! name.equals("@")
               && !name.equals("HEAD")
               && !name.contains("..")
               && !name.contains("@{")
               && !name.endsWith(".")
               && components(name);
    }

    private static boolean components(String name) {
        return Arrays.stream(name.split("/", -1)).allMatch(BranchName::component);
    }

    private static boolean component(String component) {
        return ! component.isEmpty()
               && !component.startsWith(".")
               && !component.endsWith(".lock");
    }

    @Override
    public String toString() {
        return value;
    }
}
