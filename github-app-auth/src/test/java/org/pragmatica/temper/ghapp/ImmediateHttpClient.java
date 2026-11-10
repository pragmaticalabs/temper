package org.pragmatica.temper.ghapp;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;


/// A client whose exchange answers at once (`send` and `sendAsync` alike): the mint's completion is then attached to a
/// settled promise, which is the interleaving a fast real response only hits by chance.
final class ImmediateHttpClient extends HttpClient {
    private final AtomicInteger count = new AtomicInteger();
    private final IntFunction<String> createdBody;

    ImmediateHttpClient(IntFunction<String> createdBody) {
        this.createdBody = createdBody;
    }

    int requests() {
        return count.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        var body = createdBody.apply(count.incrementAndGet());
        HttpResponse<String> response = (HttpResponse<String>) java.lang.reflect.Proxy.newProxyInstance(HttpResponse.class.getClassLoader(),
                                                                                                        new Class<?>[]{HttpResponse.class},
                                                                                                        (proxy, method, args) -> switch (method.getName()) {
            case "statusCode" -> 201;
            case "body" -> body;
            default -> throw new UnsupportedOperationException(method.getName());
        });

        return CompletableFuture.completedFuture((HttpResponse<T>) response);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                            HttpResponse.BodyHandler<T> handler,
                                                            HttpResponse.PushPromiseHandler<T> push) {
        return sendAsync(request, handler);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        return sendAsync(request, handler).join();
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
