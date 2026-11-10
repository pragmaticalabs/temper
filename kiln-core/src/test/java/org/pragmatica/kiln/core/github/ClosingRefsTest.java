package org.pragmatica.kiln.core.github;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.model.PrNumber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.ok;

/// spec §4.1: the closing keywords as GitHub links them (any case, an optional colon, `#N` or the configured
/// `owner/repo#N`, never inside code) and `Part of #N`.
class ClosingRefsTest {
    private static final String REPO = "pragmaticalabs/pragmatica";

    private static PrNumber[] numbers(long... raw) {
        return Arrays.stream(raw)
                     .mapToObj(value -> ok(PrNumber.prNumber(value)))
                     .toArray(PrNumber[]::new);
    }

    private static List<PrNumber> closes(String body) {
        return ClosingRefs.parse(body, REPO)
                          .closes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "closes", "closed", "fix", "fixes", "fixed", "resolve", "resolves", "resolved",
                            "Closes", "FIXES", "Resolved"})
    void parse_readsEveryClosingKeyword_inAnyCase(String keyword) {
        assertThat(closes("text\n" + keyword + " #42 more")).containsExactly(numbers(42));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disclosed #5", "closing #5", "fixup #5", "prefix #5", "closes#5", "closes  #5", "closes # 5",
                            "closes org/repo#5", "unresolved #5", "Fixes:: #5", "Fixes :#5", "closes #5x", "closes #5_",
                            "closes pragmaticalabs/pragmatica-fork#5"})
    void parse_readsNoClosingRef_withoutTheExactKeywordForm(String body) {
        assertThat(closes(body)).isEmpty();
    }

    /// r3: GitHub links a keyword followed by a colon.
    @ParameterizedTest
    @ValueSource(strings = {"Closes: #10", "fixes: #10", "RESOLVED: #10"})
    void parse_readsAKeywordWithAColon(String body) {
        assertThat(closes(body)).containsExactly(numbers(10));
    }

    /// r6: `#5x` is no reference; `#5` followed by punctuation or the end is.
    @Test
    void parse_readsANumber_onlyWhenNoWordCharacterFollows() {
        assertThat(closes("closes #5x, closes #6.")).containsExactly(numbers(6));
        assertThat(closes("closes #7")).containsExactly(numbers(7));
        assertThat(closes("closes #8)")).containsExactly(numbers(8));
    }

    /// `owner/repo#N` names this repo only when owner/repo is the configured one, compared case-insensitively.
    @Test
    void parse_readsTheFullForm_onlyForTheConfiguredRepo() {
        assertThat(closes("Closes pragmaticalabs/pragmatica#11")).containsExactly(numbers(11));
        assertThat(closes("fixes PragmaticaLabs/Pragmatica#12")).containsExactly(numbers(12));
        assertThat(closes("fixes: pragmaticalabs/pragmatica#13")).containsExactly(numbers(13));
        assertThat(closes("closes other/pragmatica#14, closes pragmaticalabs/other#15")).isEmpty();
        assertThat(ClosingRefs.parse("closes other/repo#16", "Other/Repo")
                              .closes()).containsExactly(numbers(16));
    }

    /// r4: a fenced code block (backticks or tildes, closed or running to the end) is not read.
    @Test
    void parse_readsNothingInsideAFencedCodeBlock() {
        assertThat(closes("text\n```\ncloses #20\n```\nfixes #21")).containsExactly(numbers(21));
        assertThat(closes("```java\nfixes #22\n```")).isEmpty();
        assertThat(closes("~~~\nfixes #23\n~~~\nresolves #24")).containsExactly(numbers(24));
        assertThat(closes("text\n```\nfixes #25 and the fence never closes")).isEmpty();
        assertThat(ClosingRefs.parse("```\nPart of #26\n```", REPO)
                              .partOf()).isEmpty();
    }

    /// r5: inline code is not read; text around it is.
    @Test
    void parse_readsNothingInsideInlineCode() {
        assertThat(closes("run `closes #30` to see, then fixes #31")).containsExactly(numbers(31));
        assertThat(closes("``closes #32 with a ` inside``")).isEmpty();
        assertThat(closes("a lone ` backtick, fixes #33")).containsExactly(numbers(33));
        assertThat(closes("`unclosed\n\nfixes #34 and `later`")).containsExactly(numbers(34));
    }

    @Test
    void parse_readsPartOf_asTheSpecWritesIt() {
        assertThat(ClosingRefs.parse("Part of #2468\n\nbody", REPO)
                              .partOf()).containsExactly(numbers(2468));
        assertThat(ClosingRefs.parse("part of #2468, Apart of #9, Part of #7x", REPO)
                              .partOf()).isEmpty();
    }

    @Test
    void parse_keepsEachNumberOnce_inOrderOfFirstAppearance() {
        var refs = ClosingRefs.parse("Closes #9, fixes #3. Resolves #9. Part of #4. Part of #04. Part of #2.", REPO);

        assertThat(refs.closes()).containsExactly(numbers(9, 3));
        assertThat(refs.partOf()).containsExactly(numbers(4, 2));
    }

    /// Digits that are no PR number are prose, skipped without failing the PR's decode.
    @Test
    void parse_skipsNumbersThatAreNoPrNumber() {
        assertThat(closes("closes #0 and closes #99999999999999999999 and closes #7")).containsExactly(numbers(7));
    }

    @Test
    void parse_readsNothingFromAnEmptyBody() {
        assertThat(ClosingRefs.parse("", REPO)).isEqualTo(new ClosingRefs(List.of(), List.of()));
    }

    /// n4b: a closing fence is a bare line of the opener's character, at least as long; ```` ```js ```` never closes.
    @Test
    void parse_closesAFence_onlyWithABareFenceOfTheSameCharacterAtLeastAsLong() {
        assertThat(closes("```\nfoo\n```js\ncloses #5\n```\nfixes #6")).containsExactly(numbers(6));
        assertThat(closes("````\n```\ncloses #1\n````\nfixes #2")).containsExactly(numbers(2));
        assertThat(closes("```\ncloses #1\n~~~\nfixes #3\n```\nfixes #2")).containsExactly(numbers(2));
        assertThat(closes("~~~~\ncloses #1\n~~~~~\nfixes #2")).containsExactly(numbers(2));
        assertThat(closes("```\ncloses #1\n   ```  \nfixes #2")).containsExactly(numbers(2));
    }

    /// n4c: lines indented by four spaces or a tab after a blank line are an indented code block; an indented line that
    /// continues a paragraph is not code (the stated approximation).
    @Test
    void parse_readsNothingInsideAnIndentedCodeBlock() {
        assertThat(closes("text\n\n    closes #5\nfixes #6")).containsExactly(numbers(6));
        assertThat(closes("    closes #5\n\nfixes #6")).containsExactly(numbers(6));
        assertThat(closes("x\n\n\tcloses #5\n    fixes #7\n\n    resolves #8\nfixes #6")).containsExactly(numbers(6));
        assertThat(closes("para\n    closes #5")).containsExactly(numbers(5));
        assertThat(closes("text\n\n   closes #9")).containsExactly(numbers(9));
    }
}
