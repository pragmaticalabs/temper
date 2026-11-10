package org.pragmatica.kiln.service.store.pg;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Page arithmetic of the checkout drill-down: 100 rows a page, and at least one page.
class PgViewStoreTest {
    @Test
    void pages_holdAHundredRowsEach_andThereIsAlwaysOne() {
        assertThat(PgViewStore.pages(0)).isEqualTo(1);
        assertThat(PgViewStore.pages(99)).isEqualTo(1);
        assertThat(PgViewStore.pages(100)).isEqualTo(1);
        assertThat(PgViewStore.pages(101)).isEqualTo(2);
    }
}
