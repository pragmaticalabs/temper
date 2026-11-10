package org.pragmatica.temper.controller.it;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


/// A GitHub for the review path: the App's mint, `GET pulls/{n}`, `GET pulls/{n}/reviews`
/// and `POST pulls/{n}/reviews`. It records every request (path, `Authorization`, body) and answers from per-test state.
final class ReviewGitHubStub implements AutoCloseable {
    record Seen(String method, String path, String authorization, String body) {}

    record Reply(int status, String body, long delayMillis) {
        static Reply status(int status, String message) {
            return new Reply(status, "{\"message\":\"" + message + "\"}", 0);
        }
    }

    static final String APP_TOKEN = "app-read-token";
    private static final Pattern PULL = Pattern.compile("^/repos/o/r/pulls/(\\d+)(/reviews)?$");

    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<String> reviews = new CopyOnWriteArrayList<>();
    private final Map<Long, String> heads = new ConcurrentHashMap<>();
    private final Map<Long, Long> postDelays = new ConcurrentHashMap<>();
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger ids = new AtomicInteger(1000);
    private final String reviewer;
    private volatile String state = "open";
    private volatile String baseRef = "main";
    private volatile IntFunction<Reply> postScript = _ -> null;
    private volatile CountDownLatch postArrived = new CountDownLatch(1);

    ReviewGitHubStub(String reviewer) throws IOException {
        this.reviewer = reviewer;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::answer);
        server.start();
    }

    URI api() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    ReviewGitHubStub head(long pr, String sha) {
        heads.put(pr, sha);

        return this;
    }

    ReviewGitHubStub base(String ref) {
        baseRef = ref;

        return this;
    }

    ReviewGitHubStub pullState(String value) {
        state = value;

        return this;
    }

    /// The reply of the n-th POST (1-based), or null for the stub's own 200.
    ReviewGitHubStub onPost(IntFunction<Reply> script) {
        postScript = script;

        return this;
    }

    ReviewGitHubStub delayPosts(long pr, long millis) {
        postDelays.put(pr, millis);

        return this;
    }

    /// A review already on the PR, as GitHub would list it.
    ReviewGitHubStub review(long id, String login, String stateName, String commit) {
        reviews.add("{\"id\":" + id
                   + ",\"user\":{\"login\":\"" + login
                   + "\"},\"state\":\"" + stateName
                   + "\",\"commit_id\":\"" + commit
                   + "\"}");

        return this;
    }

    /// GitHub turns review `id` DISMISSED (the stale rule, or a dismissal).
    ReviewGitHubStub dismiss(long id) {
        for (var i = 0; i < reviews.size(); i++) {
            if (reviews.get(i).startsWith("{\"id\":" + id + ",")) {
                reviews.set(i,
                            reviews.get(i).replaceFirst("\"state\":\"[A-Z_]+\"", "\"state\":\"DISMISSED\""));
            }
        }

        return this;
    }

    CountDownLatch postArrived() {
        return postArrived;
    }

    int posts() {
        return posts.get();
    }

    List<Seen> seen() {
        return List.copyOf(seen);
    }

    List<Seen> seen(String method, String pathSuffix) {
        return seen.stream()
                   .filter(s -> s.method()
                                 .equals(method) && s.path()
                                                     .endsWith(pathSuffix))
                   .toList();
    }

    private void answer(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(),
                              StandardCharsets.UTF_8);
        var path = exchange.getRequestURI().getPath();
        var method = exchange.getRequestMethod();
        var auth = exchange.getRequestHeaders().getFirst("Authorization");

        seen.add(new Seen(method, path, auth, body));
        var reply = route(method, path, body);

        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        var bytes = reply.body().getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("x-ratelimit-remaining", "4321");
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
        // the client gave up (a timed-out POST)
        } finally {
            exchange.close();
        }
    }

    private Reply route(String method, String path, String body) {
        if (method.equals("POST") && path.endsWith("/access_tokens")) {
            var expires = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

            return new Reply(201,
                             "{\"token\":\"" + APP_TOKEN
                            + "\",\"expires_at\":\"" + expires
                            + "\",\"permissions\":{\"pull_requests\":\"read\"}}",
                             0);
        }

        var matcher = PULL.matcher(path);

        if (!matcher.matches()) {
            return Reply.status(404, "not found");
        }

        var pr = Long.parseLong(matcher.group(1));

        if (matcher.group(2) == null) {
            return new Reply(200,
                             "{\"state\":\"" + state
                            + "\",\"head\":{\"sha\":\"" + heads.getOrDefault(pr, "0".repeat(40))
                            + "\"},\"base\":{\"ref\":\"" + baseRef
                            + "\",\"sha\":\""
                            + "9".repeat(40)
                            + "\"}}",
                             0);
        }

        if (method.equals("GET")) {
            return new Reply(200, "[" + String.join(",", new ArrayList<>(reviews)) + "]", 0);
        }

        return posted(pr, body);
    }

    private Reply posted(long pr, String body) {
        var n = posts.incrementAndGet();

        postArrived.countDown();
        var scripted = postScript.apply(n);

        if (scripted != null) {
            return scripted;
        }

        var id = ids.incrementAndGet();
        var commit = field(body, "commit_id");
        var event = field(body, "event");

        reviews.add("{\"id\":" + id
                   + ",\"user\":{\"login\":\"" + reviewer
                   + "\"},\"state\":\"" + (event.equals("APPROVE")
                                           ? "APPROVED"
                                           : "CHANGES_REQUESTED")
                   + "\",\"commit_id\":\"" + commit
                   + "\"}");

        return new Reply(200, "{\"id\":" + id + "}", postDelays.getOrDefault(pr, 0L));
    }

    private static String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);

        return matcher.find()
               ? matcher.group(1)
               : "";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
