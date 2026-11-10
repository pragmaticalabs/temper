package org.pragmatica.temper.controller.http;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Failures a route answers with a specific status (anything else is a 500). The message is what the client sees in
/// `ErrorResponse.error`; a wrapped origin is carried for the log, never printed into the message.
public sealed interface ApiError extends Cause {
    HttpStatus status();

    /// Fixed-text failures: 401 without a valid bearer token (Q-1: every route), 403 for the read token on a WRITE
    /// route, 403 for the worker or a lease token on a route its scope does not reach (m3-design §6.4), 404 for an
    /// unknown route; the fleet stream's subscriber refusals: 404 `NO_SUBSCRIBER`, 409
    /// `SUBSCRIBER_EXISTS`, 403 for a token the subscriber is not bound to (R2-f), 400 `ACK_AHEAD` for an ack above the
    /// head, 409 `EPOCH_MISMATCH` for an ack read in another epoch (lead ruling F2) and 400 `START_AHEAD` for a
    /// subscriber start above the head, 503 `NOT_READY` before the controller's
    /// start has completed; the T2 refusals: 422 `VERDICT_LINE_MISMATCH`,
    /// `CARRY_NO_ACCEPT`, `NOT_OPEN`, `POSITION_OUT_OF_RANGE`, 404 `NOT_QUEUED`, `PATH_NOT_FOUND`, `repo not configured` (a kiln 404), 409 `PATH_EXISTS`;
    /// the GO refusals: 422 `FRESH_TOKEN_INVALID`, `FRESH_TOKEN_EXPIRED`, 409 `EPOCH_CHANGED`, `go-used`,
    /// `go-expired`, 404 `GO_NOT_FOUND`; the ticket listing: 404 `NO_HOST`.
    enum General implements ApiError {
        UNAUTHORIZED("unauthorized"),
        FORBIDDEN("forbidden: read-only token"),
        NOT_PERMITTED("forbidden: not permitted for this token"),
        NOT_FOUND("not found"),
        NO_SUBSCRIBER("NO_SUBSCRIBER"),
        SUBSCRIBER_EXISTS("SUBSCRIBER_EXISTS"),
        SUBSCRIBER_TOKEN("forbidden: the subscriber is bound to another token"),
        ACK_AHEAD("ACK_AHEAD"),
        EPOCH_MISMATCH("EPOCH_MISMATCH"),
        START_AHEAD("START_AHEAD"),
        NOT_READY("NOT_READY"),
        VERDICT_LINE_MISMATCH("VERDICT_LINE_MISMATCH"),
        CARRY_NO_ACCEPT("CARRY_NO_ACCEPT"),
        NOT_OPEN("NOT_OPEN"),
        POSITION_OUT_OF_RANGE("POSITION_OUT_OF_RANGE"),
        NOT_QUEUED("NOT_QUEUED"),
        PATH_NOT_FOUND("PATH_NOT_FOUND"),
        PATH_EXISTS("PATH_EXISTS"),
        REPO_NOT_CONFIGURED("repo not configured"),
        FRESH_TOKEN_INVALID("FRESH_TOKEN_INVALID"),
        FRESH_TOKEN_EXPIRED("FRESH_TOKEN_EXPIRED"),
        EPOCH_CHANGED("EPOCH_CHANGED"),
        GO_NOT_FOUND("GO_NOT_FOUND"),
        NO_HOST("NO_HOST"),
        GO_USED("go-used"),
        GO_EXPIRED("go-expired");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
        @Override
        public HttpStatus status() {
            return switch (this) {
                case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
                case FORBIDDEN, NOT_PERMITTED, SUBSCRIBER_TOKEN -> HttpStatus.FORBIDDEN;
                case NOT_FOUND, NO_SUBSCRIBER, NOT_QUEUED, PATH_NOT_FOUND, REPO_NOT_CONFIGURED, GO_NOT_FOUND, NO_HOST -> HttpStatus.NOT_FOUND;
                case SUBSCRIBER_EXISTS, EPOCH_MISMATCH, PATH_EXISTS, EPOCH_CHANGED, GO_USED, GO_EXPIRED -> HttpStatus.CONFLICT;
                case VERDICT_LINE_MISMATCH, CARRY_NO_ACCEPT, NOT_OPEN, POSITION_OUT_OF_RANGE, FRESH_TOKEN_INVALID, FRESH_TOKEN_EXPIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
                case ACK_AHEAD, START_AHEAD -> HttpStatus.BAD_REQUEST;
                case NOT_READY -> HttpStatus.SERVICE_UNAVAILABLE;
            };
        }
    }

    /// 400: the request does not parse. The detail is the origin's own message, which names the offending field
    /// (`Invalid submittedBy: '...'`), so the client sees what to fix.
    @SuppressWarnings("JBCT-CAUSE-04")
    record BadRequest(String detail, Cause origin, String message) implements ApiError, Cause.Wrapped {
        public static final Fn2<BadRequest, String, Cause> FACTORY = Causes.forTwoValues("%s", BadRequest::new);

        /// Translator for a parse failure: keeps it as the origin and shows its message.
        public static BadRequest badRequest(Cause origin) {
            return FACTORY.apply(origin.message(), origin);
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.BAD_REQUEST;
        }
    }

    /// 404 for a named resource.
    record NotFound(String what, String message) implements ApiError {
        public static final Fn1<NotFound, String> FACTORY = Causes.forOneValue("%s not found", NotFound::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.NOT_FOUND;
        }
    }

    /// 409: the resource already exists (a refusal code, which is never reused, m1-design §6).
    record Conflict(String detail, String message) implements ApiError {
        public static final Fn1<Conflict, String> FACTORY = Causes.forOneValue("%s", Conflict::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.CONFLICT;
        }
    }

    /// 422: the request parses, but the state it meets refuses it (m3-design §11.1: `bad-next`, `base-unknown`, a park
    /// request that is not valid, …); `detail` is the wire code and the client's whole message.
    record Unprocessable(String detail, String message) implements ApiError {
        public static final Fn1<Unprocessable, String> FACTORY = Causes.forOneValue("%s", Unprocessable::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    /// 503: a dependency the request needs is down (the host link for `log`, git for source resolution). The
    /// origin is carried for the log, not shown.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unavailable(String what, Cause origin, String message) implements ApiError, Cause.Wrapped {
        public static final Fn2<Unavailable, String, Cause> FACTORY = Causes.forTwoValues("%s is unavailable",
                                                                                          Unavailable::new);

        /// Translator naming the unavailable dependency.
        public static Fn1<Unavailable, Cause> of(String what) {
            return origin -> FACTORY.apply(what, origin);
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /// 503 with a wire code of its own (`RATE_LIMITED until=<ISO>`, `CARRY_PROOF_UNAVAILABLE git`): `detail` is the code and the client's whole message, kept apart from [Unavailable] whose text is fixed.
    record Transient(String detail, String message) implements ApiError {
        public static final Fn1<Transient, String> FACTORY = Causes.forOneValue("%s", Transient::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /// 412 (D-6, M1): a mutating request from a CLI of another judged build, refused before anything acts on it
    /// ([VersionGate]); the CLI maps it to exit 13.
    record VersionSkew(String cliVersion, String controllerVersion, String message) implements ApiError {
        public static final Fn2<VersionSkew, String, String> FACTORY = Causes.forTwoValues("CLI version %s does not match controller version %s; install the CLI built from the controller's commit",
                                                                                           VersionSkew::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.PRECONDITION_FAILED;
        }
    }

    /// 412 (D-6): a mutating request whose `Temper-Version` header is present but not a version at all (empty, blank,
    /// over 40 characters, or outside `[0-9A-Za-z.+-]`); refused before anything acts, never read as an old CLI.
    record MalformedVersion(String value, String message) implements ApiError {
        public static final Fn1<MalformedVersion, String> FACTORY = Causes.forOneValue("Temper-Version '%s' is not a build version (1 to 40 of [0-9A-Za-z.+-]); install the CLI built from the controller's commit",
                                                                                       MalformedVersion::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.PRECONDITION_FAILED;
        }
    }
}
