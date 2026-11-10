package org.pragmatica.temper.protocol.frame;

import java.io.InputStream;
import java.nio.ByteBuffer;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Reads frames from a blocking stream. `next()` gives `none()` on a clean EOF at a frame boundary, and a
/// failure on EOF inside a frame or a length outside `1..MAX_FRAME`. Not thread-safe: one reader per stream.
@FunctionalInterface
public interface FrameReader {
    Result<Option<byte[]>> next();

    static FrameReader frameReader(InputStream in) {
        return () -> readFrame(in);
    }

    private static Result<Option<byte[]>> readFrame(InputStream in) {
        return read(in, FrameCodec.PREFIX_BYTES).flatMap(header -> fromHeader(in, header));
    }

    private static Result<Option<byte[]>> fromHeader(InputStream in, byte[] header) {
        if (header.length == 0) {
            return success(none());
        }

        if (header.length < FrameCodec.PREFIX_BYTES) {
            return FrameError.General.TRUNCATED.result();
        }

        return payload(in,
                       Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt())).map(Option::some);
    }

    private static Result<byte[]> payload(InputStream in, long length) {
        return FrameCodec.checkLength(length)
                         .flatMap(valid -> read(in,
                                                valid.intValue()))
                         .flatMap(bytes -> Verify.ensure(bytes,
                                                         complete -> complete.length == length,
                                                         FrameError.General.TRUNCATED));
    }

    private static Result<byte[]> read(InputStream in, int count) {
        return Result.lift(thrown -> FrameError.Io.FACTORY.apply(Causes.fromThrowable(thrown)),
                           () -> in.readNBytes(count));
    }
}
