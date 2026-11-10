package org.pragmatica.kiln.service.conflict;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// The conflict watch's events.log (temper #100): one line per new or cleared prediction, appended, never rewritten.
public interface EventsLog {
    /// The replay window at start: the last this-many lines decide what is announced. An announcement older than the
    /// window is announced again once after a restart; nothing is lost.
    int TAIL = 5000;
    /// The last [#TAIL] lines; none when the file does not exist yet.
    Promise<List<String>> tail();
    /// Appends the lines, each with its newline, creating the file and its directory when absent.
    Promise<Unit> append(List<String> lines);

    static EventsLog file(Path path) {
        return new FileLog(path);
    }

    /// The events.log at `path`.
    ///
    /// A kiln killed mid-append can leave a torn last line, possibly cut inside a multi-byte character (PR #108 review
    /// F5). So the tail is decoded REPLACING malformed bytes (a strict decoder would fail every step until an operator
    /// edits the file), and an append after a line without its newline writes the newline first, so the new line never
    /// glues onto the fragment and is replayed whole. The tail is read from the end in blocks, never the whole file.
    record FileLog(Path path) implements EventsLog {
        private static final int BLOCK = 64 * 1024;

        @Override
        public Promise<List<String>> tail() {
            return Files.exists(path)
                   ? lastLines(path, TAIL).async()
                   : Promise.success(List.of());
        }

        /// The last `count` lines of the file: blocks are read backwards from the end until `count` + 1 newlines (or the
        /// start of the file) are behind the read position, then decoded once, replacing malformed bytes.
        static Result<List<String>> lastLines(Path path, int count) {
            return Result.lift(Causes::fromThrowable,
                               () -> {
                                   try (var file = new RandomAccessFile(path.toFile(), "r")) {
                                   var end = file.length();
                                   var start = end;
                                   var newlines = 0L;

                                   while (start > 0 && newlines <= count) {
                                   var block = new byte[(int) Math.min(BLOCK, start)];

                                   start -= block.length;
                                   file.seek(start);
                                   file.readFully(block);
                                   newlines += newlines(block);
                               }

                                   var bytes = new byte[(int)(end - start)];

                                   file.seek(start);
                                   file.readFully(bytes);

                                   return last(new String(bytes, StandardCharsets.UTF_8).lines().toList(),
                                               count);
                               }
                               });
        }

        private static long newlines(byte[] block) {
            return IntStream.range(0, block.length)
                            .filter(at -> block[at] == '\n')
                            .count();
        }

        /// The last `count` lines. When the read began mid-file, `count` + 1 newlines are behind it, so its first, possibly
        /// partial, line is never among them.
        private static List<String> last(List<String> lines, int count) {
            return lines.stream()
                        .skip(Math.max(0,
                                       lines.size() - count))
                        .toList();
        }

        @Override
        public Promise<Unit> append(List<String> lines) {
            return lines.isEmpty()
                   ? Promise.unitPromise()
                   : Promise.lift(Causes::fromThrowable,
                                  () -> Files.createDirectories(path.toAbsolutePath().getParent()))
                            .flatMap(_ -> separator(path).async())
                            .flatMap(separator -> Promise.lift(Causes::fromThrowable,
                                                               () -> Files.writeString(path,
                                                                                       separator + String.join("\n",
                                                                                                               lines)
                                                                                      + "\n",
                                                                                       StandardCharsets.UTF_8,
                                                                                       StandardOpenOption.CREATE,
                                                                                       StandardOpenOption.APPEND)))
                            .mapToUnit();
        }

        /// A newline when the file's last byte is not one (a torn line), so the next line starts on its own.
        static Result<String> separator(Path path) {
            return Files.exists(path)
                   ? Result.lift(Causes::fromThrowable,
                                 () -> {
                                     try (var file = new RandomAccessFile(path.toFile(), "r")) {
                                     if (file.length() == 0) {
                                     return "";
                                 }

                                     file.seek(file.length() - 1);

                                     return file.read() == '\n'
                                            ? ""
                                            : "\n";
                                 }
                                 })
                   : Result.success("");
        }
    }
}
