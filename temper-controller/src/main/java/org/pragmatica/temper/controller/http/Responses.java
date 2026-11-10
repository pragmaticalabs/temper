package org.pragmatica.temper.controller.http;

import java.nio.charset.StandardCharsets;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.temper.protocol.api.ErrorResponse;


/// JSON bodies for routes, through the one temper mapper (`TemperJson.mapper()`), and the mapping of failures to
/// `ErrorResponse` bodies: an [ApiError] answers its own status, anything else is a 500.
public record Responses(JsonMapper mapper) {
    private static final ErrorResponse INTERNAL_ERROR = ErrorResponse.errorResponse("internal error");
    private static final byte[] INTERNAL = "{\"error\":\"internal error\"}".getBytes(StandardCharsets.UTF_8);

    public static Responses responses(JsonMapper mapper) {
        return new Responses(mapper);
    }

    /// A plain-text body.
    public static ApiResponse text(HttpStatus status, String text) {
        return ApiResponse.apiResponse(status, CommonContentType.TEXT_PLAIN, text.getBytes(StandardCharsets.UTF_8));
    }

    /// 204 with an empty body.
    public static ApiResponse noContent() {
        return ApiResponse.apiResponse(HttpStatus.NO_CONTENT, CommonContentType.TEXT_PLAIN, new byte[0]);
    }

    private static ApiResponse jsonBytes(HttpStatus status, byte[] json) {
        return ApiResponse.apiResponse(status, CommonContentType.APPLICATION_JSON, json);
    }

    public ApiResponse json(HttpStatus status, Object value) {
        return mapper.writeAsBytes(value)
                     .fold(_ -> jsonBytes(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL),
                           bytes -> jsonBytes(status, bytes));
    }

    public ApiResponse ok(Object value) {
        return json(HttpStatus.OK, value);
    }

    /// An [ApiError] answers its own status and message; anything else is a 500 with a fixed text, because its
    /// message may carry store or protocol internals (the router logs the whole chain).
    public ApiResponse failure(Cause cause) {
        return switch (cause) {
            case ApiError error -> json(error.status(),
                                        ErrorResponse.errorResponse(error.message()));
            default -> json(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
        };
    }

    /// The status [#failure] answers for `cause`.
    public static HttpStatus status(Cause cause) {
        return switch (cause) {
            case ApiError error -> error.status();
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
