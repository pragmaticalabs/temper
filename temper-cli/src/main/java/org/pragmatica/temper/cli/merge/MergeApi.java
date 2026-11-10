package org.pragmatica.temper.cli.merge;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.transport.HttpCall;
import org.pragmatica.temper.cli.transport.HttpReply;
import org.pragmatica.temper.cli.transport.HttpTransport;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.unitResult;


/// The T2 routes over the version-checked [HttpTransport]; every call carries its own bound.
///
/// Failures: `401` [CliError.General#UNAUTHORIZED], `403` [CliError.General#FORBIDDEN] (both exit 77), any other status
/// than the route's success [GateFault.Answered] with the body's `error` text (the verb decides what it means), an
/// answer that does not decode [CliError.BadPayload]; the transport's own (unreachable 69, version skew 13) pass as
/// they are.
record MergeApi(HttpTransport http, JsonMapper json) {
    static final String PR_FRESH = "/fleet/pr-fresh";
    static final String GO = "/fleet/go";
    static final String VERDICTS = "/fleet/verdicts";
    static final String QUEUE = "/fleet/merge-queue";
    static final String PATHS = "/fleet/ci-control-paths";
    static final String PR_AUTHORS = "/fleet/pr-authors";
    static final String REVIEWS = "/fleet/reviews";
    /// The review path's own bound (the controller's step 5 takes up to 120 s).
    static final Duration REVIEW_CALL = Duration.ofSeconds(150);
    static final int OK = 200;
    static final int CREATED = 201;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;

    static MergeApi mergeApi(HttpTransport http, JsonMapper json) {
        return new MergeApi(http, json);
    }

    /// `GET fleet/pr-fresh?repo=&pr=[&sha=]`.
    Promise<MergeWire.PrFresh> prFresh(String repo, long pr, Option<String> sha, Duration timeout) {
        var path = PR_FRESH + "?repo=" + encoded(repo) + "&pr=" + pr + sha.map(value -> "&sha=" + value).or("");

        return read(get(path, timeout), path, MergeWire.PrFresh.class);
    }

    /// `POST fleet/go` → 201.
    Promise<MergeWire.GoCreated> createGo(MergeWire.GoBody body, Duration timeout) {
        return written(GO, body, timeout, CREATED, MergeWire.GoCreated.class);
    }

    /// `POST fleet/go/{id}/consume {}` → 200.
    Promise<MergeWire.Consumed> consume(String go, Duration timeout) {
        return written(GO + "/" + go + "/consume", new MergeWire.EmptyBody(), timeout, OK, MergeWire.Consumed.class);
    }

    /// `POST fleet/go/{id}/outcome` → 200; the reply's body is not needed.
    Promise<Unit> outcome(String go, Object body, Duration timeout) {
        return acknowledged(GO + "/" + go + "/outcome", body, timeout, OK);
    }

    /// `POST fleet/verdicts` → 201.
    Promise<MergeWire.Registered> verdict(Object body, Duration timeout) {
        return written(VERDICTS, body, timeout, CREATED, MergeWire.Registered.class);
    }

    /// `GET fleet/merge-queue[?repo=][&base=]`.
    Promise<MergeWire.Queues> queue(Option<String> repo, Option<String> base, Duration timeout) {
        var query = Stream.concat(repo.map(value -> "repo=" + encoded(value)).stream(),
                                  base.map(value -> "base=" + encoded(value)).stream())
                          .collect(Collectors.joining("&"));
        var path = QUEUE + (query.isEmpty()
                            ? ""
                            : "?" + query);

        return read(get(path, timeout), path, MergeWire.Queues.class);
    }

    /// `POST fleet/merge-queue[/remove|/move]` → `expected`.
    Promise<MergeWire.QueuePosition> queueEdit(String suffix, Object body, int expected, Duration timeout) {
        return written(QUEUE + suffix, body, timeout, expected, MergeWire.QueuePosition.class);
    }

    /// `GET fleet/ci-control-paths?repo=`.
    Promise<MergeWire.Paths> paths(String repo, Duration timeout) {
        var path = PATHS + "?repo=" + encoded(repo);

        return read(get(path, timeout), path, MergeWire.Paths.class);
    }

    /// `POST fleet/ci-control-paths[/remove]` → `expected`; the reply's body is not needed.
    Promise<Unit> pathEdit(String suffix, Object body, int expected, Duration timeout) {
        return acknowledged(PATHS + suffix, body, timeout, expected);
    }

    /// `POST fleet/pr-authors` → 201.
    Promise<MergeWire.Authors> addAuthors(MergeWire.AuthorsAdd body, Duration timeout) {
        return written(PR_AUTHORS, body, timeout, CREATED, MergeWire.Authors.class);
    }

    /// `GET fleet/pr-authors?repo=&pr=`.
    Promise<MergeWire.Authors> authors(String repo, long pr, Duration timeout) {
        var path = PR_AUTHORS + "?repo=" + encoded(repo) + "&pr=" + pr;

        return read(get(path, timeout), path, MergeWire.Authors.class);
    }

    /// `POST fleet/reviews` → 200.
    Promise<MergeWire.Reviewed> review(Object body, Duration timeout) {
        return written(REVIEWS, body, timeout, OK, MergeWire.Reviewed.class);
    }

    private <T> Promise<T> read(HttpCall call, String path, Class<T> type) {
        return http.exchange(call)
                   .flatMap(reply -> expected(reply, OK, path, type).async());
    }

    private <T> Promise<T> written(String path, Object body, Duration timeout, int expected, Class<T> type) {
        return encodedBody(path, body).async()
                          .flatMap(text -> http.exchange(post(path, text, timeout)))
                          .flatMap(reply -> expected(reply, expected, path, type).async());
    }

    private Promise<Unit> acknowledged(String path, Object body, Duration timeout, int expected) {
        return encodedBody(path, body).async()
                          .flatMap(text -> http.exchange(post(path, text, timeout)))
                          .flatMap(reply -> status(path, reply, expected).async());
    }

    private <T> Result<T> expected(HttpReply reply, int expected, String path, Class<T> type) {
        return status(path, reply, expected).flatMap(_ -> json.readString(reply.body(),
                                                                          type)
                                                              .mapError(cause -> CliError.BadPayload.FACTORY.apply(path,
                                                                                                                   cause)));
    }

    private Result<Unit> status(String path, HttpReply reply, int expected) {
        return reply.status() == expected
               ? unitResult()
               : failure(path, reply).result();
    }

    private Cause failure(String path, HttpReply reply) {
        return switch (reply.status()) {
            case UNAUTHORIZED -> CliError.General.UNAUTHORIZED;
            case FORBIDDEN -> CliError.General.FORBIDDEN;
            default -> GateFault.Answered.FACTORY.apply(reply.status(), path, errorText(reply));
        };
    }

    /// The body's `error` text; a body without one is shown raw (FER: the raw text is the diagnosis, and the status is
    /// reported either way).
    private String errorText(HttpReply reply) {
        return json.readTree(reply.body())
                   .option()
                   .flatMap(node -> Option.from(node.path("error").stringValueOpt()))
                   .or(() -> reply.body()
                                  .strip());
    }

    private Result<String> encodedBody(String path, Object body) {
        return json.writeAsString(body)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause));
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpCall get(String path, Duration timeout) {
        return HttpCall.httpCall("GET", path, none()).withTimeout(timeout);
    }

    private static HttpCall post(String path, String body, Duration timeout) {
        return HttpCall.httpCall("POST", path, some(body)).withTimeout(timeout);
    }
}
