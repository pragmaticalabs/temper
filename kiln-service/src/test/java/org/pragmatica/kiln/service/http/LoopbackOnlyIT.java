package org.pragmatica.kiln.service.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// kiln-f1-design §7.2 (WP9) and §8 "Server reachability": the server binds the loopback address only. A connection to
/// the host's first non-loopback address on the server's port is refused; with no such address the test is SKIPPED,
/// never passed. The positive control: the same server answers on the loopback address.
///
/// Probe (recorded in the WP9 state file): bind the wildcard address in [LoopbackServer#start] and this goes red, the
/// non-loopback connection being accepted.
class LoopbackOnlyIT {
    private LoopbackServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop().await(TimeSpan.timeSpan(10).seconds());
        }
    }

    @Test
    void aNonLoopbackAddress_isRefused_theLoopbackAnswers() throws Exception {
        var outside = firstNonLoopback();

        Assumptions.assumeTrue(outside.isPresent(), "the host has no non-loopback address: nothing to refuse");
        server = LoopbackServer.start(0, Router.router(List.of(Route.get("/", _ -> Promise.success(ApiResponse.text("ok"))))))
                               .unwrap();

        assertThat(server.address().getAddress().isLoopbackAddress()).isTrue();
        assertThat(HttpClient.newHttpClient()
                             .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/")).build(),
                                   HttpResponse.BodyHandlers.ofString())
                             .body()).isEqualTo("ok");
        assertThatThrownBy(() -> connect(outside.get(), server.port())).as("connect to %s:%d", outside.get(), server.port())
                                                                       .isInstanceOf(ConnectException.class);
    }

    private static void connect(InetAddress address, int port) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 3_000);
        }
    }

    /// The first address of an interface that is up, neither loopback nor link-local (a link-local IPv6 address needs a
    /// scope to be reached), IPv4 first, in a stable order.
    private static Optional<InetAddress> firstNonLoopback() throws SocketException {
        return NetworkInterface.networkInterfaces()
                               .filter(LoopbackOnlyIT::up)
                               .flatMap(NetworkInterface::inetAddresses)
                               .filter(address -> !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                                                  && !address.isAnyLocalAddress())
                               .min(Comparator.comparing((InetAddress address) -> address instanceof Inet6Address)
                                              .thenComparing(InetAddress::getHostAddress));
    }

    private static boolean up(NetworkInterface face) {
        try {
            return face.isUp() && !face.isLoopback();
        } catch (SocketException e) {
            return false;
        }
    }
}
