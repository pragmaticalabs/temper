package org.pragmatica.temper.ghapp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// CI-dependence pin: with every common-pool worker blocked (a 2-vCPU host has ONE), an exchange still completes,
/// which `HttpClient.sendAsync` alone does not guarantee (its returned future completes through the common pool).
class VirtualSendTest {
    @Test
    void send_completes_whileEveryCommonPoolWorkerIsBlocked() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var hold = new CountDownLatch(1);

        server.createContext("/",
                             exchange -> {
                                 exchange.sendResponseHeaders(204, -1);
                                 exchange.close();
                             });
        server.start();
        try {
            for (int i = 0; i <= ForkJoinPool.commonPool().getParallelism(); i++) {
                CompletableFuture.runAsync(() -> await(hold));
            }

            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")).build();
            var answer = VirtualSend.send(HttpClient.newHttpClient(),
                                          request,
                                          HttpResponse.BodyHandlers.discarding(),
                                          Duration.ofSeconds(10))
                                    .await(timeSpan(10).seconds());
            int status = answer.fold(cause -> - 1, response -> response.statusCode());

            assertThat(status).isEqualTo(204);
        } finally {
            hold.countDown();
            server.stop(0);
        }
    }

    @Test
    void send_overTheTimeout_failsAndInterruptsTheBlockedSend() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var release = new CountDownLatch(1);

        server.createContext("/",
                             exchange -> {
                                 await(release);
                                 exchange.close();
                             });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")).build();
            var started = System.nanoTime();
            var answer = VirtualSend.send(HttpClient.newHttpClient(),
                                          request,
                                          HttpResponse.BodyHandlers.discarding(),
                                          Duration.ofMillis(300))
                                    .await(timeSpan(10).seconds());
            boolean failed = answer.fold(cause -> true, response -> false);

            assertThat(failed).isTrue();
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(tookMillis).isLessThan(5000L);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void send_overTheTimeout_interruptsTheBlockedSendThread() throws Exception {
        var interrupted = new CountDownLatch(1);
        var client = new FakeClient(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            throw new IllegalStateException("not interrupted");
        });
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:9/")).build();

        VirtualSend.send(client, request, HttpResponse.BodyHandlers.discarding(), Duration.ofMillis(200));

        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void send_whenSendThrowsAnError_failsAtOnceNotAtTheTimeout() {
        var client = new FakeClient(() -> {
            throw new StackOverflowError("thrown by send");
        });
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:9/")).build();
        var started = System.nanoTime();
        var answer = VirtualSend.send(client, request, HttpResponse.BodyHandlers.discarding(), Duration.ofSeconds(30))
                                .await(timeSpan(10).seconds());
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        String outcome = answer.fold(cause -> cause.message(), response -> "succeeded");

        assertThat(outcome).contains("thrown by send");
        assertThat(tookMillis).isLessThan(5000L);
    }

    private interface Blocking {
        void run() throws Exception;
    }

    /// An HttpClient whose `send` runs `body`; nothing else is used by VirtualSend.
    private static final class FakeClient extends HttpClient {
        private final Blocking body;

        FakeClient(Blocking body) {
            this.body = body;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
            try {
                body.run();
            } catch (IOException | InterruptedException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("fake send returned without a response");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
