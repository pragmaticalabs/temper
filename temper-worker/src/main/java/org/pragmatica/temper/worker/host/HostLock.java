package org.pragmatica.temper.worker.host;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.pragmatica.lang.Functions.ThrowingFn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.worker.WorkerError;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// `~/.temper/worker.lock` (m0-design §7.2): one worker per host. A predecessor whose ssh dropped may still be
/// alive (SIGHUP not yet processed); waiting for its OS-level lock keeps it from racing this worker's
/// `docker run`. The lock dies with the holding process, so a crashed worker never wedges the host.
public record HostLock(FileChannel channel, FileLock lock) {
    /// Exit code when the lock stays held past the wait; the controller backs off and retries.
    public static final int EXIT_LOCKED = 75;
    static final long POLL_MILLIS = 250;

    /// Takes the lock, retrying every 250 ms for up to `wait`; [WorkerError.LockTimeout] when it stays held.
    public static Result<HostLock> hostLock(Path lockFile, TimeSpan wait) {
        var deadline = System.nanoTime() + wait.nanos();

        return FileOps.createDirectories(lockFile.getParent())
                      .flatMap(_ -> opened(lockFile))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("open " + lockFile, origin))
                      .mapWith(channel -> lockedOrClosed(lockFile, channel, deadline),
                               HostLock::new);
    }

    /// Releases the lock and closes the channel; the close runs even when the release fails (closing the channel
    /// releases the lock too). The OS releases both when the process exits.
    public Result<Unit> release() {
        return Result.all(Result.lift(Causes::fromThrowable, lock::release),
                          Result.lift(Causes::fromThrowable, channel::close))
                     .map((_, _) -> unit());
    }

    /// Any failure to take the lock (held past the wait, `tryLock` failing, an interrupted wait) closes the channel.
    private static Result<FileLock> lockedOrClosed(Path lockFile, FileChannel channel, long deadline) {
        return lockWithin(lockFile, channel, deadline).onFailureRun(() -> closed(channel));
    }

    private static Result<FileChannel> opened(Path lockFile) {
        return Result.lift(Causes::fromThrowable,
                           () -> FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
    }

    private static Result<FileLock> lockWithin(Path lockFile, FileChannel channel, long deadline) {
        ThrowingFn0<FileLock> tryLock = channel::tryLock;

        return Result.lift(thrown -> WorkerError.Io.FACTORY.apply("lock " + lockFile,
                                                                  Causes.fromThrowable(thrown)),
                           tryLock)
                     .flatMap(lock -> heldOrRetried(lockFile,
                                                    channel,
                                                    option(lock),
                                                    deadline));
    }

    private static Result<FileLock> heldOrRetried(Path lockFile,
                                                  FileChannel channel,
                                                  Option<FileLock> lock,
                                                  long deadline) {
        return lock.map(Result::success)
                   .or(() -> retriedOrTimedOut(lockFile, channel, deadline));
    }

    private static Result<FileLock> retriedOrTimedOut(Path lockFile, FileChannel channel, long deadline) {
        return System.nanoTime() >= deadline
               ? WorkerError.LockTimeout.FACTORY.apply(lockFile).<FileLock> result()
               : Result.lift(Causes::fromThrowable,
                             () -> Thread.sleep(POLL_MILLIS))
                       .flatMap(_ -> lockWithin(lockFile, channel, deadline));
    }

    /// FER (degrade forward): closing the channel of a lock we did not get only frees a descriptor; the process is
    /// about to exit (75 or 1) anyway, which frees it too.
    private static Unit closed(FileChannel channel) {
        return Result.lift(Causes::fromThrowable, channel::close).or(unit());
    }
}
