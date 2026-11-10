package org.pragmatica.temper.protocol.api;

/// Error body for 400/401/404/503. A route called without a valid bearer token answers `401` with
/// [#UNAUTHORIZED], which the CLI maps to exit 77 (Q-15).
public record ErrorResponse(String error) {
    public static final ErrorResponse UNAUTHORIZED = new ErrorResponse("unauthorized");

    public static ErrorResponse errorResponse(String error) {
        return new ErrorResponse(error);
    }
}
