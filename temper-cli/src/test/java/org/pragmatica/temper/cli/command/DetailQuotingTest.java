package org.pragmatica.temper.cli.command;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// A reason detail (a worker's stderr tail can carry anything) stays one line and one quoted field (F6, adapted from
/// the M1 WP7 verifier's `VerifierDetailQuotingTest`): quotes, backslashes and line breaks are escaped, and so is every
/// other control character or line/paragraph separator, which no longer passes through raw.
class DetailQuotingTest {
    private static ResultRecordDto record(String detail) {
        return ResultRecordDto.resultRecordDto(5, "ERROR", some("infra"), some(detail),
                                               new SourceView(none(), some("a".repeat(40))), new RecipeDto.Gate(), List.of(),
                                               new ResultRecordDto.CountsDto(0, 0, 0, 0, 0, 0, 0, none(), none(), none()),
                                               List.of(), 0, List.of(), none(), ResultRecordDto.EvidenceDto.NOT_STORED,
                                               ResultRecordDto.TimingsDto.timingsDto(none(), none()),
                                               none());
    }

    @Test
    void quotesBackslashesAndNewlines_stayOneLine() {
        var line = Lines.waited(record("docker run failed (rc 125): say \"hi\"\nC:\\x\r\nnext"));

        assertThat(line).doesNotContain("\n", "\r");
        assertThat(line).contains("detail=\"docker run failed (rc 125): say \\\"hi\\\"\\nC:\\\\x\\r\\nnext\" testcases=0");
        assertThat(line.lines().count()).isEqualTo(1);
    }

    @Test
    void everyOtherControlOrSeparatorCharacter_isEscaped() {
        var line = Lines.waited(record("a\tb\u0085c d e\u001b[31mf\u0000g\u007fh é"));

        assertThat(line).doesNotContain("\t", "\u0085", " ", " ", "\u001b", "\u0000", "\u007f");
        assertThat(line).contains("detail=\"a\\tb\\u0085c\\u2028d\\u2029e\\u001b[31mf\\u0000g\\u007fh é\" testcases=0");
        assertThat(line.lines().count()).isEqualTo(1);
    }

    @Test
    void aBareValue_neverCarriesAControlCharacter() {
        assertThat(Lines.value("a\u001bb")).isEqualTo("\"a\\u001bb\"");
        assertThat(Lines.value("a b")).isEqualTo("\"a\\u2028b\"");
        assertThat(Lines.value("plain")).isEqualTo("plain");
    }

    /// A7, adapted from the verifier's `VerifierFormatCharTest`: a format character (Cf) such as a bidi override or a
    /// zero-width character is neither control nor separator, yet it can reorder or hide text on a terminal line.
    @Test
    void formatCharacters_areEscaped_inBareAndQuotedValues() {
        assertThat(Lines.value("ok\u202edab")).isEqualTo("\"ok\\u202edab\"");
        assertThat(Lines.value("a\u200bb")).isEqualTo("\"a\\u200bb\"");
        assertThat(Lines.value("\ufeffbom")).isEqualTo("\"\\ufeffbom\"");
        assertThat(Lines.waited(record("x\u202ey\u200bz\ufeff"))).contains("detail=\"x\\u202ey\\u200bz\\ufeff\" testcases=0");
    }
}
