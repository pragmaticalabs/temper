package org.pragmatica.kiln.service.http;

import java.util.List;

import org.pragmatica.lang.Promise;

/// A [LoopbackServer] alone in its own JVM, for [SlowClientCloseTest]: the JDK reads its server limits once, when the
/// first server of a JVM is created, so only a fresh JVM shows what kiln's own process gets. Prints its port, then serves
/// until its stdin closes.
public final class ChildServer {
    private ChildServer() {}

    public static void main(String[] args) throws Exception {
        var server = LoopbackServer.start(0, Router.router(List.of(Route.post("/in", request -> Promise.success(ApiResponse.text("ok"))))))
                                   .unwrap();

        System.out.println(server.port());
        System.out.flush();
        while (System.in.read() >= 0) {
            // serve until the parent closes stdin
        }
        server.stop();
    }
}
