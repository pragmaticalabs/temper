package org.pragmatica.kiln.service.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


/// kiln's HTTP server (kiln-f1-design §4, Q-1): the JDK's `HttpServer` bound to [InetAddress#getLoopbackAddress], never
/// the wildcard address (rc4's `http-server` cannot bind one address; pragmatica #2180), with one virtual thread per
/// exchange. Without `[server] bind`, only a process that can connect to the loopback address reaches it (§8 "Server
/// reachability"); pinned by `LoopbackOnlyIT`. With `bind` (owner 2026-11-05) a second `HttpServer` listens on that one
/// IPv4 address and the same port, for reads from the LAN; the loopback listener stays, so an `ssh -L` tunnel keeps
/// working. Pinned by `LanBindIT`.
///
/// Before any route sees a request (WP9 verification rulings):
/// - a `Host` header whose host name (any port: an `ssh -L` tunnel has its own) is not `127.0.0.1`, `localhost`, the bind
///   address or a configured `hostNames` entry, or no `Host` at all, is refused 421, so a page whose DNS name was rebound
///   can neither read nor write;
/// - a request whose method is not GET is refused 403 unless the connection's own remote address is loopback (the socket,
///   never a header such as `X-Forwarded-For`): the LAN reads, only this machine writes;
/// - a body over [#MAX_BODY] is refused 413, one not read within [#READ] 408 (a client cannot hold an exchange open),
///   and one that is not UTF-8 400.
/// A route that has not answered in [#ANSWER] gets 503; every answer carries `X-Content-Type-Options: nosniff` and
/// `Cache-Control: no-store`; the exchange always ends.
public final class LoopbackServer {
    /// Request bodies are capped at 8 MiB (§4 [guess]), as `kiln-ingest` caps the Mac push.
    public static final int MAX_BODY = 8 * 1024 * 1024;
    /// The longest a route may take to answer.
    public static final Duration ANSWER = Duration.ofSeconds(60);
    /// The longest a request body may take to arrive.
    public static final Duration READ = Duration.ofSeconds(30);
    /// The host names a request may name in `Host`: the loopback address's, as a browser or a tunnel spells it.
    static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost");

    /// The JDK server's own limits (read once, when its first server is created): an unread body is never drained, so
    /// the connection of a client that never sends its body is closed right after its 408 instead of holding a socket and
    /// a thread until the client leaves; and a request still open [#READ] plus 5 s after it began is closed, a backstop
    /// set past [#READ] so the 408 is always written first (at exactly [#READ] the two raced and the client could see the
    /// connection close with no answer). An operator's `-D` wins.
    static final Map<String, String> JDK_LIMITS = Map.of("sun.net.httpserver.maxReqTime",
                                                         Long.toString(READ.plusSeconds(5).toSeconds()),
                                                         "sun.net.httpserver.drainAmount",
                                                         "0");

    private final List<HttpServer> servers;
    private final ExecutorService executor;

    private LoopbackServer(List<HttpServer> servers, ExecutorService executor) {
        this.servers = servers;
        this.executor = executor;
    }

    /// Binds `port` on the loopback address (0: any free port) and starts serving `router`.
    public static Result<LoopbackServer> start(int port, Router router) {
        return start(port, Option.none(), List.of(), router);
    }

    /// As [#start(int, Router)], and with `bind` (a literal IPv4 address, checked by the config loader) also on that
    /// address and the loopback listener's port. `hostNames` join the accepted `Host` names. The [#JDK_LIMITS] are set
    /// first: in kiln this is the process's only JDK server, so they are in force (a server created earlier in the same
    /// JVM would have fixed the JDK's limits already). If the second listener cannot be bound, the first is stopped and
    /// the start fails.
    public static Result<LoopbackServer> start(int port, Option<String> bind, List<String> hostNames, Router router) {
        JDK_LIMITS.forEach(System.getProperties()::putIfAbsent);
        var hosts = hosts(bind, hostNames);

        return listener(InetAddress.getLoopbackAddress(),
                        port).flatMap(first -> bind.fold(() -> Result.success(List.of(first)),
                                                         address -> second(first, address)))
                       .map(servers -> started(servers, hosts, router));
    }

    private static Result<List<HttpServer>> second(HttpServer first, String address) {
        return Result.lift(Causes::fromThrowable,
                           () -> InetAddress.getByName(address))
                     .flatMap(inet -> listener(inet,
                                               first.getAddress().getPort()))
                     .map(other -> List.of(first, other))
                     .onFailure(_ -> first.stop(0));
    }

    private static Result<HttpServer> listener(InetAddress address, int port) {
        return Result.lift(Causes::fromThrowable, () -> HttpServer.create(new InetSocketAddress(address, port), 0));
    }

    /// The `Host` names a request may carry: [#LOCAL_HOSTS], the bind address and `hostNames`, lower-case.
    static Set<String> hosts(Option<String> bind, List<String> hostNames) {
        return Stream.concat(Stream.concat(LOCAL_HOSTS.stream(),
                                           bind.stream()),
                             hostNames.stream())
                     .map(name -> name.toLowerCase(Locale.ROOT))
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static LoopbackServer started(List<HttpServer> servers, Set<String> hosts, Router router) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        servers.forEach(server -> {
            server.setExecutor(executor);
            server.createContext("/", exchange -> exchanged(exchange, router, hosts));
            server.start();
        });

        return new LoopbackServer(servers, executor);
    }

    /// The loopback listener's port (also the bind address's).
    public int port() {
        return address().getPort();
    }

    /// The loopback listener's address.
    public InetSocketAddress address() {
        return servers.getFirst()
                      .getAddress();
    }

    /// Stops accepting, gives exchanges in flight a second to finish, then stops the executor.
    public Promise<Unit> stop() {
        servers.forEach(server -> server.stop(1));
        executor.shutdown();

        return Promise.unitPromise();
    }

    /// One exchange: read (capped), route, answer, close. FER: a body that cannot be read is answered 400, a route that
    /// does not answer in time 503, and an answer the client went away from is dropped; the exchange is always closed.
    private static void exchanged(HttpExchange exchange, Router router, Set<String> hosts) {
        var response = !named(Option.option(exchange.getRequestHeaders().getFirst("Host")),
                              hosts)
                       ? ApiResponse.failure(421, "misdirected request")
                       : !readOnly(exchange) && !fromLoopback(exchange)
                         ? ApiResponse.failure(403, "writes are accepted from this machine only")
                         : answered(exchange, router);

        respond(exchange, response);
        exchange.close();
    }

    private static ApiResponse answered(HttpExchange exchange, Router router) {
        return body(exchange).fold(read -> read.fold(cause -> Promise.success(unread(cause)),
                                                     body -> routed(exchange, router, body)))
                   .await(TimeSpan.fromDuration(ANSWER))
                   .or(ApiResponse.failure(503, "kiln did not answer in time"));
    }

    /// Whether `Host` names one of `hosts`: its host name, without the port, compared case-insensitively.
    static boolean named(Option<String> host, Set<String> hosts) {
        return host.map(value -> value.replaceFirst(":[0-9]*$", "")
                                      .toLowerCase(Locale.ROOT))
                   .filter(hosts::contains)
                   .isPresent();
    }

    private static boolean readOnly(HttpExchange exchange) {
        return exchange.getRequestMethod()
                       .equals("GET");
    }

    /// The socket's remote address, never a header: a LAN client cannot claim to be this machine.
    private static boolean fromLoopback(HttpExchange exchange) {
        return exchange.getRemoteAddress()
                       .getAddress()
                       .isLoopbackAddress();
    }

    private static ApiResponse unread(Cause cause) {
        return cause instanceof CoreError.Timeout
               ? ApiResponse.failure(408, "the body did not arrive in time")
               : ApiResponse.failure(400, "the body could not be read");
    }

    private static Promise<ApiResponse> routed(HttpExchange exchange, Router router, byte[] body) {
        return body.length > MAX_BODY
               ? Promise.success(ApiResponse.failure(413, "the body is over 8 MiB"))
               : utf8(body).fold(_ -> Promise.success(ApiResponse.failure(400, "the body is not UTF-8")),
                                 text -> router.handle(request(exchange, text)));
    }

    /// Strict: a malformed or unmappable sequence fails, never becomes U+FFFD.
    static Result<String> utf8(byte[] body) {
        return Result.lift(Causes::fromThrowable,
                           () -> StandardCharsets.UTF_8.newDecoder()
                                                       .decode(ByteBuffer.wrap(body))
                                                       .toString());
    }

    /// At most one byte past the cap is read, so an endless body costs 8 MiB, never more; a body that has not arrived
    /// within [#READ] fails as a timeout (the read runs on its own virtual thread, ended when the exchange closes).
    private static Promise<byte[]> body(HttpExchange exchange) {
        var read = Promise.<byte[]> promise();

        Thread.ofVirtual().start(() -> read.resolve(Result.lift(Causes::fromThrowable,
                                                                () -> exchange.getRequestBody()
                                                                              .readNBytes(MAX_BODY + 1))));

        return read.timeout(TimeSpan.fromDuration(READ));
    }

    static ApiRequest request(HttpExchange exchange, String body) {
        var uri = exchange.getRequestURI();

        return ApiRequest.apiRequest(exchange.getRequestMethod(), uri.getPath(), query(uri), headers(exchange), body);
    }

    /// The first value of each request header, by its lower-case name.
    private static Map<String, String> headers(HttpExchange exchange) {
        return exchange.getRequestHeaders()
                       .entrySet()
                       .stream()
                       .filter(entry -> !entry.getValue()
                                              .isEmpty())
                       .collect(Collectors.toMap(entry -> entry.getKey()
                                                               .toLowerCase(Locale.ROOT),
                                                 entry -> entry.getValue()
                                                               .getFirst(),
                                                 (first, _) -> first));
    }

    /// The query's parameters, each decoded; the first of a repeated name wins.
    static Map<String, String> query(URI uri) {
        return Option.option(uri.getRawQuery())
                     .map(LoopbackServer::pairs)
                     .or(Map.of());
    }

    private static Map<String, String> pairs(String raw) {
        return Arrays.stream(raw.split("&"))
                     .filter(pair -> !pair.isEmpty())
                     .collect(Collectors.toMap(pair -> decoded(before(pair)),
                                               pair -> decoded(after(pair)),
                                               (first, _) -> first,
                                               LinkedHashMap::new));
    }

    private static String before(String pair) {
        var equals = pair.indexOf('=');

        return equals < 0
               ? pair
               : pair.substring(0, equals);
    }

    private static String after(String pair) {
        var equals = pair.indexOf('=');

        return equals < 0
               ? ""
               : pair.substring(equals + 1);
    }

    /// The JDK has already refused a request whose URI holds a malformed escape, so decoding cannot fail here.
    private static String decoded(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    /// The answer, or nothing when the client went away mid-answer: nothing is left to answer then. The answer's stream is
    /// closed here, which flushes it before the JDK drains an unread body: a client that never sends its body still gets
    /// its 408.
    private static Result<Unit> respond(HttpExchange exchange, ApiResponse response) {
        var bytes = response.body().getBytes(StandardCharsets.UTF_8);
        var length = bytes.length == 0
                     ? -1
                     : bytes.length;
        var headers = exchange.getResponseHeaders();

        headers.set("Content-Type", response.contentType());
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");

        return Result.lift(Causes::fromThrowable,
                           () -> {
                               exchange.sendResponseHeaders(response.status(), length);
                               try (var out = exchange.getResponseBody()) {
                               out.write(bytes);
                           }
                           });
    }
}
