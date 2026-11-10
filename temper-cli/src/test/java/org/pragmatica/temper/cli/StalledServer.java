package org.pragmatica.temper.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// A controller that answers every request with `200`, its headers and half a line, then stalls without closing: the
/// JDK's request timeout has ended at the headers, so only a bound over the whole exchange ends the call.
final class StalledServer implements AutoCloseable {
    private final ServerSocket server;
    private final List<Socket> open = new CopyOnWriteArrayList<>();

    private StalledServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::accept);
    }

    static StalledServer start() throws IOException {
        return new StalledServer();
    }

    String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                var socket = server.accept();

                open.add(socket);
                Thread.ofVirtual().start(() -> stall(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private static void stall(Socket socket) {
        try {
            skipHead(socket.getInputStream());
            socket.getOutputStream()
                  .write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 1000\r\n\r\n{\"v\":1,\"kind\":")
                                 .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            Thread.sleep(120_000);
        } catch (IOException | InterruptedException ended) {
            // closed by the test
        }
    }

    /// Reads up to and including the blank line that ends the request head.
    private static void skipHead(InputStream in) throws IOException {
        var line = new StringBuilder();

        while (true) {
            var value = in.read();

            if (value < 0) {
                throw new IOException("closed");
            }
            if (value == '\n') {
                if (line.toString().strip().isEmpty()) {
                    return;
                }
                line.setLength(0);
            } else {
                line.append((char) value);
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (var socket : open) {
            socket.close();
        }
    }
}
