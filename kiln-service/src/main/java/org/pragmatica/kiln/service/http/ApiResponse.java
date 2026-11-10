package org.pragmatica.kiln.service.http;

/// One answer: its status, content type and body.
public record ApiResponse(int status, String contentType, String body) {
    public static final String HTML = "text/html; charset=utf-8";
    public static final String JSON = "application/json";
    public static final String TEXT = "text/plain; charset=utf-8";

    public static ApiResponse html(String body) {
        return new ApiResponse(200, HTML, body);
    }

    public static ApiResponse json(String body) {
        return new ApiResponse(200, JSON, body);
    }

    public static ApiResponse text(String body) {
        return new ApiResponse(200, TEXT, body);
    }

    /// A failure's answer: its status and its message as plain text.
    public static ApiResponse failure(int status, String message) {
        return new ApiResponse(status, TEXT, message + "\n");
    }
}
