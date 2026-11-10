package org.pragmatica.temper.controller.http;

import java.nio.charset.StandardCharsets;

import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;


/// What a route answers: status, content type and body. [HttpGlue] is the only code that writes it to rc4's
/// `ResponseWriter`.
///
/// The body array is exposed as built, not copied; nothing mutates it after construction.
public record ApiResponse(HttpStatus status, ContentType contentType, byte[] body) {
    public static ApiResponse apiResponse(HttpStatus status, ContentType contentType, byte[] body) {
        return new ApiResponse(status, contentType, body);
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
