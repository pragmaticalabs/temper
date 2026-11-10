package org.pragmatica.temper.cli.alarm;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Network;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;

import tools.jackson.databind.PropertyNamingStrategies;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// The GitHub REST calls of the `github-issue` sink, over the JDK `HttpClient`, against `base` (`https://api.github.com`
/// or a test's fake). The token travels only in the `Authorization: Bearer` header: every failure names its request by
/// method and path ([CliError.SinkFailed], [CliError.SinkRejected]), and [#toString] leaves the token out.
///
/// Every answer body is read up to its cap, [#MAX_ISSUE_PAGE_BODY] or [#MAX_COMMENT_PAGE_BODY] bytes for a page of a
/// list and [#MAX_BODY] for one object; a longer one fails the request ([CliError.SinkFailed]), so a runaway answer cannot exhaust the checker's
/// small heap (`-Xmx256m`), while no answer GitHub can send within its own limits ever reaches the cap. [#TIMEOUT]
/// bounds the wait for the answer's headers only (the JDK request timeout ends there); a body that stalls after them is
/// bounded by the sink's delivery deadline, whose cut fails the run (exit 2) and ends the process.
public record GithubApi(HttpClient client, URI base, String repo, String token, JsonMapper json) {
    public static final String LABEL = "temper-alarm";
    /// GitHub's cap on an issue or comment body, in characters.
    public static final int MAX_BODY_CHARS = 65_536;
    /// One listed item at its worst: every body character JSON-escaped as a surrogate pair of six-byte unicode escapes
    /// (12 bytes), plus 192 KiB for the rest (100 labels at about 1.4 KiB = 140 KiB, the user, the other objects):
    /// 65536 x 12 + 192 KiB = 768 KiB + 192 KiB = 960 KiB.
    public static final int MAX_ITEM_BYTES = MAX_BODY_CHARS * 12 + 192 * 1024;
    /// 2 MiB for an answer of one object (the opened issue, a new comment, the closed issue): one item at its worst,
    /// 960 KiB, fits with over 1 MiB to spare.
    public static final int MAX_BODY = 2 * 1024 * 1024;
    /// Issues per page of [#openIssues]: 20 items at their worst are 20 x 960 KiB = 19200 KiB, under
    /// [#MAX_ISSUE_PAGE_BODY].
    public static final int ISSUES_PER_PAGE = 20;
    /// 19 MiB (19456 KiB) for one page of the open list: a page of 20 items at their worst (19200 KiB) can never exceed
    /// it. The list is read once, before any comments, one page at a time; the bodies kept after a page are cut to
    /// what the sink reads ([Issue]).
    public static final int MAX_ISSUE_PAGE_BODY = 19 * 1024 * 1024;
    /// Comments per page of [#commentsSince]: 10 items at their worst are 10 x 960 KiB = 9600 KiB, under
    /// [#MAX_COMMENT_PAGE_BODY].
    public static final int COMMENTS_PER_PAGE = 10;
    /// 10 MiB (10240 KiB) for one page of comments: a page of 10 items at their worst (9600 KiB) can never exceed it.
    /// Up to 7 conditions read a page each at once (one page at a time each), so 7 x 10 MiB = 70 MiB of raw answers at
    /// most, plus their parsing (the chunks read and the decoded text): the unit's `-Xmx256m` holds it; the bodies
    /// kept after a page are cut to what the sink reads ([Comment]).
    public static final int MAX_COMMENT_PAGE_BODY = 10 * 1024 * 1024;
    /// Pages of recent comments read at most, 50 x 10: up to 500 comments within the repeat interval are read in full;
    /// more fails the read.
    public static final int MAX_PAGES = 50;
    /// Real issues (pull requests not counted) among the open ones read at most; more fails the read.
    public static final int MAX_ISSUES = 500;
    /// Pages of the open list read at most, pull requests included: 50 x 20 = 1000 items, so [#MAX_ISSUES] is
    /// reachable beside as many pull requests; the bound that holds when pull requests fill the pages.
    public static final int MAX_ISSUE_PAGES = 50;
    /// The start of every marker line the sink writes; an issue keeps only its body lines that start with it.
    public static final String MARKER_PREFIX = "<!-- temper-alarm:";
    /// [P] GitHub answers in well under a second; a minute-scale stall is an outage, and the next timer run retries.
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int OK = 200;
    private static final int CREATED = 201;
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");

    /// An issue as GitHub answers it (`body`, `created_at`, `labels`, `pull_request`); the other fields are ignored.
    /// Absent labels read as none (GitHub always sends the array; this is the adapter's guard against a `null`), and a
    /// `null` or absent body as none. Of the body only its lines that start (trimmed) with [#MARKER_PREFIX] are kept,
    /// so [#MAX_ISSUES] bodies of [#MAX_BODY_CHARS] never sit in the heap together. `/issues` lists open pull requests
    /// too, each with a `pull_request` object: those are never the alarm's.
    public record Issue(int number,
                        String title,
                        Option<String> body,
                        Instant createdAt,
                        List<Label> labels,
                        Option<PullRequestRef> pullRequest) {
        public Issue {
            body = Option.option(body).flatMap(text -> text).map(Issue::markerLines);
            labels = Option.option(labels).map(List::copyOf).or(List.of());
            pullRequest = Option.option(pullRequest).flatMap(ref -> ref);
        }

        public static Issue issue(int number,
                                  String title,
                                  Option<String> body,
                                  Instant createdAt,
                                  List<Label> labels,
                                  Option<PullRequestRef> pullRequest) {
            return new Issue(number, title, body, createdAt, labels, pullRequest);
        }

        private static String markerLines(String body) {
            return body.lines()
                       .map(String::strip)
                       .filter(line -> line.startsWith(MARKER_PREFIX))
                       .collect(Collectors.joining("\n"));
        }

        boolean isIssue() {
            return pullRequest.isEmpty();
        }

        boolean labelled() {
            return labels.stream()
                         .anyMatch(label -> LABEL.equalsIgnoreCase(label.name()));
        }
    }

    /// The `pull_request` object that marks a pull request in the issue list; its content is not read.
    public record PullRequestRef(Option<String> url) {
        public static PullRequestRef pullRequestRef(Option<String> url) {
            return new PullRequestRef(url);
        }
    }

    /// What one paged list keeps and how far it is read: `keep` filters each page, `maxItems` caps the kept items and
    /// `maxPages` the pages read, whichever is reached first failing the read; `maxBytes` caps each page's answer.
    record Listing<T>(Class<T[]> type, Predicate<T> keep, int maxItems, int maxPages, int maxBytes, String what) {
        static <T> Listing<T> listing(Class<T[]> type,
                                      Predicate<T> keep,
                                      int maxItems,
                                      int maxPages,
                                      int maxBytes,
                                      String what) {
            return new Listing<>(type, keep, maxItems, maxPages, maxBytes, what);
        }
    }

    /// One label of an issue.
    public record Label(String name) {
        public static Label label(String name) {
            return new Label(name);
        }
    }

    /// A comment as GitHub answers it: its body (absent if GitHub sends none) and nothing else. Of the body only its
    /// last line (after stripping) is kept, which holds the marker the sink reads, so up to 500 comments of
    /// [#MAX_BODY_CHARS] never sit in the heap together.
    public record Comment(long id, Option<String> body) {
        public Comment {
            body = Option.option(body).flatMap(text -> text).map(Comment::lastLine);
        }

        private static String lastLine(String body) {
            var stripped = body.strip();

            return stripped.substring(stripped.lastIndexOf('\n') + 1);
        }

        public static Comment comment(long id, Option<String> body) {
            return new Comment(id, body);
        }
    }

    /// An answer, read within its cap, with its `Link: rel="next"` target if any.
    record Reply(int status, String body, Option<String> next) {
        static Reply reply(int status, String body, Option<String> next) {
            return new Reply(status, body, next);
        }
    }

    record NewIssue(String title, String body, List<String> labels) {
        static NewIssue newIssue(String title, String body, List<String> labels) {
            return new NewIssue(title, body, labels);
        }
    }

    record NewComment(String body) {
        static NewComment newComment(String body) {
            return new NewComment(body);
        }
    }

    record StateChange(String state) {
        static StateChange stateChange(String state) {
            return new StateChange(state);
        }
    }

    public static GithubApi githubApi(HttpClient client, URI base, String repo, String token) {
        return new GithubApi(client, base, repo, token, githubJson());
    }

    /// GitHub's snake_case names; unknown fields are ignored (the Jackson 3 default), since GitHub answers many.
    static JsonMapper githubJson() {
        return JsonMapper.jsonMapper()
                         .withPragmaticaTypes()
                         .configure(builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
                         .build();
    }

    /// `GET /repos/{repo}/issues?state=open&per_page=20`: every open issue, labelled or not, pull requests left out,
    /// following `Link: rel="next"`. The sink finds its issue by the marker line in its body, so an issue whose label
    /// was dropped is still found and never duplicated. More than [#MAX_ISSUES] issues, or [#MAX_ISSUE_PAGES] pages,
    /// fails the read (exit 2) rather than let a partial list open a second issue for a code; so does a next link
    /// outside `base`, which the token would otherwise follow.
    public Promise<List<Issue>> openIssues() {
        var path = repoPath() + "/issues";

        return target("GET " + path, base + path + "?state=open&per_page=" + ISSUES_PER_PAGE).async()
                     .flatMap(uri -> page(path,
                                          uri,
                                          Listing.listing(Issue[].class,
                                                          Issue::isIssue,
                                                          MAX_ISSUES,
                                                          MAX_ISSUE_PAGES,
                                                          MAX_ISSUE_PAGE_BODY,
                                                          "open issues"),
                                          List.of(),
                                          MAX_ISSUE_PAGES));
    }

    /// `POST /repos/{repo}/issues` with the label: `201` the new issue, which must carry the label (GitHub compares
    /// label names case-insensitively, and so does this). GitHub drops the labels silently when the token may not set
    /// them; such an issue is still found again by its body's marker line, but the token is wrong, so it fails the
    /// delivery (exit 2) with a message naming the issue.
    public Promise<Issue> open(String title, String body) {
        var path = repoPath() + "/issues";

        return written(NewIssue.newIssue(title,
                                         body,
                                         List.of(LABEL)),
                       "POST " + path).async()
                      .flatMap(json -> sent("POST",
                                            path,
                                            "",
                                            some(json)))
                      .flatMap(reply -> createdLabelled(path, reply).async());
    }

    /// `GET /repos/{repo}/issues/{number}/comments?since=&per_page=10`: every comment created or edited at or after
    /// `since`, oldest first (GitHub's order), across at most [#MAX_PAGES] pages, so the newest is the last.
    public Promise<List<Comment>> commentsSince(int number, Instant since) {
        var path = issuePath(number) + "/comments";

        return target("GET " + path,
                      base + path
                     + "?per_page=" + COMMENTS_PER_PAGE
                     + "&since=" + URLEncoder.encode(since.toString(),
                                                     StandardCharsets.UTF_8)).async()
                     .flatMap(uri -> page(path,
                                          uri,
                                          Listing.listing(Comment[].class,
                                                          _ -> true,
                                                          MAX_PAGES * COMMENTS_PER_PAGE,
                                                          MAX_PAGES,
                                                          MAX_COMMENT_PAGE_BODY,
                                                          "comments"),
                                          List.of(),
                                          MAX_PAGES));
    }

    /// `POST /repos/{repo}/issues/{number}/comments`: `201`.
    public Promise<Unit> comment(int number, String body) {
        var path = issuePath(number) + "/comments";

        return written(NewComment.newComment(body),
                       "POST " + path).async()
                      .flatMap(json -> sent("POST",
                                            path,
                                            "",
                                            some(json)))
                      .flatMap(reply -> unitOf(expected("POST", path, CREATED, reply)).async());
    }

    /// `PATCH /repos/{repo}/issues/{number}` with `{"state":"closed"}`: `200`.
    public Promise<Unit> close(int number) {
        var path = issuePath(number);

        return written(StateChange.stateChange("closed"),
                       "PATCH " + path).async()
                      .flatMap(json -> sent("PATCH",
                                            path,
                                            "",
                                            some(json)))
                      .flatMap(reply -> unitOf(expected("PATCH", path, OK, reply)).async());
    }

    /// Everything but the token.
    @Override
    public String toString() {
        return "GithubApi[base=" + base + ", repo=" + repo + "]";
    }

    // ---- pages of the issue list -----------------------------------------------------------------------------------
    /// Iteration over the `Link: rel="next"` pages of one list.
    private <T> Promise<List<T>> page(String path, URI uri, Listing<T> listing, List<T> found, int pagesLeft) {
        return exchange("GET", path, uri, none(), listing.maxBytes()).flatMap(reply -> nextPage(path,
                                                                                                reply,
                                                                                                listing,
                                                                                                found,
                                                                                                pagesLeft));
    }

    private <T> Promise<List<T>> nextPage(String path, Reply reply, Listing<T> listing, List<T> found, int pagesLeft) {
        return listed(path,
                      reply,
                      listing.type()).flatMap(items -> capped(path,
                                                              listing,
                                                              Stream.concat(found.stream(),
                                                                            items.stream().filter(listing.keep()))
                                                                    .toList()))
                     .async()
                     .flatMap(kept -> followed(path,
                                               reply.next(),
                                               listing,
                                               kept,
                                               pagesLeft));
    }

    private static <T> Result<List<T>> capped(String path, Listing<T> listing, List<T> kept) {
        return kept.size() > listing.maxItems()
               ? CliError.SinkFailed.FACTORY.apply("GET " + path,
                                                   cause("more than " + listing.maxItems() + " " + listing.what()))
                                            .result()
               : success(kept);
    }

    private <T> Promise<List<T>> followed(String path,
                                          Option<String> next,
                                          Listing<T> listing,
                                          List<T> found,
                                          int pagesLeft) {
        return next.map(link -> further(path, link, listing, found, pagesLeft))
                   .or(() -> Promise.success(found));
    }

    private <T> Promise<List<T>> further(String path, String link, Listing<T> listing, List<T> found, int pagesLeft) {
        return pagesLeft > 1
               ? nextTarget(path, link).async()
                           .flatMap(uri -> page(path, uri, listing, found, pagesLeft - 1))
               : CliError.SinkFailed.FACTORY.apply("GET " + path,
                                                   cause("more than " + listing.maxPages()
                                                        + " pages of " + listing.what()))
                                            .promise();
    }

    private Result<URI> nextTarget(String path, String link) {
        return link.startsWith(base + "/")
               ? target("GET " + path, link)
               : CliError.SinkFailed.FACTORY.apply("GET " + path, cause("next page outside the API base")).result();
    }

    // ---- one exchange ----------------------------------------------------------------------------------------------
    private String repoPath() {
        return "/repos/" + repo;
    }

    private String issuePath(int number) {
        return repoPath() + "/issues/" + number;
    }

    private static Result<URI> target(String request, String uri) {
        return Network.parseURI(uri).mapError(cause -> CliError.SinkFailed.FACTORY.apply(request, cause));
    }

    private Promise<Reply> sent(String method, String path, String query, Option<String> body) {
        return target(method + " " + path, base + path + query).async()
                     .flatMap(uri -> exchange(method, path, uri, body, MAX_BODY));
    }

    private Promise<Reply> exchange(String method, String path, URI uri, Option<String> body, int cap) {
        var request = method + " " + path;

        return Promise.lift(failed(request),
                            () -> client.send(httpRequest(uri, method, body),
                                              BodyHandlers.ofInputStream()))
                      .flatMap(response -> bounded(request, response, cap));
    }

    /// Reads at most one byte past `cap`, closes the stream (the rest is never read), then judges the size.
    private static Promise<Reply> bounded(String request, HttpResponse<InputStream> response, int cap) {
        return Promise.lift(failed(request),
                            () -> response.body()
                                          .readNBytes(cap + 1))
                      .flatMap(bytes -> closedAfter(request, response, bytes))
                      .flatMap(bytes -> reply(request, response, bytes, cap).async());
    }

    private static Promise<byte[]> closedAfter(String request, HttpResponse<InputStream> response, byte[] bytes) {
        return Promise.lift(failed(request),
                            () -> response.body()
                                          .close())
                      .map(_ -> bytes);
    }

    private static Result<Reply> reply(String request, HttpResponse<InputStream> response, byte[] bytes, int cap) {
        return bytes.length > cap
               ? CliError.SinkFailed.FACTORY.apply(request, cause("answer body over " + cap + " bytes")).result()
               : success(Reply.reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), next(response)));
    }

    private static Option<String> next(HttpResponse<InputStream> response) {
        return Option.from(response.headers().firstValue("Link"))
                     .map(NEXT::matcher)
                     .filter(Matcher::find)
                     .map(matcher -> matcher.group(1));
    }

    private static Fn1<Cause, Throwable> failed(String request) {
        return thrown -> CliError.SinkFailed.FACTORY.apply(request, Causes.fromThrowable(thrown));
    }

    private HttpRequest httpRequest(URI uri, String method, Option<String> body) {
        return headed(HttpRequest.newBuilder(uri).timeout(TIMEOUT)).method(method,
                                                                           body.map(BodyPublishers::ofString)
                                                                               .or(BodyPublishers::noBody))
                     .build();
    }

    private HttpRequest.Builder headed(HttpRequest.Builder builder) {
        return builder.header("Authorization", "Bearer " + token)
                      .header("Accept", "application/vnd.github+json")
                      .header("X-GitHub-Api-Version", "2022-11-28")
                      .header("User-Agent", "temper-alarm")
                      .header("Content-Type", "application/json");
    }

    // ---- decoding --------------------------------------------------------------------------------------------------
    private <T> Result<List<T>> listed(String path, Reply reply, Class<T[]> type) {
        return decoded(expected("GET", path, OK, reply), "GET " + path, type).map(GithubApi::listOf);
    }

    private Result<Issue> created(String path, Reply reply) {
        return decoded(expected("POST", path, CREATED, reply), "POST " + path, Issue.class);
    }

    private Result<Issue> createdLabelled(String path, Reply reply) {
        return created(path, reply).flatMap(issue -> labelled(path, issue));
    }

    private static Result<Issue> labelled(String path, Issue issue) {
        return issue.labelled()
               ? success(issue)
               : CliError.SinkFailed.FACTORY.apply("POST " + path,
                                                   cause("issue #" + issue.number()
                                                        + " was opened without the " + LABEL
                                                        + " label (the token may not set labels); the next checks find it by"
                                                        + " its marker line, but grant the token label rights"))
                                            .result();
    }

    private static <T> List<T> listOf(T[] items) {
        return List.of(items);
    }

    private static Result<Unit> unitOf(Result<String> body) {
        return body.mapToUnit();
    }

    private static Result<String> expected(String method, String path, int status, Reply reply) {
        return reply.status() == status
               ? success(reply.body())
               : CliError.SinkRejected.FACTORY.apply(reply.status(),
                                                     method + " " + path)
                                              .result();
    }

    private <T> Result<T> decoded(Result<String> body, String request, Class<T> type) {
        return body.flatMap(text -> json.readString(text, type)
                                        .mapError(cause -> CliError.SinkFailed.FACTORY.apply(request, cause)));
    }

    private Result<String> written(Object value, String request) {
        return json.writeAsString(value)
                   .mapError(cause -> CliError.SinkFailed.FACTORY.apply(request, cause));
    }
}
