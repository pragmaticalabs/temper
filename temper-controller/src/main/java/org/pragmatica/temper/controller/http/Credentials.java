package org.pragmatica.temper.controller.http;

import java.util.stream.Stream;

import org.pragmatica.http.Headers;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Tuple;
import org.pragmatica.lang.Tuple.Tuple2;

import static org.pragmatica.lang.Option.none;


/// The credentials the API accepts: the full bearer token, optionally a read token (`[server] readTokenFile`, D3) that
/// may call READ routes only, a worker token (`[server] workerTokenFile`, ruling R-M3-15) that may call READ routes
/// and `claim`, a verdict token (`[server] verdictTokenFile`) that may call the VERDICT routes
/// only and a Mac token (`[server] macTokenFile`) that may call the MAC routes only, and a lease token
/// (`Authorization: Lease <token>`, m3-design §6.4) for its task's lease verbs. The bearer tokens are held as
/// [BearerToken]s (SHA-256 only), so `toString` prints no token nor digest.
///
/// A presented bearer token is checked against EVERY configured bearer token on every request, the others even when
/// one matched: with no short-circuit, the time taken does not reveal which one matched. Whether an optional token is
/// configured at all is fixed at start, not a property of the request, so an absent one skipping its check leaks
/// nothing. The tokens are distinct ([org.pragmatica.temper.controller.config.Secrets] refuses a start otherwise), so
/// at most one matches. A `Lease` credential is classified by its scheme alone: whether it is the task's current
/// token is decided by the store's fenced UPDATE (409 `lease-lost`), never by a comparison here.
public record Credentials(BearerToken full,
                          Option<BearerToken> read,
                          Option<BearerToken> worker,
                          Option<BearerToken> verdict,
                          Option<BearerToken> mac) {
    public static Credentials credentials(BearerToken full, Option<BearerToken> read) {
        return new Credentials(full, read, none(), none(), none());
    }

    public static Credentials credentials(BearerToken full, Option<BearerToken> read, Option<BearerToken> worker) {
        return new Credentials(full, read, worker, none(), none());
    }

    public static Credentials credentials(BearerToken full,
                                          Option<BearerToken> read,
                                          Option<BearerToken> worker,
                                          Option<BearerToken> verdict,
                                          Option<BearerToken> mac) {
        return new Credentials(full, read, worker, verdict, mac);
    }

    /// The access a request's `Authorization` header grants; none for a missing, malformed or unknown credential.
    public Option<Access> classify(Headers headers) {
        return LeaseToken.presented(headers)
                         .filter(token -> !token.isEmpty())
                         .map(_ -> Access.LEASE)
                         .orElse(() -> BearerToken.presented(headers).flatMap(this::classified));
    }

    /// Every comparison is evaluated (they are arguments) before any result is looked at.
    private Option<Access> classified(String presented) {
        return access(full.matches(presented),
                      matches(read, presented),
                      matches(worker, presented),
                      matches(verdict, presented),
                      matches(mac, presented));
    }

    private static boolean matches(Option<BearerToken> token, String presented) {
        return token.map(configured -> configured.matches(presented))
                    .or(false);
    }

    private static Option<Access> access(boolean isFull,
                                         boolean isRead,
                                         boolean isWorker,
                                         boolean isVerdict,
                                         boolean isMac) {
        return Option.from(Stream.of(Tuple.tuple(isFull, Access.FULL),
                                     Tuple.tuple(isRead, Access.READ),
                                     Tuple.tuple(isWorker, Access.WORKER),
                                     Tuple.tuple(isVerdict, Access.VERDICT),
                                     Tuple.tuple(isMac, Access.MAC))
                                 .filter(Tuple2::first)
                                 .map(Tuple2::last)
                                 .findFirst());
    }
}
