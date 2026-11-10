package org.pragmatica.temper.ghapp;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;


/// `HttpClient.sendAsync` without the common pool. The JDK completes the future `sendAsync` returns through
/// `ForkJoinPool.commonPool()` whatever executor the client has, and a 2-vCPU host sizes that pool to ONE worker: one
/// caller blocked in it and no exchange ever reports (the CI `ReviewRoutesIT` failures, 2026-11-06). This runs the
/// blocking `send` on a virtual thread and completes the promise there; the exchange is bounded by `timeout`, and a
/// failure (the timeout included) interrupts the send.
public final class VirtualSend {
    private VirtualSend() {}

    public static <T> Promise<HttpResponse<T>> send(HttpClient client,
                                                    HttpRequest request,
                                                    HttpResponse.BodyHandler<T> handler,
                                                    Duration timeout) {
        return send(client, request, handler, timeout, Causes::fromThrowable);
    }

    public static <T> Promise<HttpResponse<T>> send(HttpClient client,
                                                    HttpRequest request,
                                                    HttpResponse.BodyHandler<T> handler,
                                                    Duration timeout,
                                                    Fn1<Cause, Throwable> failure) {
        var promise = Promise.<HttpResponse<T>> promise();
        var sender = Thread.ofVirtual()
                           .name("http-send")
                           .unstarted(() -> complete(client, request, handler, failure, promise));

        sender.start();

        return promise.timeout(TimeSpan.timeSpan(timeout.toMillis()).millis())
                      .onFailureRun(sender::interrupt);
    }

    private static <T> void complete(HttpClient client,
                                     HttpRequest request,
                                     HttpResponse.BodyHandler<T> handler,
                                     Fn1<Cause, Throwable> failure,
                                     Promise<HttpResponse<T>> promise) {
        try {
            promise.succeed(client.send(request, handler));
        } catch (Throwable thrown) {
            // Throwable, not Exception: an Error thrown by send would otherwise leave the caller waiting out the timeout.
            promise.fail(failure.apply(thrown));
        }
    }
}
