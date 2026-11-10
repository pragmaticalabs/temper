package org.pragmatica.kiln.service.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.summary.Counts;
import org.pragmatica.kiln.service.http.ApiError;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// [ApiRoutes]' parameters and the summary's counts (WP9). `status=` is a [CheckoutStatus] name, the spelling the
/// pages link (open item 5); the display spelling is refused, never read as another status.
class ApiRoutesTest {
    private static final Instant AS_OF = Instant.parse("2026-10-30T12:00:00Z");

    private static Cause refused(Result<?> result) {
        return result.fold(cause -> cause, value -> Assertions.fail("expected a failure, got " + value));
    }

    @Test
    void status_isTheEnumName_only() {
        assertThat(ApiRoutes.status(success("IN_USE")).unwrap()).isEqualTo(CheckoutStatus.IN_USE);
        assertThat(ApiRoutes.status(success("NEEDS_SALVAGE")).unwrap()).isEqualTo(CheckoutStatus.NEEDS_SALVAGE);
        for (var refused : List.of("IN-USE", "in_use", "ACTIVE ", "")) {
            assertThat(ApiRoutes.status(success(refused)).isFailure()).as(refused).isTrue();
        }
        for (var status : CheckoutStatus.values()) {
            assertThat(ApiRoutes.status(success(status.name())).unwrap()).isEqualTo(status);
        }
    }

    @Test
    void page_isOneOrMore_defaultOne() {
        assertThat(ApiRoutes.page(none()).unwrap()).isEqualTo(1);
        assertThat(ApiRoutes.page(some("1")).unwrap()).isEqualTo(1);
        assertThat(ApiRoutes.page(some("7")).unwrap()).isEqualTo(7);
        for (var raw : List.of("0", "-1", "x", "1.5")) {
            assertThat(refused(ApiRoutes.page(some(raw)))).as(raw).isInstanceOf(ApiError.BadRequest.class);
        }
    }

    @Test
    void host_isAConfiguredNameOrTheMac() {
        assertThat(ApiRoutes.hostName("mac").unwrap()).isEqualTo(HostName.MAC);
        assertThat(ApiRoutes.hostName("bigboy").unwrap().value()).isEqualTo("bigboy");
        assertThat(refused(ApiRoutes.hostName("Big Boy"))).isInstanceOf(ApiError.BadRequest.class);
    }

    /// Without `since`, the last 60 minutes before now (spec §7); a `since` that is no ISO instant is a 400.
    @Test
    void since_defaultsToTheLastHour() {
        assertThat(ApiRoutes.since(none(), AS_OF).unwrap()).isEqualTo(AS_OF.minusSeconds(3_600));
        assertThat(ApiRoutes.since(some("2026-10-30T11:59:59Z"), AS_OF).unwrap()).isEqualTo(Instant.parse("2026-10-30T11:59:59Z"));
        assertThat(refused(ApiRoutes.since(some("yesterday"), AS_OF))).isInstanceOf(ApiError.BadRequest.class);
    }

    @Test
    void totals_sumEveryHost() {
        var bigboy = new HostCounts(HostName.hostName("bigboy").unwrap(), Map.of(CheckoutStatus.NEEDS_SALVAGE, 2, CheckoutStatus.STALE_UNOWNED, 1, CheckoutStatus.ACTIVE, 9), 0, 0, none());
        var mac = new HostCounts(HostName.MAC, Map.of(CheckoutStatus.NEEDS_SALVAGE, 3), 0, 0, none());

        assertThat(ApiRoutes.totals(List.of(bigboy, mac))).isEqualTo(new Counts(5, 1));
        assertThat(ApiRoutes.totals(List.of())).isEqualTo(new Counts(0, 0));
    }
}
