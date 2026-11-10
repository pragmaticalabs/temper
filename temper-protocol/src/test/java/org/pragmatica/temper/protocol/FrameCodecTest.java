package org.pragmatica.temper.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.frame.FrameCodec;
import org.pragmatica.temper.protocol.frame.FrameError;
import org.pragmatica.temper.protocol.frame.FrameReader;
import org.pragmatica.temper.protocol.frame.FrameWriter;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.LogSource;
import org.pragmatica.temper.protocol.message.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// m0-design §4.1 frame format: `uint32 BE length (1..16 MiB) || UTF-8 JSON envelope`.
class FrameCodecTest {
    private static final JsonMapper MAPPER = TemperJson.mapper();

    private static <T> T ok(Result<T> result) {
        return result.fold(cause -> fail("expected success, got: " + cause.message()), value -> value);
    }

    private static <T> Cause failure(Result<T> result) {
        return result.fold(cause -> cause, value -> fail("expected failure, got: " + value));
    }

    private static <T> T present(Option<T> option) {
        return option.fold(() -> fail("expected a frame"), value -> value);
    }

    private static byte[] frame(long id) {
        return ok(FrameCodec.encode(Envelope.envelope(id, new Message.Collect(id, "main", 1)), MAPPER));
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();

        Arrays.stream(parts)
              .forEach(out::writeBytes);
        return out.toByteArray();
    }

    @Test
    void encode_prefixesBigEndianLength() {
        var frame = frame(1);

        assertThat(ByteBuffer.wrap(frame)
                             .getInt()).isEqualTo(frame.length - 4);
    }

    @Test
    void reader_returnsEachFrame_thenNoneAtCleanEof() {
        var reader = FrameReader.frameReader(new ByteArrayInputStream(concat(frame(1), frame(2))));

        assertThat(ok(FrameCodec.decode(present(ok(reader.next())), MAPPER)).id()).isEqualTo(1);
        assertThat(ok(FrameCodec.decode(present(ok(reader.next())), MAPPER)).id()).isEqualTo(2);
        assertThat(ok(reader.next())).isEqualTo(Option.none());
    }

    @Test
    void reader_failsTruncated_onEofInsideHeaderOrPayload() {
        var whole = frame(1);

        assertThat(failure(FrameReader.frameReader(new ByteArrayInputStream(Arrays.copyOf(whole, 2)))
                                      .next())).isEqualTo(FrameError.General.TRUNCATED);
        assertThat(failure(FrameReader.frameReader(new ByteArrayInputStream(Arrays.copyOf(whole, whole.length - 1)))
                                      .next())).isEqualTo(FrameError.General.TRUNCATED);
    }

    @Test
    void reader_failsBadLength_forZeroAndOversizedPrefix() {
        var zero = ByteBuffer.allocate(4)
                             .putInt(0)
                             .array();
        var oversized = ByteBuffer.allocate(4)
                                  .putInt(FrameCodec.MAX_FRAME + 1)
                                  .array();
        var negativeAsUnsigned = ByteBuffer.allocate(4)
                                           .putInt(-1)
                                           .array();

        assertThat(failure(FrameReader.frameReader(new ByteArrayInputStream(zero))
                                      .next())).isInstanceOf(FrameError.BadLength.class);
        assertThat(failure(FrameReader.frameReader(new ByteArrayInputStream(oversized))
                                      .next())).isInstanceOf(FrameError.BadLength.class);
        assertThat(failure(FrameReader.frameReader(new ByteArrayInputStream(negativeAsUnsigned))
                                      .next())).isInstanceOf(FrameError.BadLength.class);
    }

    @Test
    void encode_refusesOversizedEnvelope() {
        var huge = new Message.LogChunk("x".repeat(FrameCodec.MAX_FRAME), true, LogSource.FILE);

        assertThat(failure(FrameCodec.encode(Envelope.envelope(1, huge), MAPPER))).isInstanceOf(FrameError.BadLength.class);
    }

    @Test
    void decode_failsOnNonEnvelopeJson() {
        assertThat(failure(FrameCodec.decode("{\"nope\":1}".getBytes(), MAPPER))).isNotNull();
    }

    @Test
    void writer_neverInterleavesConcurrentFrames() throws Exception {
        var sink = new ByteArrayOutputStream();
        var writer = FrameWriter.frameWriter(sink);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 400)
                     .forEach(id -> pool.submit(() -> ok(writer.write(frame(id + 1)))));
        }

        var reader = FrameReader.frameReader(new ByteArrayInputStream(sink.toByteArray()));
        var ids = new ArrayList<Long>();
        Option<byte[]> next;

        while ((next = ok(reader.next())).isPresent()) {
            ids.add(ok(FrameCodec.decode(present(next), MAPPER)).id());
        }
        assertThat(ids).hasSize(400)
                       .doesNotHaveDuplicates();
        assertThat(List.copyOf(ids)).allMatch(id -> id >= 1 && id <= 400);
    }
}
