package org.pragmatica.temper.controller.http;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// A request as the routes see it: the method, the path below `/api/v1` split into segments, the path parameters
/// bound by the matched route, the query, the headers and the body. Built by [HttpGlue] from rc4's `HttpRequest`.
///
/// The body array is exposed as received, not copied; routes must not mutate it.
public record ApiRequest(HttpMethod method,
                         List<String> segments,
                         Map<String, String> params,
                         QueryParams query,
                         Headers headers,
                         byte[] body) {
    public static final String PREFIX = "/api/v1";

    public ApiRequest {
        segments = List.copyOf(segments);
        params = Map.copyOf(params);
    }

    public static ApiRequest apiRequest(HttpMethod method,
                                        List<String> segments,
                                        Map<String, String> params,
                                        QueryParams query,
                                        Headers headers,
                                        byte[] body) {
        return new ApiRequest(method, segments, params, query, headers, body);
    }

    /// Splits `request.path()`; a path outside [#PREFIX] gets segments that no route matches.
    public static ApiRequest apiRequest(HttpRequest request) {
        return new ApiRequest(request.method(),
                              segments(request.path()),
                              Map.of(),
                              request.queryParams(),
                              request.headers(),
                              request.body());
    }

    /// The same request with the path parameters a route bound.
    public ApiRequest withParams(Map<String, String> bound) {
        return new ApiRequest(method, segments, bound, query, headers, body);
    }

    /// The path as received, without the query, rebuilt from the segments (for the log).
    public String path() {
        return segments.size() == 2 && segments.getFirst()
                                               .isEmpty()
               ? segments.getLast()
               : PREFIX + "/" + String.join("/", segments);
    }

    public Option<String> param(String name) {
        return option(params.get(name));
    }

    public Option<String> queryParam(String name) {
        return query.get(name)
                    .filter(value -> !value.isBlank());
    }

    private static List<String> segments(String path) {
        return path.startsWith(PREFIX + "/")
               ? Arrays.stream(path.substring(PREFIX.length() + 1).split("/"))
                       .filter(segment -> !segment.isEmpty())
                       .toList()
               : List.of("", path);
    }
}
