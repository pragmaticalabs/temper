package org.pragmatica.temper.worker;

import java.io.FileDescriptor;
import java.io.FileOutputStream;

import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.frame.FrameCodec;
import org.pragmatica.temper.protocol.frame.FrameWriter;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;


/// A subprocess entry for [MainStdoutTest]: the worker's frame path WITHOUT `Main.main`'s `System.setOut` guard,
/// so only the shipped `logback.xml` keeps log lines off stdout. It writes a frame, logs at every level (root set to
/// TRACE), runs the real [Main#run] into a startup failure (the caller removes `HOME`), writes a second frame and
/// exits with the worker's exit code.
public final class StdoutHarness {
    private StdoutHarness() {}

    public static void main(String[] args) {
        var frames = new FileOutputStream(FileDescriptor.out);
        var writer = FrameWriter.frameWriter(frames);
        var mapper = TemperJson.mapper();
        var log = LoggerFactory.getLogger(StdoutHarness.class);

        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.TRACE);
        FrameCodec.encode(Envelope.envelope(1,
                                            Message.Failure.failure("first", "before logging")),
                          mapper)
                  .flatMap(writer::write)
                  .onFailure(cause -> System.exit(90));
        log.trace("harness-trace");
        log.debug("harness-debug");
        log.info("harness-info");
        log.warn("harness-warn");
        log.error("harness-error");
        var rc = Main.run(frames, System.in);

        FrameCodec.encode(Envelope.envelope(2,
                                            Message.Failure.failure("second", "after the worker")),
                          mapper)
                  .flatMap(writer::write)
                  .onFailure(cause -> System.exit(91));
        System.exit(rc);
    }
}
