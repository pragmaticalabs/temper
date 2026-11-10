package org.pragmatica.kiln.service.http;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Owner 2026-11-05 (kiln-f1-design Q-1): with `[server] bind` set to the host's first non-loopback IPv4 address, kiln
/// answers GET there, refuses every write that does not come from loopback (403), keeps the loopback listener (a POST
/// via loopback is accepted), keeps the Host guard (421) and accepts a configured `hostNames` entry. With no such
/// address the tests are SKIPPED, never passed.
///
/// Probes (recorded in the state file): drop the 403 check, accept any Host, or decide "local" from a header instead of
/// the socket, and the matching test here goes red.
class LanBindIT {
    private LoopbackServer server;
    private InetAddress lan;
    private final AtomicInteger posts = new AtomicInteger();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void listen() throws Exception {
        var outside = firstNonLoopbackV4();

        Assumptions.assumeTrue(outside.isPresent(), "the host has no non-loopback IPv4 address: nothing to bind");
        lan = outside.get();
        server = LoopbackServer.start(0,
                                      Option.some(lan.getHostAddress()),
                                      List.of("Kiln.Lan"),
                                      Router.router(List.of(Route.get("/",
                                                                      _ -> Promise.success(ApiResponse.text("ok"))),
                                                            Route.post("/in",
                                                                       _ -> {
                                                                           posts.incrementAndGet();

                                                                           return Promise.success(ApiResponse.text("written"));
                                                                       }))))
                               .unwrap();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop().await(TimeSpan.timeSpan(10).seconds());
        }
    }

    @Test
    void aGet_fromTheLanAddress_isAnswered() throws Exception {
        var response = send("GET", lan.getHostAddress(), "/", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
    }

    @Test
    void aPost_fromTheLanAddress_isRefused403_andNeverReachesTheRoute() throws Exception {
        var response = send("POST", lan.getHostAddress(), "/in", "x");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(posts.get()).isZero();
    }

    /// A header cannot make a LAN connection local: the check reads the socket.
    @Test
    void aPost_fromTheLanAddress_withForwardedForLoopback_isStillRefused403() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + lan.getHostAddress() + ":" + server.port() + "/in"))
                                 .header("X-Forwarded-For", "127.0.0.1")
                                 .header("Forwarded", "for=127.0.0.1")
                                 .POST(HttpRequest.BodyPublishers.ofString("x"))
                                 .build();

        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(posts.get()).isZero();
    }

    @Test
    void aPost_viaLoopback_isStillAccepted() throws Exception {
        var response = send("POST", "127.0.0.1", "/in", "x");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("written");
        assertThat(posts.get()).isEqualTo(1);
    }

    @Test
    void aForeignHost_isRefused421_onBothListeners() throws Exception {
        assertThat(statusFor(lan.getHostAddress(), "evil.example")).isEqualTo(421);
        assertThat(statusFor("127.0.0.1", "evil.example")).isEqualTo(421);
    }

    @Test
    void aHostNamesEntry_isAccepted_caseInsensitively_withAnyPort() throws Exception {
        assertThat(statusFor(lan.getHostAddress(), "kiln.lan")).isEqualTo(200);
        assertThat(statusFor(lan.getHostAddress(), "KILN.LAN:9999")).isEqualTo(200);
        assertThat(statusFor(lan.getHostAddress(), lan.getHostAddress() + ":8")).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String address, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + address + ":" + server.port() + path))
                                 .method(method,
                                         body == null
                                         ? HttpRequest.BodyPublishers.noBody()
                                         : HttpRequest.BodyPublishers.ofString(body))
                                 .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /// The Host header is restricted in `HttpClient`, so this writes the request on a raw socket and returns the status.
    private int statusFor(String address, String host) throws Exception {
        try (var socket = new Socket(address, server.port())) {
            socket.getOutputStream()
                  .write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));

            return Integer.parseInt(new String(socket.getInputStream().readAllBytes(),
                                               StandardCharsets.ISO_8859_1).split(" ") [1]);
        }
    }

    private static Optional<InetAddress> firstNonLoopbackV4() throws SocketException {
        return NetworkInterface.networkInterfaces()
                               .filter(LanBindIT::up)
                               .flatMap(NetworkInterface::inetAddresses)
                               .filter(address -> address instanceof Inet4Address
                                                  && !address.isLoopbackAddress()
                                                  && !address.isLinkLocalAddress()
                                                  && !address.isAnyLocalAddress())
                               .min(Comparator.comparing(InetAddress::getHostAddress));
    }

    private static boolean up(NetworkInterface face) {
        try {
            return face.isUp() && !face.isLoopback();
        } catch (SocketException e) {
            return false;
        }
    }
}
