package org.pragmatica.temper.cli.transport;

import java.time.Duration;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One request to the controller API: `path` is relative to `…/api/v1` and already carries its encoded query;
/// `json` is the request body for POSTs that have one. `lease` is a task's lease token (m3-design §6.4): when present
/// the request authenticates with `Authorization: Lease <token>` instead of the bearer token. `version` is the CLI's
/// build version, sent as the `Temper-Version` header (D-6, set by [VersionCheck] on every call). `timeout` bounds this
/// call's answer instead of the transport's own bound, when present (`events` sizes each long-poll to the time it has
/// left before its heartbeat deadline).
public record HttpCall(String method,
                       String path,
                       Option<String> json,
                       Option<String> lease,
                       Option<String> version,
                       Option<Duration> timeout) {
    public static HttpCall httpCall(String method, String path, Option<String> json) {
        return new HttpCall(method, path, json, none(), none(), none());
    }

    /// The same call under a lease token.
    public HttpCall withLease(String token) {
        return new HttpCall(method, path, json, some(token), version, timeout);
    }

    /// The same call naming the CLI's build version.
    public HttpCall withVersion(String cliVersion) {
        return new HttpCall(method, path, json, lease, some(cliVersion), timeout);
    }

    /// The same call, its answer bounded by `bound` instead of the transport's own bound.
    public HttpCall withTimeout(Duration bound) {
        return new HttpCall(method, path, json, lease, version, some(bound));
    }

    /// Never prints the token.
    @Override
    public String toString() {
        return "HttpCall[" + method + " " + path + "]";
    }
}
