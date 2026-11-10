package org.pragmatica.temper.controller.evidence;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;


/// File steps of a pulled tarball or dump (Zone C; every `IOException` is lifted, then named by
/// [EvidenceError.Io]): read the leading bytes, force a file another process wrote, hash it, and publish it by an
/// atomic rename followed by a forced directory, so a published file is complete and survives a crash. Every
/// opened channel or stream is closed after its operation, whatever the operation's outcome.
public sealed interface Tarballs {
    int GZIP_FIRST = 0x1f;
    int GZIP_SECOND = 0x8b;

    /// Whether the file starts with the gzip magic `1f 8b`.
    static Result<Boolean> isGzip(Path file) {
        return opened(file, StandardOpenOption.READ).flatMap(channel -> closing(channel,
                                                                                leadingMagic(channel)))
                     .mapError(cause -> EvidenceError.Io.FACTORY.apply("read " + file, cause));
    }

    /// Forces the file, hashes it, renames it over `target` and forces `target`'s directory; answers the sha256.
    static Result<String> published(Path partial, Path target) {
        return forced(partial).flatMap(_ -> sha256(partial))
                     .flatMap(sha -> renamedWith(partial, target, sha));
    }

    /// Forces the file, renames it over `target` and forces `target`'s directory (a dump needs no hash).
    static Result<Unit> moved(Path partial, Path target) {
        return forced(partial).flatMap(_ -> renamed(partial, target));
    }

    static Result<Long> size(Path file) {
        return FileOps.size(file).mapError(cause -> EvidenceError.Io.FACTORY.apply("size " + file, cause));
    }

    static Result<Path> directoriesFor(Path file) {
        var directory = file.getParent();

        return FileOps.createDirectories(directory).mapError(cause -> EvidenceError.Io.FACTORY.apply("create " + directory,
                                                                                                     cause));
    }

    private static Result<String> renamedWith(Path partial, Path target, String sha) {
        return renamed(partial, target).map(_ -> sha);
    }

    private static Result<Unit> renamed(Path partial, Path target) {
        var directory = target.getParent();

        return FileOps.moveAtomic(partial, target)
                      .flatMap(_ -> FileOps.forceDirectory(directory))
                      .mapError(cause -> EvidenceError.Io.FACTORY.apply("publish " + target, cause));
    }

    private static Result<Unit> forced(Path file) {
        return opened(file, StandardOpenOption.WRITE).flatMap(channel -> closing(channel,
                                                                                 Result.lift(Causes::fromThrowable,
                                                                                             () -> channel.force(true))))
                     .mapError(cause -> EvidenceError.Io.FACTORY.apply("force " + file, cause));
    }

    private static Result<String> sha256(Path file) {
        return Result.lift(Causes::fromThrowable,
                           () -> MessageDigest.getInstance("SHA-256"))
                     .flatMap(digest -> digested(file, digest))
                     .map(digest -> HexFormat.of().formatHex(digest.digest()))
                     .mapError(cause -> EvidenceError.Io.FACTORY.apply("hash " + file, cause));
    }

    /// Streams the file through `digest`.
    private static Result<MessageDigest> digested(Path file, MessageDigest digest) {
        return Result.lift(Causes::fromThrowable,
                           () -> Files.newInputStream(file))
                     .flatMap(in -> closing(in,
                                            transferred(in, digest)))
                     .map(_ -> digest);
    }

    private static Result<Long> transferred(InputStream in, MessageDigest digest) {
        return Result.lift(Causes::fromThrowable,
                           () -> in.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest)));
    }

    /// The first two bytes, read at position 0 (a positional read of a regular file returns them unless the file is
    /// shorter).
    private static Result<Boolean> leadingMagic(FileChannel channel) {
        var buffer = ByteBuffer.allocate(2);

        return Result.lift(Causes::fromThrowable,
                           () -> channel.read(buffer, 0))
                     .map(_ -> isMagic(buffer));
    }

    private static boolean isMagic(ByteBuffer buffer) {
        return buffer.position() == 2
               && Byte.toUnsignedInt(buffer.get(0)) == GZIP_FIRST
               && Byte.toUnsignedInt(buffer.get(1)) == GZIP_SECOND;
    }

    private static Result<FileChannel> opened(Path file, StandardOpenOption option) {
        return Result.lift(Causes::fromThrowable, () -> FileChannel.open(file, option));
    }

    /// Closes `resource` after `result` was computed; a failed close fails the step.
    private static <T> Result<T> closing(Closeable resource, Result<T> result) {
        return Result.lift(Causes::fromThrowable, resource::close).flatMap(_ -> result);
    }

    record unused() implements Tarballs {}
}
