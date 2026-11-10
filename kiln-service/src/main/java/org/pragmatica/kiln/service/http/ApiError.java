package org.pragmatica.kiln.service.http;

import org.pragmatica.lang.Cause;


/// A request kiln refuses: a parameter it cannot read (400), or a write it does not accept from where it came (403). Any
/// other failure of a route answers 500 ([Router]); a body over the cap is refused by [LoopbackServer] before any route
/// sees it (413).
public sealed interface ApiError extends Cause {
    int BAD_REQUEST = 400;
    int FORBIDDEN = 403;
    int status();

    /// `parameter` names the query parameter, `why` what is wrong with it.
    record BadRequest(String parameter, String why, String message) implements ApiError {
        @Override
        public int status() {
            return BAD_REQUEST;
        }
    }

    /// `rule` names the request rule the request broke.
    record Forbidden(String rule, String message) implements ApiError {
        @Override
        public int status() {
            return FORBIDDEN;
        }
    }

    static ApiError forbidden(String rule) {
        return new Forbidden(rule, "forbidden: " + rule);
    }

    static ApiError badRequest(String parameter, String why) {
        return new BadRequest(parameter, why, "bad parameter " + parameter + ": " + why);
    }
}
