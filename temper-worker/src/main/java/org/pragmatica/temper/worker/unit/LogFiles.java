package org.pragmatica.temper.worker.unit;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.worker.WorkerError;

import static org.pragmatica.lang.Result.unitResult;


/// The unit `log` file: capped to its last bytes at exit (m0-design §7.4, 200 MiB [P]) and tailed for `LogTail`
/// by reading backwards (m0-design §7.8). Only the tail of a file is ever read into memory.
public sealed interface LogFiles {
    long CAP_BYTES = 200L * 1024 * 1024;
    /// `log.tail`: the last 1 MiB of `log`, the small evidence set's log (m1-design §3.2).
    long TAIL_FILE_BYTES = 1024L * 1024;

    /// Keeps only the last `cap` bytes of `file` (copied to a sibling, then renamed over it atomically).
    static Result<Unit> keepTail(Path file, long cap) {
        return FileOps.size(file)
                      .flatMap(size -> cappedTo(file, size, cap))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("cap the log " + file, origin));
    }

    /// Writes the last `cap` bytes of `source` to `target` (through a sibling renamed atomically); `source` is not
    /// changed.
    static Result<Unit> copyTail(Path source, Path target, long cap) {
        var partial = target.resolveSibling(target.getFileName() + ".tmp");

        return FileOps.size(source)
                      .flatMap(size -> tailCopied(source,
                                                  partial,
                                                  Math.max(0, size - cap),
                                                  Math.min(size, cap)))
                      .flatMap(_ -> FileOps.moveAtomic(partial, target))
                      .mapToUnit()
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("write the log tail " + target, origin));
    }

    private static Result<Unit> tailCopied(Path source, Path target, long from, long length) {
        return opened(source, StandardOpenOption.READ).flatMap(channel -> closing(channel,
                                                                                  copiedTo(channel, target, from, length)));
    }

    /// The last `lines` lines of `file`, reading at most `maxBytes` from its end.
    static Result<LogText> tail(Path file, int lines, int maxBytes) {
        return FileOps.size(file)
                      .flatMap(size -> lastBytes(file, size, maxBytes))
                      .map(read -> lastLines(new String(read.bytes(),
                                                        StandardCharsets.UTF_8),
                                             read.cutAtStart(),
                                             lines))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("tail the log " + file, origin));
    }

    /// The last `lines` lines of `text`. When `cutAtStart`, the text began mid-file, so its first (possibly
    /// partial) line is dropped and the result is marked truncated; it is also truncated when lines were left out.
    static LogText lastLines(String text, boolean cutAtStart, int lines) {
        var body = cutAtStart
                   ? text.substring(text.indexOf('\n') + 1)
                   : text;
        var start = startOfLastLines(body, Math.max(0, lines));

        return LogText.logText(body.substring(start), cutAtStart || start > 0);
    }

    /// The index where the last `lines` lines of `body` begin (a trailing newline does not start a line).
    private static int startOfLastLines(String body, int lines) {
        var end = body.endsWith("\n")
                  ? body.length() - 1
                  : body.length();

        return lines == 0
               ? body.length()
               : afterNewlines(body, end, lines);
    }

    /// Walks back from `index` over `remaining` newlines; the position just after the last one counted, or 0 when
    /// the text has fewer lines.
    private static int afterNewlines(String body, int index, int remaining) {
        var newline = body.lastIndexOf('\n', index - 1);

        return remaining == 1 || newline == -1
               ? newline + 1
               : afterNewlines(body, newline, remaining - 1);
    }

    private static Result<Unit> cappedTo(Path file, long size, long cap) {
        return size <= cap
               ? unitResult()
               : tailKept(file, size - cap, cap);
    }

    private static Result<TailBytes> lastBytes(Path file, long size, int maxBytes) {
        var length = (int) Math.min(size, maxBytes);

        return opened(file, StandardOpenOption.READ).flatMap(channel -> closing(channel,
                                                                                mapped(channel, size - length, length)))
                     .map(bytes -> TailBytes.tailBytes(bytes, size > length));
    }

    private static Result<byte[]> mapped(FileChannel channel, long from, int length) {
        var bytes = new byte[length];

        return Result.lift(Causes::fromThrowable,
                           () -> channel.map(FileChannel.MapMode.READ_ONLY, from, length))
                     .map(buffer -> copiedOut(buffer, bytes));
    }

    private static byte[] copiedOut(ByteBuffer buffer, byte[] bytes) {
        buffer.get(bytes);

        return bytes;
    }

    private static Result<Unit> tailKept(Path file, long from, long length) {
        var kept = file.resolveSibling(file.getFileName() + ".cap");

        return opened(file, StandardOpenOption.READ).flatMap(source -> closing(source,
                                                                               copiedTo(source, kept, from, length)))
                     .flatMap(_ -> FileOps.moveAtomic(kept, file))
                     .mapToUnit();
    }

    private static Result<Unit> copiedTo(FileChannel source, Path kept, long from, long length) {
        return opened(kept, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).flatMap(target -> closing(target,
                                                                                                                                                 transferred(source,
                                                                                                                                                             target,
                                                                                                                                                             from,
                                                                                                                                                             length)));
    }

    /// `transferTo` may move fewer bytes than asked; continue until `remaining` is zero. A zero-byte transfer
    /// (the source shrank) ends the copy rather than spinning.
    private static Result<Unit> transferred(FileChannel source, FileChannel target, long position, long remaining) {
        return remaining <= 0
               ? unitResult()
               : Result.lift(Causes::fromThrowable,
                             () -> source.transferTo(position, remaining, target))
                       .flatMap(moved -> continued(source, target, position, remaining, moved));
    }

    private static Result<Unit> continued(FileChannel source,
                                          FileChannel target,
                                          long position,
                                          long remaining,
                                          long moved) {
        return moved <= 0
               ? unitResult()
               : transferred(source, target, position + moved, remaining - moved);
    }

    private static Result<FileChannel> opened(Path file, StandardOpenOption... options) {
        return Result.lift(Causes::fromThrowable, () -> FileChannel.open(file, options));
    }

    /// Closes `channel` after `result` was computed; a failed close fails an otherwise successful result.
    private static <T> Result<T> closing(FileChannel channel, Result<T> result) {
        return Result.lift(Causes::fromThrowable, channel::close).flatMap(_ -> result);
    }

    /// Raw bytes from the end of a file; `cutAtStart` when the file has more before them.
    record TailBytes(byte[] bytes, boolean cutAtStart) {
        static TailBytes tailBytes(byte[] bytes, boolean cutAtStart) {
            return new TailBytes(bytes, cutAtStart);
        }
    }

    /// A log tail and whether anything before it was left out.
    record LogText(String text, boolean truncated) {
        public static LogText logText(String text, boolean truncated) {
            return new LogText(text, truncated);
        }
    }

    record unused() implements LogFiles {}
}
