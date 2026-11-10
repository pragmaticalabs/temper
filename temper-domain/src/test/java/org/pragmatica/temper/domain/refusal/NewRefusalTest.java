package org.pragmatica.temper.domain.refusal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// m1-design §6: a data-driven refusal can only carry a code the domain allows, whoever builds it.
class NewRefusalTest {
    @Test
    void newRefusal_accepts_aDataDrivenRule() {
        var parsed = ok(NewRefusal.newRefusal(RefusalRule.ItTestRequired.itTestRequired("R-2190", "ember"),
                                              Option.some(2190),
                                              "flaky ITs",
                                              Option.some("#2190 closes")));

        assertThat(parsed.code()).isEqualTo("R-2190");
    }

    @ParameterizedTest
    @ValueSource(strings = {"R-ENV", "R-M2", "R-SHELL", "R-DISK", "port-group", "R-", "R-lower"})
    void newRefusal_refusesStructuralOrMalformedCodes_evenWhenBuiltDirectly(String code) {
        assertThat(failure(NewRefusal.newRefusal(RefusalRule.ItTestRequired.itTestRequired(code, "ember"),
                                                 Option.none(),
                                                 "x",
                                                 Option.none()))).isNotNull();
    }

    @Test
    void newRefusal_refusesBlankReasonNonPositiveIssueOrBlankUntil() {
        var rule = RefusalRule.ItTestRequired.itTestRequired("R-OK", "ember");

        assertThat(failure(NewRefusal.newRefusal(rule, Option.none(), " ", Option.none()))).isNotNull();
        assertThat(failure(NewRefusal.newRefusal(rule, Option.some(0), "x", Option.none()))).isNotNull();
        assertThat(failure(NewRefusal.newRefusal(rule, Option.none(), "x", Option.some(" ")))).isNotNull();
    }
}
