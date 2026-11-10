package org.pragmatica.temper.protocol.frame;

import java.io.OutputStream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Writes whole frames (from [FrameCodec#encode]) and flushes each one. Thread-safe: concurrent writers are
/// serialised on the stream's monitor, so frames never interleave. Every writer of one stream must share
/// that same `OutputStream` instance (a wrapper around it would take a different monitor).
@FunctionalInterface
public interface FrameWriter {
    Result<Unit> write(byte[] frame);

    static FrameWriter frameWriter(OutputStream out) {
        return frame -> writeFlushed(out, frame);
    }

    /// Write and flush under the stream's monitor: a frame is never interleaved with another writer's.
    private static Result<Unit> writeFlushed(OutputStream out, byte[] frame) {
        synchronized (out) {
            return Result.lift(ioError(),
                               () -> out.write(frame))
                         .flatMap(_ -> Result.lift(ioError(),
                                                   out::flush));
        }
    }

    private static Fn1<FrameError.Io, Throwable> ioError() {
        return thrown -> FrameError.Io.FACTORY.apply(Causes.fromThrowable(thrown));
    }
}
