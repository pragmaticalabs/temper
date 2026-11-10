package org.pragmatica.temper.worker.link;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.frame.FrameCodec;
import org.pragmatica.temper.protocol.frame.FrameReader;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.WorkerError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The worker's read loop over stdin (m0-design §7.2 step 4). One thread reads frames; each request is handed
/// to the [RequestHandler] and answered when its promise resolves, so a slow request (a 20-minute image build) never
/// blocks the next frame. Replies and unsolicited messages share the [Outbox].
///
/// Lifecycle frames are handled in the loop itself: `Welcome` completes the handshake (requests before it are
/// refused with `handshake`), `Incompatible` and `Shutdown` end the loop. Exit codes:
///
/// - `0`: clean EOF at a frame boundary, `Shutdown`, `Incompatible` (the controller deploys and reconnects), or a
///   `Welcome` of another protocol;
///   detached containers keep running;
/// - `2`: the frame stream broke (EOF inside a frame, a bad length, an I/O failure).
public record FrameLoop(FrameReader reader,
                        JsonMapper mapper,
                        Outbox outbox,
                        RequestHandler router,
                        Runnable onWelcome) {
    private static final Logger LOG = LoggerFactory.getLogger(FrameLoop.class);
    public static final int EXIT_OK = 0;
    public static final int EXIT_STREAM_BROKEN = 2;

    /// Reads until the stream ends or the controller ends the session; returns the exit code. Each frame yields
    /// `none()` to continue or the exit code to stop.
    public int run() {
        var welcomed = new AtomicBoolean();

        return Stream.generate(reader::next)
                     .map(next -> step(next, welcomed))
                     .flatMap(Option::stream)
                     .findFirst()
                     .orElse(EXIT_OK);
    }

    public static FrameLoop frameLoop(FrameReader reader,
                                      JsonMapper mapper,
                                      Outbox outbox,
                                      RequestHandler router,
                                      Runnable onWelcome) {
        return new FrameLoop(reader, mapper, outbox, router, onWelcome);
    }

    /// One frame read: `none()` to continue, or the exit code.
    private Option<Integer> step(Result<Option<byte[]>> next, AtomicBoolean welcomed) {
        return next.fold(this::broken, frame -> frameOrClosed(frame, welcomed));
    }

    private Option<Integer> frameOrClosed(Option<byte[]> frame, AtomicBoolean welcomed) {
        return frame.map(bytes -> handled(bytes, welcomed))
                    .or(this::closed);
    }

    private Option<Integer> broken(Cause cause) {
        LOG.error("frame stream broken: {}", cause);

        return some(EXIT_STREAM_BROKEN);
    }

    private Option<Integer> closed() {
        LOG.info("controller closed the link; containers keep running");

        return some(EXIT_OK);
    }

    private Option<Integer> handled(byte[] frame, AtomicBoolean welcomed) {
        return FrameCodec.decode(frame, mapper).fold(this::undecodable, envelope -> handled(envelope, welcomed));
    }

    /// A frame that does not decode is answered with an unsolicited `Failure{bad-frame}`; the stream itself is
    /// still aligned (the length prefix was valid), so the loop continues.
    private Option<Integer> undecodable(Cause cause) {
        LOG.error("undecodable frame: {}", cause);
        sent(outbox.post(Message.Failure.failure("bad-frame", cause.message())));

        return none();
    }

    private Option<Integer> handled(Envelope envelope, AtomicBoolean welcomed) {
        return switch (envelope.msg()) {
            case Message.Welcome welcome -> welcomed(welcome, welcomed);
            case Message.Incompatible incompatible -> incompatible(incompatible);
            case Message.Shutdown _ -> shutdown();
            default -> dispatched(envelope, welcomed.get());
        };
    }

    /// The controller checks the protocol in `Hello`; the worker checks it in `Welcome` too (m4-design §7), so a
    /// controller of another protocol (a v2 one sends attempt-less requests) is refused by both sides: the worker
    /// ends the session as on `Incompatible`, before any request is served.
    private Option<Integer> welcomed(Message.Welcome welcome, AtomicBoolean welcomed) {
        return welcome.protocol() == Handshake.PROTOCOL_VERSION
               ? accepted(welcome, welcomed)
               : refused(welcome);
    }

    private Option<Integer> accepted(Message.Welcome welcome, AtomicBoolean welcomed) {
        LOG.info("handshake complete: controller {}", welcome.controllerVersion());
        welcomed.set(true);
        onWelcome.run();

        return none();
    }

    private static Option<Integer> refused(Message.Welcome welcome) {
        LOG.error("controller {} speaks protocol {}, this worker protocol {}: session refused",
                  welcome.controllerVersion(),
                  welcome.protocol(),
                  Handshake.PROTOCOL_VERSION);

        return some(EXIT_OK);
    }

    private Option<Integer> incompatible(Message.Incompatible incompatible) {
        LOG.warn("controller refused this worker: {}", incompatible.reason());

        return some(EXIT_OK);
    }

    private Option<Integer> shutdown() {
        LOG.info("controller requested shutdown");

        return some(EXIT_OK);
    }

    /// Hands a request to its handler and answers it when the handler's promise resolves; the loop does not wait,
    /// so a slow request never blocks the next frame. The reply IS the outcome.
    private Option<Integer> dispatched(Envelope envelope, boolean welcomed) {
        // May run late: the answer is detached by design (the loop never waits for a request).
        handled(envelope.msg(), welcomed).onResult(result -> answered(envelope, result));

        return none();
    }

    private Promise<Reply> handled(Message request, boolean welcomed) {
        return welcomed
               ? router.route(request)
               : WorkerError.General.BEFORE_HANDSHAKE.promise();
    }

    private Unit answered(Envelope envelope, Result<Reply> result) {
        return result.fold(cause -> failed(envelope, cause), reply -> replied(envelope, reply));
    }

    private Unit failed(Envelope envelope, Cause cause) {
        LOG.warn("request {} ({}) failed: {}",
                 envelope.id(),
                 envelope.msg().getClass().getSimpleName(),
                 cause);

        return sent(outbox.reply(envelope.id(),
                                 Message.Failure.failure(WorkerError.code(cause), cause.message())));
    }

    private Unit replied(Envelope envelope, Reply reply) {
        return switch (reply) {
            case Reply.Respond respond -> respondedThen(envelope, respond);
            case Reply.Silent _ -> unit();
        };
    }

    private Unit respondedThen(Envelope envelope, Reply.Respond respond) {
        sent(outbox.reply(envelope.id(), respond.message()));

        return respond.followUp()
                      .map(followUp -> started(envelope, followUp))
                      .or(unit());
    }

    /// A follow-up that fails (a `Collect` whose report cannot be produced) is answered with a second frame
    /// replying to the same request: `Failure{code, detail}` with `replyTo` = the request id, so the controller can
    /// attribute it to the unit it asked about instead of waiting for a report that will not come.
    private Unit started(Envelope envelope, Reply.FollowUp followUp) {
        // May run late: the follow-up is detached; its failure is only answered with a frame.
        followUp.run().onFailure(cause -> failed(envelope, cause));

        return unit();
    }

    /// FER (degrade forward): a frame that cannot be written means stdout is gone; the controller will see EOF
    /// and reconnect, and every result the frame carried is on disk for the next worker. Logged, not retried.
    private static Unit sent(Result<Unit> written) {
        written.onFailure(cause -> LOG.error("frame not written: {}", cause));

        return unit();
    }
}
