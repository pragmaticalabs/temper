package org.pragmatica.temper.cli.transport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.temper.cli.CliError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// A call's own bound (`HttpCall.withTimeout`) wins over the transport's (verifier F3): against a controller that
/// accepts and never answers, a call bounded at 500 ms fails as unreachable within seconds, though the transport's
/// own bound is 60 s. Probe: ignore `call.timeout()` → the call waits 60 s → red (test timeout).
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class HttpTransportTest {
    @Test
    void exchange_honoursTheCallsOwnBound() throws IOException {
        try (var silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            var transport = HttpTransport.jdkHttpTransport(HttpTransport.httpClient(),
                                                           URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/api/v1"),
                                                           "token",
                                                           Duration.ofSeconds(60));
            var started = System.nanoTime();

            transport.exchange(HttpCall.httpCall("GET", "/fleet/heartbeat", none())
                                       .withTimeout(Duration.ofMillis(500)))
                     .await()
                     .onSuccess(reply -> Assertions.fail("expected unreachable, got " + reply))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(CliError.Unreachable.class));

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        }
    }

    @Test
    void withTimeout_keepsEveryOtherField() {
        var call = HttpCall.httpCall("POST", "/x", some("{}"))
                           .withLease("l")
                           .withVersion("v")
                           .withTimeout(Duration.ofSeconds(3));

        assertThat(call).isEqualTo(new HttpCall("POST",
                                                "/x",
                                                some("{}"),
                                                some("l"),
                                                some("v"),
                                                some(Duration.ofSeconds(3))));
        assertThat(call.toString()).isEqualTo("HttpCall[POST /x]");
    }
}
