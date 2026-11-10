package org.pragmatica.temper.store.pg;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.refusal.NewRefusal;
import org.pragmatica.temper.domain.refusal.RefusalRule;
import org.pragmatica.temper.domain.refusal.TestRunner;
import org.pragmatica.temper.store.api.RefusalRow;
import org.pragmatica.temper.store.api.RefusalStore;
import org.pragmatica.temper.store.api.StoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Refusals as data through the management surface (spec §2.5, m1-design §6): list, add (409 on an existing code),
/// expire (idempotent, 404 on an unknown code); the matcher round-trips through its jsonb form.
@Tag("pg")
class RefusalStoreIT {
    private static StoreHarness harness;
    private static RefusalStore refusals;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
        refusals = harness.store.refusals();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void list_showsTheSeedRows() {
        assertThat(refusals.list().await(PgFixture.WAIT).unwrap()).extracting(row -> row.rule().code()).contains("R-1553", "R-FAILSAFE");
    }

    @Test
    void add_thenListed_active_andTheMatcherRoundTrips() {
        var rule = RefusalRule.ExcludeTestClass.excludeTestClass("R-ADD-ONE", "FlakyTest", "#9", some(TestRunner.SUREFIRE));
        var added = refusals.add(NewRefusal.newRefusal(rule, some(9), "flaky", some("until #9 closes")).unwrap())
                            .await(PgFixture.WAIT)
                            .unwrap();

        assertThat(added).isEqualTo(RefusalRow.refusalRow(rule, some(9), "flaky", some("until #9 closes"), none()));
        assertThat(refusals.active().await(PgFixture.WAIT).unwrap()).contains(rule);
        assertThat(refusals.list().await(PgFixture.WAIT).unwrap()).contains(added);
    }

    @Test
    void add_existingCode_failsRefusalExists_evenWhenExpired() {
        var rule = RefusalRule.ItTestRequired.itTestRequired("R-ADD-TWICE", "forge-tests");
        var refusal = NewRefusal.newRefusal(rule, none(), "twice", none()).unwrap();

        refusals.add(refusal).await(PgFixture.WAIT).unwrap();
        refusals.expire("R-ADD-TWICE").await(PgFixture.WAIT).unwrap();
        refusals.add(refusal)
                .await(PgFixture.WAIT)
                .onSuccess(row -> fail("expected RefusalExists, got " + row))
                .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.RefusalExists.FACTORY.apply("R-ADD-TWICE")));
    }

    @Test
    void expire_removesFromActive_keepsTheFirstExpiry() {
        var rule = RefusalRule.ItTestRequired.itTestRequired("R-EXPIRE", "forge-tests");

        refusals.add(NewRefusal.newRefusal(rule, none(), "expire me", none()).unwrap()).await(PgFixture.WAIT).unwrap();
        var first = refusals.expire("R-EXPIRE").await(PgFixture.WAIT).unwrap();
        var second = refusals.expire("R-EXPIRE").await(PgFixture.WAIT).unwrap();

        assertThat(first.expiredAt().isPresent()).isTrue();
        assertThat(second.expiredAt()).isEqualTo(first.expiredAt());
        assertThat(refusals.active().await(PgFixture.WAIT).unwrap()).doesNotContain(rule);
    }

    @Test
    void expire_unknownCode_failsRefusalNotFound() {
        refusals.expire("R-NOPE")
                .await(PgFixture.WAIT)
                .onSuccess(row -> fail("expected RefusalNotFound, got " + row))
                .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.RefusalNotFound.FACTORY.apply("R-NOPE")));
    }
}
