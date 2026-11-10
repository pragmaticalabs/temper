package org.pragmatica.temper.worker.unit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.message.LogSource;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;


/// `LogTail` (m0-design §7.8): the unit's `log` file when it exists (read backwards, at most 1 MiB), otherwise
/// `docker logs --tail <n>`. Lines are clamped to `1..MAX_LOG_LINES`; the text never exceeds `MAX_LOG_BYTES`.
public record LogTailer(WorkerPaths paths, UnitFiles files, DockerCli docker) {
    public static LogTailer logTailer(WorkerPaths paths, UnitFiles files, DockerCli docker) {
        return new LogTailer(paths, files, docker);
    }

    public Promise<Message> tail(UnitRef ref, int requestedLines) {
        var lines = Math.clamp(requestedLines, 1, Message.MAX_LOG_LINES);
        var log = paths.logFile(ref);

        return FileOps.isRegularFile(log)
               ? fromFile(log, lines)
               : fromContainer(ref, lines);
    }

    private static Promise<Message> fromFile(Path log, int lines) {
        return LogFiles.tail(log, lines, Message.MAX_LOG_BYTES)
                       .map(text -> chunk(text, LogSource.FILE))
                       .async();
    }

    private Promise<Message> fromContainer(UnitRef ref, int lines) {
        return files.container(ref)
                    .async()
                    .flatMap(container -> docker.logsTail(container, lines))
                    .map(completed -> chunk(fromDocker(completed, lines),
                                            LogSource.DOCKER));
    }

    /// stdout then stderr, cut to the last `MAX_LOG_BYTES`.
    static LogFiles.LogText fromDocker(Completed completed, int lines) {
        var combined = (completed.stdoutText() + completed.stderrText()).getBytes(StandardCharsets.UTF_8);
        var cut = combined.length > Message.MAX_LOG_BYTES;
        var kept = cut
                   ? Arrays.copyOfRange(combined, combined.length - Message.MAX_LOG_BYTES, combined.length)
                   : combined;
        var text = LogFiles.lastLines(new String(kept, StandardCharsets.UTF_8), cut, lines);

        return LogFiles.LogText.logText(text.text(),
                                        text.truncated() || completed.truncated());
    }

    private static Message chunk(LogFiles.LogText text, LogSource source) {
        return new Message.LogChunk(text.text(), text.truncated(), source);
    }
}
