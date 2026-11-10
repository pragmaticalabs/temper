package org.pragmatica.temper.protocol.frame;

import java.nio.ByteBuffer;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Frame format (m0-design §4.1): `uint32 big-endian length N (1 ≤ N ≤ 16 MiB) || N bytes UTF-8 JSON`, one
/// [Envelope] per frame.
///
/// `encode` produces the whole frame (prefix included) for [FrameWriter]; `decode` takes the payload that
/// [FrameReader] returns (prefix already stripped). Both directions enforce the same length bound.
public sealed interface FrameCodec {
    int MAX_FRAME = 16 * 1024 * 1024;
    int PREFIX_BYTES = 4;

    static Result<byte[]> encode(Envelope envelope, JsonMapper mapper) {
        return mapper.writeAsBytes(envelope)
                     .flatMap(FrameCodec::checkLength)
                     .map(FrameCodec::prefixed);
    }

    static Result<Envelope> decode(byte[] payload, JsonMapper mapper) {
        return checkLength(payload).flatMap(json -> envelope(json, mapper));
    }

    /// Whether a frame length is within `1..MAX_FRAME`.
    static Result<Long> checkLength(long length) {
        return Verify.ensure(length, Verify.Is::between, 1L, (long) MAX_FRAME, FrameError.BadLength.FACTORY);
    }

    private static Result<Envelope> envelope(byte[] json, JsonMapper mapper) {
        return mapper.readBytes(json, Envelope.class);
    }

    private static Result<byte[]> checkLength(byte[] payload) {
        return checkLength((long) payload.length).map(_ -> payload);
    }

    private static byte[] prefixed(byte[] payload) {
        return ByteBuffer.allocate(PREFIX_BYTES + payload.length)
                         .putInt(payload.length)
                         .put(payload)
                         .array();
    }

    record unused() implements FrameCodec {}
}
