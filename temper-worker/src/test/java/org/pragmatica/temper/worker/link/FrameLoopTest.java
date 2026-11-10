package org.pragmatica.temper.worker.link;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.frame.FrameCodec;
import org.pragmatica.temper.protocol.frame.FrameReader;
import org.pragmatica.temper.protocol.frame.FrameWriter;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.WorkerError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The frame loop (m0-design §7.2): the handshake gate, request/reply correlation, `Ack` before a follow-up,
/// failures as `Failure{code}`, and the exit codes for EOF, `Incompatible`, `Shutdown` and a broken stream.
class FrameLoopTest {
    private static final JsonMapper MAPPER = TemperJson.mapper();
    private static final HostFactsDto FACTS = new HostFactsDto(0, 0, 4, 0, 0, none(), none(), 1, 2, 3, Instant.EPOCH, List.of());

    private static byte[] frames(Message... messages) {
        var out = new ByteArrayOutputStream();

        for (var index = 0; index < messages.length; index++) {
            out.writeBytes(ok(FrameCodec.encode(Envelope.envelope(100 + index, messages[index]), MAPPER)));
        }
        return out.toByteArray();
    }

    private record Session(int exit, List<Envelope> sent, int welcomes) {}

    private static Session session(byte[] input, RequestHandler handler, Predicate<List<Envelope>> done) throws InterruptedException {
        var out = new ByteArrayOutputStream();
        var outbox = Outbox.framed(FrameWriter.frameWriter(out), MAPPER);
        var welcomes = new AtomicInteger();
        var exit = FrameLoop.frameLoop(FrameReader.frameReader(new ByteArrayInputStream(input)),
                                       MAPPER,
                                       outbox,
                                       handler,
                                       welcomes::incrementAndGet)
                            .run();
        var sent = List.<Envelope> of();

        for (var wait = 0; wait < 200; wait++) {
            sent = decoded(out);
            if (done.test(sent)) {
                break;
            }
            Thread.sleep(10);
        }
        return new Session(exit, sent, welcomes.get());
    }

    private static List<Envelope> decoded(ByteArrayOutputStream out) {
        byte[] bytes;

        synchronized (out) {
            bytes = out.toByteArray();
        }

        var reader = FrameReader.frameReader(new ByteArrayInputStream(bytes));
        var envelopes = new ArrayList<Envelope>();

        while (true) {
            var next = ok(reader.next());

            if (next.isEmpty()) {
                return envelopes;
            }
            next.onPresent(frame -> envelopes.add(ok(FrameCodec.decode(frame, MAPPER))));
        }
    }

    private static final RequestHandler FACTS_HANDLER = message -> message instanceof Message.FactsRequest
                                                                   ? Promise.success(Reply.respond(new Message.Facts(FACTS)))
                                                                   : Causes.cause("unexpected").promise();

    @Test
    void run_refusesRequestsBeforeTheHandshake() throws Exception {
        var session = session(frames(new Message.FactsRequest()), FACTS_HANDLER, sent -> !sent.isEmpty());

        assertThat(session.exit()).isZero();
        assertThat(session.sent()).singleElement()
                                  .satisfies(envelope -> {
                                      assertThat(envelope.replyTo()).isEqualTo(Option.some(100L));
                                      assertThat(envelope.msg()).isEqualTo(Message.Failure.failure("handshake",
                                                                                               "Request received before the handshake completed"));
                                  });
    }

    @Test
    void run_answersRequestsAfterWelcome_withReplyToTheRequestId() throws Exception {
        var session = session(frames(new Message.Welcome(Handshake.PROTOCOL_VERSION, "0.1.0"), new Message.FactsRequest()), FACTS_HANDLER, sent -> !sent.isEmpty());

        assertThat(session.welcomes()).isEqualTo(1);
        assertThat(session.sent()).singleElement()
                                  .satisfies(envelope -> {
                                      assertThat(envelope.replyTo()).isEqualTo(Option.some(101L));
                                      assertThat(envelope.msg()).isEqualTo(new Message.Facts(FACTS));
                                  });
    }

    @Test
    void run_writesTheReplyBeforeStartingTheFollowUp() throws Exception {
        var out = new ByteArrayOutputStream();
        var outbox = Outbox.framed(FrameWriter.frameWriter(out), MAPPER);
        RequestHandler handler = _ -> Promise.success(Reply.respond(new Message.Ack(none()),
                                                                    () -> outbox.post(new Message.Shutdown())
                                                                                .async()));

        FrameLoop.frameLoop(FrameReader.frameReader(new ByteArrayInputStream(frames(new Message.Welcome(Handshake.PROTOCOL_VERSION, "v"),
                                                                                    new Message.Collect(1, "main", 1)))),
                            MAPPER,
                            outbox,
                            handler,
                            () -> {})
                 .run();

        var sent = List.<Envelope> of();

        for (var wait = 0; wait < 200 && sent.size() < 2; wait++) {
            sent = decoded(out);
            Thread.sleep(10);
        }

        assertThat(sent).extracting(Envelope::msg)
                        .containsExactly(new Message.Ack(none()), new Message.Shutdown());
        assertThat(sent.getFirst()
                       .replyTo()).isEqualTo(Option.some(101L));
        assertThat(sent.get(1)
                       .replyTo()).isEqualTo(none());
    }

    @Test
    void run_answersAFailedRequestWithItsFailureCode() throws Exception {
        RequestHandler handler = _ -> WorkerError.General.SEED_MISSING.promise();
        var session = session(frames(new Message.Welcome(Handshake.PROTOCOL_VERSION, "v"), new Message.PrepareJob(1, "a".repeat(40), "o")),
                              handler,
                              sent -> !sent.isEmpty());

        assertThat(session.sent()).singleElement()
                                  .extracting(Envelope::msg)
                                  .isInstanceOfSatisfying(Message.Failure.class,
                                                          failure -> assertThat(failure.code()).isEqualTo("seed-missing"));
    }

    /// The worker checks `Welcome.protocol` too: a controller of another protocol (a v2 one) is refused by the
    /// worker as well; the session ends before any request is served.
    @Test
    void run_endsTheSession_onAWelcomeOfAnotherProtocol() throws Exception {
        var session = session(frames(new Message.Welcome(Handshake.PROTOCOL_VERSION - 1, "v"), new Message.FactsRequest()),
                              FACTS_HANDLER,
                              _ -> true);

        assertThat(session.exit()).isZero();
        assertThat(session.welcomes()).isZero();
        assertThat(session.sent()).isEmpty();
    }

    @Test
    void run_endsCleanlyOnIncompatibleAndShutdown_evenWithMoreInput() throws Exception {
        var incompatible = session(frames(new Message.Incompatible(1, "0.2.0", "version"), new Message.Welcome(Handshake.PROTOCOL_VERSION, "v")),
                                   FACTS_HANDLER,
                                   _ -> true);

        assertThat(incompatible.exit()).isZero();
        assertThat(incompatible.welcomes()).isZero();

        var shutdown = session(frames(new Message.Welcome(Handshake.PROTOCOL_VERSION, "v"), new Message.Shutdown(), new Message.FactsRequest()),
                               FACTS_HANDLER,
                               _ -> true);

        assertThat(shutdown.exit()).isZero();
        assertThat(shutdown.sent()).isEmpty();
    }

    @Test
    void run_exits2_onATruncatedFrame() throws Exception {
        var whole = frames(new Message.Welcome(Handshake.PROTOCOL_VERSION, "v"));
        var truncated = Arrays.copyOf(whole, whole.length - 3);

        assertThat(session(truncated, FACTS_HANDLER, _ -> true).exit()).isEqualTo(FrameLoop.EXIT_STREAM_BROKEN);
    }

    @Test
    void run_reportsAnUndecodableFrame_andKeepsReading() throws Exception {
        var garbage = "{\"nope\":1}".getBytes(StandardCharsets.UTF_8);
        var frame = ByteBuffer.allocate(4 + garbage.length)
                              .putInt(garbage.length)
                              .put(garbage)
                              .array();
        var input = new ByteArrayOutputStream();

        input.writeBytes(frame);
        input.writeBytes(frames(new Message.Welcome(Handshake.PROTOCOL_VERSION, "v"), new Message.FactsRequest()));

        var session = session(input.toByteArray(), FACTS_HANDLER, sent -> sent.size() >= 2);

        assertThat(session.sent()).extracting(Envelope::msg)
                                  .anySatisfy(message -> assertThat(message).isInstanceOfSatisfying(Message.Failure.class,
                                                                                                    failure -> assertThat(failure.code()).isEqualTo("bad-frame")))
                                  .anySatisfy(message -> assertThat(message).isInstanceOf(Message.Facts.class));
    }

    @Test
    void run_answersAFailedFollowUp_withAFailureToTheSameRequest() throws Exception {
        RequestHandler handler = _ -> Promise.success(Reply.respond(new Message.Ack(none()),
                                                                    () -> WorkerError.General.SEED_MISSING.promise()));
        var session = session(frames(new Message.Welcome(Handshake.PROTOCOL_VERSION, "v"), new Message.Collect(1, "main", 1)), handler, sent -> sent.size() >= 2);

        assertThat(session.sent()).extracting(Envelope::msg)
                                  .containsExactly(new Message.Ack(none()),
                                                   Message.Failure.failure("seed-missing", WorkerError.General.SEED_MISSING.message()));
        assertThat(session.sent()).extracting(Envelope::replyTo)
                                  .containsOnly(Option.some(101L));
    }
}
