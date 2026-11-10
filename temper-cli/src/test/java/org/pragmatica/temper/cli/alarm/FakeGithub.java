package org.pragmatica.temper.cli.alarm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/// An in-process stand-in for the GitHub issues REST API (JDK `HttpServer` on a loopback port): just the five calls
/// the `github-issue` sink makes, over in-memory issues, each request recorded with its `Authorization` header. A
/// request without `Bearer <token>` is `401 Bad credentials`, as GitHub answers; [#failWith] answers every request
/// with one status.
public final class FakeGithub implements AutoCloseable {
    private static final Pattern ISSUES = Pattern.compile("^/repos/([^/]+/[^/]+)/issues$");
    private static final Pattern ISSUE = Pattern.compile("^/repos/([^/]+/[^/]+)/issues/(\\d+)$");
    private static final Pattern COMMENTS = Pattern.compile("^/repos/([^/]+/[^/]+)/issues/(\\d+)/comments$");
    private static final Pattern TITLE = Pattern.compile("\"title\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern STATE = Pattern.compile("\"state\":\"([a-z]+)\"");
    private static final Pattern SINCE = Pattern.compile("(?:^|&)since=([^&]+)");
    private static final Pattern PER_PAGE = Pattern.compile("(?:^|&)per_page=(\\d+)");
    private static final Pattern PAGE = Pattern.compile("(?:^|&)page=(\\d+)");
    private static final Pattern BODY = Pattern.compile("\"body\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ALARM_TITLE = Pattern.compile("^temper alarm: ([a-z-]+) \\(([^)]+)\\)$");

    public record Received(String method, String target, String authorization, String body) {}

    public record Comment(long id, String body, Instant createdAt) {}

    public static final class Issue {
        final int number;
        public final String title;
        public final String body;
        final List<String> labels;
        final Instant createdAt;
        public final List<Comment> comments = new CopyOnWriteArrayList<>();
        public volatile String state = "open";
        public volatile boolean pullRequest = false;

        Issue(int number, String title, String body, List<String> labels, Instant createdAt) {
            this.number = number;
            this.title = title;
            this.body = body;
            this.labels = labels;
            this.createdAt = createdAt;
        }

        public int number() {
            return number;
        }
    }

    private final HttpServer server;
    private final String token;
    private final String repo;
    private final Map<Integer, Issue> issues = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger numbers = new AtomicInteger(10);
    private final AtomicLong commentIds = new AtomicLong(100);
    volatile Instant now = Instant.parse("2026-10-13T10:00:00Z");
    private volatile int failStatus = 0;
    private volatile int brokenIssue = 0;
    private volatile int brokenClose = 0;
    private volatile int padBytes = 0;
    private volatile int pageSize = 100;
    private volatile String listBody = null;
    private volatile String nextLink = null;
    private volatile boolean dropLabels = false;
    private volatile long delayMs = 0;
    private volatile int delayAfter = 0;
    private volatile String labelAs = GithubApi.LABEL;
    private final AtomicInteger requests = new AtomicInteger();

    private FakeGithub(String token, String repo) throws IOException {
        this.token = token;
        this.repo = repo;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public static FakeGithub start(String token, String repo) throws IOException {
        return new FakeGithub(token, repo);
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /// Every request answers `status` from now on.
    public FakeGithub failWith(int status) {
        failStatus = status;
        return this;
    }

    /// Every write to issue `number` (comment, close) answers 500.
    FakeGithub breakIssue(int number) {
        brokenIssue = number;
        return this;
    }

    /// Only the close of issue `number` answers 500 (its comments still work).
    FakeGithub breakClose(int number) {
        brokenClose = number;
        return this;
    }

    /// Every listed issue and comment carries `bytes` more of a field the sink ignores, as GitHub's metadata would.
    FakeGithub padItems(int bytes) {
        padBytes = bytes;
        return this;
    }

    /// The open-issue list answers at most this many per page (fewer when `per_page` asks for fewer), with a `Link: rel="next"` header while more remain.
    FakeGithub pageSize(int size) {
        pageSize = size;
        return this;
    }

    /// The open-issue list answers `body` as it is (200), whatever the issues.
    FakeGithub listAnswers(String body) {
        listBody = body;
        return this;
    }

    /// The first page of the open-issue list names `link` as its `rel="next"`, whatever the issues.
    FakeGithub nextLink(String link) {
        nextLink = link;
        return this;
    }

    /// New issues are created without labels, as GitHub does for a token that may not set them.
    FakeGithub dropLabels() {
        dropLabels = true;
        return this;
    }

    /// Every answer waits this long first.
    public FakeGithub delay(long millis) {
        delayMs = millis;
        return this;
    }

    /// Every answer after the first `count` requests waits `millis` first.
    FakeGithub delayAfter(int count, long millis) {
        delayAfter = count;
        delayMs = millis;
        return this;
    }

    /// New issues carry the label under this spelling.
    FakeGithub labelAs(String name) {
        labelAs = name;
        return this;
    }

    /// An open pull request, which GitHub lists among the issues.
    public Issue pullRequest(String title, Instant createdAt) {
        var issue = issue(title, createdAt);
        issue.pullRequest = true;
        return issue;
    }

    /// An existing issue (open, labelled `temper-alarm` unless `labels` says otherwise) whose body ends with the marker
    /// line the sink writes for the code and host its title names (none when the title is not an alarm title).
    public Issue issue(String title, Instant createdAt, String... labels) {
        var alarm = ALARM_TITLE.matcher(title);
        var body = alarm.matches() ? "Firing since x\n\n<!-- temper-alarm:" + alarm.group(1) + ":" + alarm.group(2) + " -->" : null;
        return issueWithBody(title, body, createdAt, labels);
    }

    /// An existing issue with exactly this body (`null`: none).
    public Issue issueWithBody(String title, String body, Instant createdAt, String... labels) {
        var issue = new Issue(numbers.incrementAndGet(), title, body, labels.length == 0 ? List.of(GithubApi.LABEL) : List.of(labels),
                              createdAt);
        issues.put(issue.number, issue);
        return issue;
    }

    Comment comment(Issue issue, String body, Instant at) {
        var comment = new Comment(commentIds.incrementAndGet(), body, at);
        issue.comments.add(comment);
        return comment;
    }

    public List<Issue> issues() {
        return issues.values().stream().sorted((a, b) -> Integer.compare(a.number, b.number)).toList();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    /// The mutating requests (`POST`, `PATCH`) as `METHOD path`.
    List<String> writes() {
        return received.stream()
                       .filter(request -> !request.method().equals("GET"))
                       .map(request -> request.method() + " " + request.target())
                       .toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            var method = exchange.getRequestMethod();
            var path = exchange.getRequestURI().getPath();
            var query = exchange.getRequestURI().getRawQuery();
            received.add(new Received(method, path + (query == null ? "" : "?" + query), authorization, body));

            if (delayMs > 0 && requests.incrementAndGet() > delayAfter) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failStatus != 0) {
                reply(exchange, failStatus, "{\"message\":\"failing on purpose\"}");
            } else if (!("Bearer " + token).equals(authorization)) {
                reply(exchange, 401, "{\"message\":\"Bad credentials\"}");
            } else {
                route(exchange, method, path, query == null ? "" : query, body);
            }
        }
    }

    private void route(HttpExchange exchange, String method, String path, String query, String body) throws IOException {
        var list = ISSUES.matcher(path);
        var one = ISSUE.matcher(path);
        var comments = COMMENTS.matcher(path);

        if (list.matches() && repo.equals(list.group(1)) && method.equals("GET")) {
            listPage(exchange, path, query);
        } else if (list.matches() && repo.equals(list.group(1)) && method.equals("POST")) {
            var issue = new Issue(numbers.incrementAndGet(), unescaped(group(TITLE, body)), unescaped(group(BODY, body)),
                                  dropLabels ? List.of() : List.of(labelAs), now);
            issues.put(issue.number, issue);
            reply(exchange, 201, json(issue));
        } else if (method.equals("PATCH") && one.matches() && number(one) == brokenClose) {
            reply(exchange, 500, "{\"message\":\"Server Error\"}");
        } else if (!method.equals("GET") && (comments.matches() && number(comments) == brokenIssue
                                             || one.matches() && number(one) == brokenIssue)) {
            reply(exchange, 500, "{\"message\":\"Server Error\"}");
        } else if (comments.matches() && repo.equals(comments.group(1)) && issues.containsKey(number(comments))) {
            commentRoute(exchange, method, issues.get(number(comments)), query, body);
        } else if (one.matches() && repo.equals(one.group(1)) && method.equals("PATCH") && issues.containsKey(number(one))) {
            var issue = issues.get(number(one));
            issue.state = group(STATE, body);
            reply(exchange, 200, json(issue));
        } else {
            reply(exchange, 404, "{\"message\":\"Not Found\"}");
        }
    }

    private void commentRoute(HttpExchange exchange, String method, Issue issue, String query, String body) throws IOException {
        if (method.equals("POST")) {
            var comment = comment(issue, unescaped(group(BODY, body)), now);
            reply(exchange, 201, "{\"id\":" + comment.id() + ",\"body\":\"…\",\"created_at\":\"" + comment.createdAt() + "\"}");
        } else {
            var since = Instant.parse(URLDecoder.decode(group(SINCE, query), StandardCharsets.UTF_8));
            var recent = issue.comments.stream().filter(comment -> !comment.createdAt().isBefore(since)).toList();
            var perPage = Integer.parseInt(group(PER_PAGE, query));
            var pageText = group(PAGE, query);
            var page = pageText.isEmpty() ? 1 : Integer.parseInt(pageText);
            var from = Math.min((page - 1) * perPage, recent.size());
            var to = Math.min(page * perPage, recent.size());
            if (to < recent.size()) {
                exchange.getResponseHeaders().add("Link", "<" + url() + exchange.getRequestURI().getPath() + "?per_page=" + perPage
                                                          + "&since=" + group(SINCE, query) + "&page=" + (page + 1) + ">; rel=\"next\"");
            }
            reply(exchange, 200, recent.subList(from, to).stream()
                                       .map(comment -> "{\"id\":" + comment.id() + padding() + ",\"user\":{\"login\":\"x\"},\"body\":\""
                                                       + escaped(comment.body()) + "\",\"created_at\":\""
                                                       + comment.createdAt() + "\"}")
                                       .collect(Collectors.joining(",", "[", "]")));
        }
    }

    /// `state=open&labels=temper-alarm`: open issues carrying the label, newest first (GitHub's default order),
    /// `pageSize` per page (`page=`, from 1), with GitHub's `Link: <…>; rel="next"` while more remain.
    private void listPage(HttpExchange exchange, String path, String query) throws IOException {
        if (listBody != null) {
            reply(exchange, 200, listBody);
            return;
        }
        if (nextLink != null && group(PAGE, query).isEmpty()) {
            exchange.getResponseHeaders().add("Link", "<" + nextLink + ">; rel=\"next\"");
        }
        var label = query.contains("labels=" + GithubApi.LABEL) ? GithubApi.LABEL : "";
        var open = issues().reversed().stream()
                           .filter(issue -> issue.state.equals("open"))
                           .filter(issue -> label.isEmpty() || issue.labels.contains(label))
                           .toList();
        var pageText = group(PAGE, query);
        var page = pageText.isEmpty() ? 1 : Integer.parseInt(pageText);
        var asked = group(PER_PAGE, query);
        var perPage = Math.min(pageSize, asked.isEmpty() ? 30 : Integer.parseInt(asked));
        var from = Math.min((page - 1) * perPage, open.size());
        var to = Math.min(page * perPage, open.size());
        if (to < open.size()) {
            exchange.getResponseHeaders().add("Link", "<" + url() + path + "?state=open&labels=" + GithubApi.LABEL
                                                      + "&per_page=" + perPage + "&page=" + (page + 1) + ">; rel=\"next\", <" + url() + path
                                                      + "?page=99>; rel=\"last\"");
        }
        reply(exchange, 200, open.subList(from, to).stream().map(this::listed).collect(Collectors.joining(",", "[", "]")));
    }

    private String listed(Issue issue) {
        return padBytes == 0 ? json(issue) : "{" + padding().substring(1) + "," + json(issue).substring(1);
    }

    /// `,"pad":"x…"` while [#padItems] asks for it: the other fields of a listed item (labels, user, URLs) at their size.
    private String padding() {
        return padBytes == 0 ? "" : ",\"pad\":\"" + "x".repeat(padBytes) + "\"";
    }

    private static String json(Issue issue) {
        return "{\"url\":\"https://api.github.com/x\",\"number\":" + issue.number + ",\"title\":\"" + issue.title.replace("\"", "\\\"")
               + "\",\"body\":" + (issue.body == null ? "null" : "\"" + escaped(issue.body) + "\"")
               + ",\"state\":\"" + issue.state + "\",\"labels\":["
               + issue.labels.stream().map(label -> "{\"name\":\"" + label + "\"}").collect(Collectors.joining(","))
               + "],\"comments\":" + issue.comments.size() + ",\"created_at\":\"" + issue.createdAt + "\",\"pull_request\":"
               + (issue.pullRequest ? "{\"url\":\"https://api.github.com/pulls/" + issue.number + "\"}" : "null") + "}";
    }

    private static int number(Matcher matcher) {
        return Integer.parseInt(matcher.group(2));
    }

    private static String group(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String unescaped(String json) {
        return json.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /// JSON string content: backslash, quote and newline escaped short, every other control or non-ASCII UTF-16 unit as
    /// a six-byte unicode escape (a surrogate pair as two: 12 bytes, the worst case).
    private static String escaped(String text) {
        var out = new StringBuilder(text.length());
        text.chars().forEach(c -> out.append(c == '\\' ? "\\\\" : c == '"' ? "\\\"" : c == '\n' ? "\\n"
                                             : c < 0x20 || c > 0x7e ? "\\u" + Integer.toHexString(c | 0x10000).substring(1) : String.valueOf((char) c)));
        return out.toString();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
