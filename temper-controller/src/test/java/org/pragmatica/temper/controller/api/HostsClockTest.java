package org.pragmatica.temper.controller.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.store.api.HostName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// m4-design §11: `GET /hosts` judges `retired` at one instant, the clock read once per request, so two hosts with the
/// same `retireAt` never disagree. The clock here steps 1 ms on every read, from 1 ms before `retireAt`: a read per host
/// would judge the first host at `retireAt - 1ms` (not retired) and the second at `retireAt` (retired).
/// Probe: read the clock in `hostView` once per host -> red.
class HostsClockTest {
    private static final Instant RETIRE_AT = Instant.parse("2026-11-13T00:00:00Z");

    private final AtomicInteger reads = new AtomicInteger();
    private final ApiFixture fixture = new ApiFixture(() -> RETIRE_AT.minusMillis(1).plusMillis(reads.getAndIncrement()),
                                                      List.of(retiring("cloudbb-3"), retiring("cloudbb-4")));

    @Test
    void hosts_judgeRetiredAtOneClockRead_perRequest() {
        var before = fixture.authed(HttpMethod.GET, "/api/v1/hosts", "");

        assertThat(before.status()).as(before.bodyText()).isEqualTo(HttpStatus.OK);
        assertThat(reads.get()).as("clock reads for one request").isEqualTo(1);
        assertThat(before.bodyText()).as("both hosts judged at retireAt - 1ms")
                                     .contains("\"name\":\"cloudbb-3\"", "\"name\":\"cloudbb-4\"")
                                     .contains("\"retired\":false")
                                     .doesNotContain("\"retired\":true");

        var at = fixture.authed(HttpMethod.GET, "/api/v1/hosts", "");

        assertThat(reads.get()).as("clock reads after two requests").isEqualTo(2);
        assertThat(at.bodyText()).as("both hosts judged at retireAt")
                                 .contains("\"name\":\"cloudbb-3\"", "\"name\":\"cloudbb-4\"")
                                 .contains("\"retired\":true")
                                 .doesNotContain("\"retired\":false");
    }

    private static ControllerConfig.Host retiring(String name) {
        return ControllerConfig.Host.host(HostName.hostName(name).unwrap(),
                                          "localhost",
                                          "builder",
                                          Path.of("/home/builder/.temper/key"),
                                          Path.of("/home/builder/.temper/known_hosts"),
                                          3,
                                          16384,
                                          false,
                                          true,
                                          some(RETIRE_AT));
    }
}
