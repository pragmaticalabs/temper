package org.pragmatica.kiln.core.github;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.parse.Number.parseLong;


/// The PR numbers a PR body names with a closing keyword and with `Part of #N` (spec §4.1), each list distinct and in
/// order of first appearance, read as GitHub links them: a keyword in any case
/// (`close[sd]?|fix(e[sd])?|resolve[sd]?`), an optional colon, one space, then `#N` or `owner/repo#N` naming `repo`
/// (compared case-insensitively), with no word character after `N`. Fenced code blocks, indented code blocks and inline
/// code are not read. An indented code block is approximated as lines indented by four spaces or a tab that follow a
/// blank line (or start the body) or another such line: an indented line that continues a paragraph is not code, and an
/// indented continuation of a list item after a blank line is read as code (so a keyword there is missed).
/// `Part of` is matched as the spec writes it, case and all, with `#N` alone.
///
/// Residual (accepted): GitHub closes the issue only when the PR merges into the default branch; kiln shows the keywords
/// whatever the base.
public record ClosingRefs(List<PrNumber> closes, List<PrNumber> partOf) {
    private static final Pattern CLOSES = Pattern.compile("(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?):? ([\\w.-]+/[\\w.-]+)?#(\\d+)(?!\\w)");

    private static final int CLOSES_REPO = 1;
    private static final int CLOSES_NUMBER = 2;
    private static final Pattern PART_OF = Pattern.compile("\\bPart of #(\\d+)(?!\\w)");
    private static final int PART_OF_NUMBER = 1;

    /// A fence of three or more backticks or tildes (an info string may follow), up to a closing fence or the end of the
    /// body. A closing fence is a bare line of the same character, at least as long as the opener: ```` ```js ```` opens
    /// a block but never closes one (CommonMark).
    private static final Pattern FENCED_CODE = Pattern.compile("(?ms)^ {0,3}(?:(`{3,})[^\\n]*.*?(?:^ {0,3}\\1`*[ \\t]*$|\\z)"
                                                              + "|(~{3,})[^\\n]*.*?(?:^ {0,3}\\2~*[ \\t]*$|\\z))");

    /// A run of lines indented by four spaces or a tab, at the start of the body or after a blank line (kept, group 1).
    private static final Pattern INDENTED_CODE = Pattern.compile("(?m)(\\A|^[ \\t]*\\n)(?:(?: {4}|\\t)[^\\n]*(?:\\n|\\z))+");

    /// A backtick string up to the next one of the same length, within one paragraph.
    private static final Pattern INLINE_CODE = Pattern.compile("(?s)(`+)(?!`)(?:(?!\\n[ \\t]*\\n).)+?(?<!`)\\1(?!`)");

    public ClosingRefs {
        closes = List.copyOf(closes);
        partOf = List.copyOf(partOf);
    }

    /// `repo` is the configured `owner/name`: an `owner/repo#N` naming another repository is no reference to this one.
    public static ClosingRefs parse(String body, String repo) {
        var prose = prose(body);

        return new ClosingRefs(numbers(CLOSES.matcher(prose).results().filter(match -> namesRepo(match, repo)),
                                       CLOSES_NUMBER),
                               numbers(PART_OF.matcher(prose).results(),
                                       PART_OF_NUMBER));
    }

    /// The body with its code removed: fenced blocks and inline code become a space, so words either side stay apart,
    /// and an indented code block is dropped after the blank line before it.
    private static String prose(String body) {
        return INLINE_CODE.matcher(INDENTED_CODE.matcher(FENCED_CODE.matcher(body).replaceAll(" ")).replaceAll("$1")).replaceAll(" ");
    }

    private static boolean namesRepo(MatchResult match, String repo) {
        return option(match.group(CLOSES_REPO)).map(repo::equalsIgnoreCase)
                     .or(true);
    }

    private static List<PrNumber> numbers(Stream<MatchResult> matches, int group) {
        return matches.flatMap(match -> prNumber(match.group(group)).stream())
                      .distinct()
                      .toList();
    }

    /// Design-out: digits that are no PR number (`#0`, or too many for a long) are prose, not a reference, so they are
    /// skipped rather than failing the PR's decode; nothing downstream could act on them.
    private static Option<PrNumber> prNumber(String digits) {
        return parseLong(digits).flatMap(PrNumber::prNumber)
                        .option();
    }
}
