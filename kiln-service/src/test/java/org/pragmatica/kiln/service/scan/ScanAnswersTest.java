package org.pragmatica.kiln.service.scan;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.scan.ScanTransportError.BadAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.scan.ScanFakes.path;

/// The `reach` and `size` answers, read strictly: anything off contract fails the whole answer.
class ScanAnswersTest {
    private static final Sha A = Sha.sha("a".repeat(40)).unwrap();
    private static final Sha B = Sha.sha("b".repeat(40)).unwrap();
    private static final String A_IN = "{\"sha\":\"" + A.value() + "\",\"ref\":\"refs/heads/main\"}";
    private static final String B_OUT = "{\"sha\":\"" + B.value() + "\",\"ref\":null}";

    @Test
    void reach_aRefIsInTheBare_nullIsNot() {
        var answer = ScanAnswers.reach(Set.of(A, B), List.of(A_IN, B_OUT, "{\"end\":true,\"count\":2}"));

        assertThat(answer.unwrap()).isEqualTo(Map.of(A, true, B, false));
    }

    @Test
    void reach_offContract_failsTheWholeAnswer() {
        var trailer = "{\"end\":true,\"count\":2}";

        assertBad(List.of(A_IN, B_OUT), "no trailer");
        assertBad(List.of(A_IN, B_OUT, "{\"end\":true,\"count\":3}"), "count off");
        assertBad(List.of(A_IN, B_OUT, "{\"end\":false,\"count\":2}"), "end false");
        assertBad(List.of(A_IN, B_OUT, "{\"count\":2}"), "end absent");
        assertBad(List.of(A_IN, "{\"end\":true,\"count\":1}"), "a SHA missing");
        assertBad(List.of(A_IN, A_IN, "{\"end\":true,\"count\":2}"), "a SHA twice");
        assertBad(List.of(A_IN, "{\"sha\":\"" + "c".repeat(40) + "\",\"ref\":null}", trailer), "another SHA");
        assertBad(List.of(A_IN, "{\"sha\":\"" + B.value() + "\",\"ref\":7}", trailer), "ref not text");
        assertBad(List.of(A_IN, "{\"sha\":\"" + B.value() + "\"}", trailer), "ref absent");
        assertBad(List.of(A_IN, "{\"sha\":\"nothex\",\"ref\":null}", trailer), "bad SHA");
        assertBad(List.of(A_IN, "{\"sha\":7,\"ref\":null}", trailer), "SHA not text");
        assertBad(List.of(A_IN, "[1]", trailer), "not an object");
        assertBad(List.of(A_IN, "not json", trailer), "not JSON");
        assertBad(List.of(A_IN, "{\"sha\":\"" + B.value() + "\",\"ref\":null,\"ref\":\"x\"}", trailer), "a key twice");
        assertBad(List.of(), "no line");
    }

    @Test
    void size_readsTheKb_ofTheAskedPath() {
        assertThat(ScanAnswers.size(path("/w/co/a"), List.of("{\"path\":\"/w/co/a\",\"kb\":42}")).unwrap()).isEqualTo(42L);
        assertThat(ScanAnswers.size(path("/w/co/a"), List.of("{\"path\":\"/w/co/a\",\"kb\":0}")).unwrap()).isZero();
    }

    @Test
    void size_offContract_isRefused() {
        var a = path("/w/co/a");

        assertThat(ScanAnswers.size(a, List.of("{\"path\":\"/w/co/b\",\"kb\":42}")).isFailure()).as("another path").isTrue();
        assertThat(ScanAnswers.size(a, List.of("{\"path\":\"/w/co/a\",\"kb\":-1}")).isFailure()).as("negative").isTrue();
        assertThat(ScanAnswers.size(a, List.of("{\"path\":\"/w/co/a\",\"kb\":1.5}")).isFailure()).as("fraction").isTrue();
        assertThat(ScanAnswers.size(a, List.of("{\"path\":\"/w/co/a\",\"kb\":\"42\"}")).isFailure()).as("text").isTrue();
        assertThat(ScanAnswers.size(a, List.of("{\"path\":\"/w/co/a\",\"kb\":99999999999999999999}")).isFailure()).as("over a long").isTrue();
        assertThat(ScanAnswers.size(a, List.of("{\"path\":\"/w/co/a\"}")).isFailure()).as("kb absent").isTrue();
        assertThat(ScanAnswers.size(a, List.of("{\"kb\":42}")).isFailure()).as("path absent").isTrue();
        assertThat(ScanAnswers.size(a, List.of("{\"path\":7,\"kb\":42}")).isFailure()).as("path not text").isTrue();
        assertThat(ScanAnswers.size(a, List.of("{\"path\":\"/w/co/a\",\"kb\":1}", "{\"path\":\"/w/co/a\",\"kb\":1}")).isFailure())
            .as("two lines").isTrue();
        assertThat(ScanAnswers.size(a, List.of()).isFailure()).as("no line").isTrue();
        assertThat(ScanAnswers.size(a, List.of("[]")).isFailure()).as("not an object").isTrue();
    }

    private static void assertBad(List<String> lines, String why) {
        var answer = ScanAnswers.reach(Set.of(A, B), lines);

        boolean bad = answer.fold(cause -> cause instanceof BadAnswer, _ -> false);

        assertThat(bad).as("%s: %s", why, answer).isTrue();
    }
}
