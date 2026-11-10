package org.pragmatica.temper.controller.review;

import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.store.api.StoreError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The per-PR lock's two store failures on the wire (§5.3, §5.4): both are 503, so the CLI exits 69 and the verifier
/// lane retries; `BUSY` names the capacity refusal, `LOCK_LOST` sends the retry through step 7.
class ReviewClassifyTest {
    @Test
    void lockSessionsBusy_is503Busy() {
        assertThat(ReviewRoutes.classify(StoreError.PrLock.BUSY)).isInstanceOf(ApiError.Transient.class)
                  .extracting(cause -> cause.message())
                  .asString()
                  .isEqualTo("BUSY");
    }

    @Test
    void aLostLock_is503LockLost() {
        assertThat(ReviewRoutes.classify(StoreError.PrLock.LOST)).isInstanceOf(ApiError.Transient.class)
                  .extracting(cause -> cause.message())
                  .asString()
                  .isEqualTo("LOCK_LOST");
    }
}
