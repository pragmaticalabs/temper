package org.pragmatica.kiln.service.http;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;


/// One route: a method, an exact path (kiln's routes have no path parameters) and its handler (kiln-f1-design §4).
public record Route(String method, String path, Fn1<Promise<ApiResponse>, ApiRequest> handler) {
    public static Route get(String path, Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return new Route("GET", path, handler);
    }

    public static Route post(String path, Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return new Route("POST", path, handler);
    }

    public boolean matches(ApiRequest request) {
        return method.equals(request.method()) && path.equals(request.path());
    }
}
