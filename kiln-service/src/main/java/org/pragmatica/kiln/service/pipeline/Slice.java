package org.pragmatica.kiln.service.pipeline;

import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.kiln.service.github.Credential;
import org.pragmatica.kiln.service.github.GhAnswer;
import org.pragmatica.kiln.service.github.GitHubHttp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The pipeline's share of a cycle (#102): a [GitHubHttp] that sends at most `size` requests until [#reset]. A request
/// over the slice is refused by [#ready], before the shared budget counts it, so it is never sent and never charged.
public record Slice(GitHubHttp http, AtomicInteger used, int size) implements GitHubHttp {
    /// The cycle's slice is spent: the cycle ends, and the next one goes on.
    enum Spent implements Cause {
        SPENT("the cycle's request slice is spent");
        private final String message;
        Spent(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    public static Slice slice(GitHubHttp http, int size) {
        return new Slice(http, new AtomicInteger(), size);
    }

    @Contract
    void reset() {
        used.set(0);
    }

    @Override
    public Promise<Unit> ready() {
        return used.get() >= size
               ? Spent.SPENT.promise()
               : http.ready();
    }

    @Override
    public Promise<GhAnswer> get(String pathAndQuery, Option<String> etag) {
        used.incrementAndGet();

        return http.get(pathAndQuery, etag);
    }

    @Override
    public Promise<GhAnswer> post(String path, String json) {
        used.incrementAndGet();

        return http.post(path, json);
    }

    @Override
    public Credential.Mode mode() {
        return http.mode();
    }
}
