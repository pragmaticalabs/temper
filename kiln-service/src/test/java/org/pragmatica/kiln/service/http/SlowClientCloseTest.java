package org.pragmatica.kiln.service.http;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// WP9 confirmation round: a client that announces a body and never sends it gets its 408 after [LoopbackServer#READ],
/// and the server then closes the connection itself (the [LoopbackServer#JDK_LIMITS]), instead of draining the body and
/// holding the socket and a thread until the client leaves. Run against a [ChildServer] in a fresh JVM, as kiln's own
/// process is: the JDK reads those limits once per JVM.
class SlowClientCloseTest {
    /// The child's first line that is a number: its port (anything it logs before is skipped).
    private static int port(BufferedReader lines) throws java.io.IOException {
        for (var line = lines.readLine(); line != null; line = lines.readLine()) {
            if (line.strip().matches("[0-9]+")) {
                return Integer.parseInt(line.strip());
            }
        }
        throw new IllegalStateException("the child server printed no port");
    }

    @Test
    void start_setsTheJdkLimits() {
        var server = LoopbackServer.start(0, Router.router(List.of())).unwrap();

        server.stop().await(TimeSpan.timeSpan(10).seconds());
        assertThat(System.getProperty("sun.net.httpserver.drainAmount")).isNotNull();
        assertThat(System.getProperty("sun.net.httpserver.maxReqTime")).isNotNull();
    }

    @Test
    void after408_theServerClosesTheConnection() throws Exception {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var child = HermeticProcess.builder(java, "-cp", classpath, ChildServer.class.getName()).redirectErrorStream(true).start();

        try (var lines = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
             var socket = new Socket()) {
            var port = port(lines);

            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
            socket.setSoTimeout(45_000);
            socket.getOutputStream().write(("POST /in HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nContent-Length: 10\r\n\r\n")
                                               .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            var in = socket.getInputStream();
            var head = new ByteArrayOutputStream();
            var chunk = new byte[1024];
            int n;
            while (!head.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n") && (n = in.read(chunk)) >= 0) {
                head.write(chunk, 0, n);
            }
            assertThat(head.toString(StandardCharsets.ISO_8859_1)).startsWith("HTTP/1.1 408");

            var answered = System.nanoTime();
            socket.setSoTimeout(10_000);
            var closed = false;
            try {
                while (in.read(chunk) >= 0) {
                    // the rest of the 408's body
                }
                closed = true;
            } catch (SocketTimeoutException stillOpen) {
                closed = false;
            }

            assertThat(closed).as("the server closed the connection after its 408").isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - answered)).isLessThan(Duration.ofSeconds(5));
        } finally {
            child.getOutputStream().close();
            child.destroy();
        }
    }
}
