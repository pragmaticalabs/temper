package org.pragmatica.kiln.service.http;

import java.util.Locale;
import java.util.Map;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// One request as a route sees it: the method, the path, the query parameters (the first value of each, decoded), the
/// headers (the first value of each, by lower-case name) and the body, already capped at [LoopbackServer#MAX_BODY].
public record ApiRequest(String method,
                         String path,
                         Map<String, String> query,
                         Map<String, String> headers,
                         String body) {
    public ApiRequest {
        query = Map.copyOf(query);
        headers = Map.copyOf(headers);
    }

    public static ApiRequest apiRequest(String method,
                                        String path,
                                        Map<String, String> query,
                                        Map<String, String> headers,
                                        String body) {
        return new ApiRequest(method, path, query, headers, body);
    }

    /// A request without headers.
    public static ApiRequest apiRequest(String method, String path, Map<String, String> query, String body) {
        return apiRequest(method, path, query, Map.of(), body);
    }

    /// A header by its name in any case.
    public Option<String> header(String name) {
        return option(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    /// A query parameter; a blank one counts as absent.
    public Option<String> param(String name) {
        return option(query.get(name)).filter(value -> !value.isBlank());
    }
}
