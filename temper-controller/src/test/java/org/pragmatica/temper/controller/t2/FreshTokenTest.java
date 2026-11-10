package org.pragmatica.temper.controller.t2;

import java.security.SecureRandom;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// [FreshToken]: the token's shape, and that it binds every field, the key included (GO.g, token half).
class FreshTokenTest {
    private static final FreshToken.Fields FIELDS = FreshToken.Fields.fields("o/r", 7, "a".repeat(40), "main",
                                                                             "c".repeat(40), 3,
                                                                             "e6d0c1f2-0000-4000-8000-000000000001", 42,
                                                                             1_793_800_000_000L);

    @Test
    void token_hasTheV1Shape() {
        var token = FreshToken.freshToken(new SecureRandom()).token(FIELDS).unwrap();

        assertThat(token).matches("^v1\\.1793800000000\\.42\\.[A-Za-z0-9_-]{43}$");
    }

    @Test
    void valid_ownToken_isValid() {
        var key = FreshToken.freshToken(new SecureRandom());

        assertThat(key.valid(key.token(FIELDS).unwrap(), FIELDS).unwrap()).isTrue();
    }

    /// Every bound field changes the token; another key (a restart) voids it.
    @Test
    void valid_anyFieldChanged_orAnotherKey_isInvalid() {
        var key = FreshToken.freshToken(new SecureRandom());
        var token = key.token(FIELDS).unwrap();

        assertThat(List.of(FreshToken.Fields.fields("o/x", 7, FIELDS.sha(), "main", FIELDS.baseTip(), 3, FIELDS.fleetEpoch(), 42, FIELDS.checkedAtMs()),
                                     FreshToken.Fields.fields("o/r", 8, FIELDS.sha(), "main", FIELDS.baseTip(), 3, FIELDS.fleetEpoch(), 42, FIELDS.checkedAtMs()),
                                     FreshToken.Fields.fields("o/r", 7, "b".repeat(40), "main", FIELDS.baseTip(), 3, FIELDS.fleetEpoch(), 42, FIELDS.checkedAtMs()),
                                     FreshToken.Fields.fields("o/r", 7, FIELDS.sha(), "dev", FIELDS.baseTip(), 3, FIELDS.fleetEpoch(), 42, FIELDS.checkedAtMs()),
                                     FreshToken.Fields.fields("o/r", 7, FIELDS.sha(), "main", "d".repeat(40), 3, FIELDS.fleetEpoch(), 42, FIELDS.checkedAtMs()),
                                     FreshToken.Fields.fields("o/r", 7, FIELDS.sha(), "main", "", 3, FIELDS.fleetEpoch(), 42, FIELDS.checkedAtMs()),
                                     FreshToken.Fields.fields("o/r", 7, FIELDS.sha(), "main", FIELDS.baseTip(), 4, FIELDS.fleetEpoch(), 42, FIELDS.checkedAtMs()),
                                     FreshToken.Fields.fields("o/r", 7, FIELDS.sha(), "main", FIELDS.baseTip(), 3, "x", 42, FIELDS.checkedAtMs()),
                                     FreshToken.Fields.fields("o/r", 7, FIELDS.sha(), "main", FIELDS.baseTip(), 3, FIELDS.fleetEpoch(), 41, FIELDS.checkedAtMs()),
                                     FreshToken.Fields.fields("o/r", 7, FIELDS.sha(), "main", FIELDS.baseTip(), 3, FIELDS.fleetEpoch(), 42, FIELDS.checkedAtMs() + 1000)))
            .allSatisfy(other -> assertThat(key.valid(token, other).unwrap()).as(other.toString()).isFalse());
        assertThat(FreshToken.freshToken(new SecureRandom()).valid(token, FIELDS).unwrap()).isFalse();
        assertThat(key.valid(token + "x", FIELDS).unwrap()).isFalse();
    }
}
