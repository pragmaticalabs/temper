package org.pragmatica.temper.ghapp;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [GitHubAppAuth] over the JDK HTTP client. State changes happen under one lock and never block in it: the exchange
/// is asynchronous, and its completion records the outcome and then settles the one shared [Promise] that every
/// caller of that mint waits on.
final class MintingAuth implements GitHubAppAuth {
    private static final System.Logger LOG = System.getLogger("org.pragmatica.temper.ghapp");
    /// A new token is minted when less than this remains (about every 50 minutes).
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(10);

    /// Delay before the next mint after the 1st, 2nd, ... failure; the last repeats.
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30),
                                                  Duration.ofMinutes(1),
                                                  Duration.ofMinutes(2),
                                                  Duration.ofMinutes(4),
                                                  Duration.ofMinutes(5));

    /// The token in hand. `toString` never shows it.
    private record Held(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "Held[expiresAt=" + expiresAt + "]";
        }
    }

    private final AppAuthConfig config;
    private final HttpClient client;
    private final Clock clock;
    private final String body;
    private final Object lock = new Object();
    private Option<Held> held = none();
    private Option<Promise<Unit>> inflight = none();
    private Option<Cause> lastFailure = none();
    private Instant retryAt = Instant.MIN;
    private int failures;

    MintingAuth(AppAuthConfig config, HttpClient client, Clock clock, String body) {
        this.config = config;
        this.client = client;
        this.clock = clock;
        this.body = body;
    }

    @Override
    public Promise<SecretToken> token() {
        synchronized (lock) {
            var now = clock.instant();

            return remaining(now).filter(left -> left.compareTo(REFRESH_MARGIN) >= 0)
                            .isPresent()
                   ? served(now)
                   : refreshed(now);
        }
    }

    /// True while a mint is in flight (tests wait for the background refresh to settle).
    boolean mintInFlight() {
        synchronized (lock) {
            return inflight.isPresent();
        }
    }

    private Option<Duration> remaining(Instant now) {
        return held.map(token -> Duration.between(now, token.expiresAt()));
    }

    /// A mint is due. While the held token has not expired the caller gets it at once and the mint runs in the
    /// background (if none is in flight and the backoff allows); only a caller with no valid token waits for the mint.
    private Promise<SecretToken> refreshed(Instant now) {
        return usable(now)
               ? servedWhileRefreshing(now)
               : awaited(now);
    }

    private boolean usable(Instant now) {
        return remaining(now).filter(left -> left.isPositive())
                        .isPresent();
    }

    private Promise<SecretToken> servedWhileRefreshing(Instant now) {
        // Read the held token before the mint starts: a mint that settles at once runs its completion inside this
        // call (same thread, same lock) and would otherwise swap the token this caller is meant to get.
        var current = served(now);

        if (inflight.isEmpty() && !now.isBefore(retryAt)) {
            start(now);
        }

        return current;
    }

    /// Join the mint in flight, start one if the backoff allows, else report what is known.
    private Promise<SecretToken> awaited(Instant now) {
        return inflight.map(this::afterMint)
                       .or(() -> now.isBefore(retryAt)
                                 ? served(now)
                                 : afterMint(start(now)));
    }

    private Promise<SecretToken> afterMint(Promise<Unit> done) {
        return done.flatMap(_ -> settled());
    }

    private Promise<SecretToken> settled() {
        synchronized (lock) {
            return served(clock.instant());
        }
    }

    /// The held token if it has not expired, else [AppAuthError.CredentialUnavailable].
    private Promise<SecretToken> served(Instant now) {
        return held.filter(token -> token.expiresAt()
                                         .isAfter(now))
                   .map(token -> Promise.success(new SecretToken(token.value())))
                   .or(() -> unavailable(now));
    }

    /// What is known: the last mint's failure, else that the held token has expired (with when a mint is next
    /// allowed), else that none was ever minted. Never the token.
    private Promise<SecretToken> unavailable(Instant now) {
        return AppAuthError.CredentialUnavailable.FACTORY.apply(lastFailure.map(Cause::message).or(() -> expired(now))).promise();
    }

    private String expired(Instant now) {
        return held.map(token -> "token expired at " + token.expiresAt()
                                + "; next mint after " + (retryAt.isAfter(now)
                                                          ? retryAt
                                                          : now))
                   .or("no token minted yet");
    }

    private Promise<Unit> start(Instant now) {
        var done = Promise.<Unit> promise();

        inflight = some(done);
        mint(now).withResult(result -> result.onSuccess(this::minted)
                                             .onFailure(this::failed))
            .withResult(_ -> finished(done));

        return done;
    }

    /// The mint is over, outcome recorded and logged: its callers may read the outcome. `inflight` was cleared with
    /// the outcome, in one critical section, so no later caller joins a mint whose result is already stale.
    private void finished(Promise<Unit> done) {
        done.succeed(Unit.unit());
    }

    private Promise<MintAnswer.Minted> mint(Instant now) {
        return AppKey.load(config.keyFile())
                     .flatMap(key -> Jwt.sign(config.clientId(),
                                              key,
                                              now))
                     .map(jwt -> MintRequest.request(config, jwt, body))
                     .async()
                     .flatMap(this::exchange)
                     .flatMap(response -> MintAnswer.parse(response).async());
    }

    private Promise<HttpResponse<String>> exchange(HttpRequest request) {
        return VirtualSend.send(client,
                                request,
                                MintingAuth::answerBody,
                                GitHubAppAuth.TIMEOUT,
                                MintingAuth::failedExchange).mapError(MintingAuth::unreachable);
    }

    private static HttpResponse.BodySubscriber<String> answerBody(HttpResponse.ResponseInfo info) {
        return HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
                                                     1024 * 1024);
    }

    /// A failure of the exchange as a [AppAuthError.MintUnreachable] with a safe reason: the deadline's cause is ours,
    /// its text goes through [Redact] all the same.
    private static Cause unreachable(Cause origin) {
        return origin instanceof AppAuthError
               ? origin
               : AppAuthError.MintUnreachable.FACTORY.apply(Redact.text(origin.message(), Redact.REASON_CAP));
    }

    private static Cause failedExchange(Throwable thrown) {
        return AppAuthError.MintUnreachable.FACTORY.apply(Redact.reason(thrown));
    }

    /// A token with less than [#REFRESH_MARGIN] left is usable until it expires, but is never worth a mint per call:
    /// the next mint waits the backoff, which grows while GitHub keeps answering with short tokens.
    private void minted(MintAnswer.Minted answer) {
        synchronized (lock) {
            var now = clock.instant();

            held = some(new Held(answer.token(), answer.expiresAt()));
            inflight = none();
            lastFailure = none();
            if (Duration.between(now, answer.expiresAt()).compareTo(REFRESH_MARGIN) < 0) {
                retryAt = now.plus(BACKOFF.get(Math.min(failures, BACKOFF.size() - 1)));
                failures++;
            } else {
                failures = 0;
                retryAt = Instant.MIN;
            }
        }

        LOG.log(System.Logger.Level.INFO,
                "mint ok identity={0} installation={1} expires_at={2} permissions={3}",
                config.identity(),
                config.installationId(),
                answer.expiresAt(),
                answer.permissions());
    }

    private void failed(Cause cause) {
        Duration delay;

        synchronized (lock) {
            delay = BACKOFF.get(Math.min(failures, BACKOFF.size() - 1));
            failures++;
            retryAt = clock.instant().plus(delay);
            lastFailure = some(cause);
            inflight = none();
        }

        LOG.log(System.Logger.Level.WARNING,
                "mint failed identity={0} installation={1} cause={2}; next attempt in {3}s",
                config.identity(),
                config.installationId(),
                cause.message(),
                delay.toSeconds());
        logClockSkew(cause);
    }

    private void logClockSkew(Cause cause) {
        if (cause instanceof AppAuthError.MintRefused refused && MintAnswer.clockSkew(refused)) {
            LOG.log(System.Logger.Level.WARNING,
                    "clock-skew identity={0} local_time={1}: {2}",
                    config.identity(),
                    clock.instant(),
                    refused.reason());
        }
    }
}
