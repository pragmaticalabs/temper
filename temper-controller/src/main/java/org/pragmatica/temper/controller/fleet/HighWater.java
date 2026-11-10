package org.pragmatica.temper.controller.fleet;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// The epoch's high-water file `<stateDir>/fleet-hwm` = `<epoch> <max seq>`, rewritten by atomic
/// rename after each committed fleet insert, and at least once a minute ([#refresh]).
///
/// The last mark is also kept in memory ([#mark]), written or not: the running checks ([TemperEpoch#verify]) compare
/// the database with it, so a restore under a running controller is detected even when the file cannot be written.
/// Within an epoch the mark only moves forward: a lower seq never overwrites it ([#advance] ignores it); only [#force]
/// (start, a new epoch) sets it whatever it held. An insert compares its seqs with the mark read before it
/// ([Mark#behind], [FleetWriter]).
///
/// FER on a failed write: logged, and the file stays behind the database, which is normal (a crash between
/// commit and rename). What it costs: a restore while the controller is down, to a point between the file's seq and the
/// true head, is not detected by the file. [#force] and [#refresh] answer the failure, so that a file that cannot be
/// written at start rotates the epoch and the `selfcheck:highwater` source goes stale ([Fleet]).
public final class HighWater {
    private static final Logger log = LoggerFactory.getLogger(HighWater.class);

    private final Path file;
    private final Object lock = new Object();
    private Mark mark = new Mark("", -1);

    /// One line's content.
    public record Mark(String epoch, long seq) {
        static Option<Mark> parse(String text) {
            var parts = text.trim().split(" ");

            return parts.length == 2
                   ? Result.lift(Causes::fromThrowable,
                                 () -> new Mark(parts[0],
                                                Long.parseLong(parts[1])))
                           .option()
                   : Option.none();
        }

        String line() {
            return epoch + " " + seq + "\n";
        }

        /// Whether `seq`, committed by an insert that started after this mark was read, is at or below it (FIX-5): every
        /// seq handed out after the read is above every committed one, the mark included, unless a restore set the
        /// sequence back. So it is a seq used twice: the restore lost the mark's row (or more) and the insert reused
        /// its seq.
        public boolean behind(long seq) {
            return seq <= this.seq;
        }
    }

    private HighWater(Path file) {
        this.file = file;
    }

    public static HighWater highWater(Path stateDir) {
        return new HighWater(stateDir.resolve("fleet-hwm"));
    }

    /// The file's mark; none when it is absent or does not parse (then it is rewritten from the database).
    public Option<Mark> read() {
        return Files.exists(file)
               ? Result.lift(Causes::fromThrowable,
                             () -> Files.readString(file, StandardCharsets.US_ASCII))
                       .option()
                       .flatMap(Mark::parse)
               : Option.none();
    }

    /// The last mark, in memory (none set yet: epoch "" and seq -1).
    public Mark mark() {
        synchronized (lock) {
            return mark;
        }
    }

    /// Sets and writes `mark` whatever the mark held (at start, and when the epoch changes); the write's outcome.
    public Result<Unit> force(Mark mark) {
        synchronized (lock) {
            this.mark = mark;

            return store(mark);
        }
    }

    /// Moves the mark of the current epoch to `seq` and writes it, unless the mark is already at `seq` or above.
    /// FER on a failed write: logged by [#store]; the minute's [#refresh] writes again, and its failures make
    /// `selfcheck:highwater` stale.
    public Unit advance(long seq) {
        synchronized (lock) {
            return seq > mark.seq()
                   ? store(next(seq)).or(unit())
                   : unit();
        }
    }

    /// Rewrites the file with the mark, moved to `seq` when that is above it (the minute's check, after the database
    /// was found not behind the mark); the write's outcome.
    public Result<Unit> refresh(long seq) {
        synchronized (lock) {
            return store(next(Math.max(seq, mark.seq())));
        }
    }

    private Mark next(long seq) {
        mark = new Mark(mark.epoch(), seq);

        return mark;
    }

    private Result<Unit> store(Mark mark) {
        return replace(mark).onFailure(cause -> log.warn("fleet high-water file {} not written: {}",
                                                         file,
                                                         cause.message()))
                      .mapToUnit();
    }

    /// The new content in a sibling temp file, then an atomic rename over the file.
    private Result<Path> replace(Mark mark) {
        var temp = file.resolveSibling("fleet-hwm.tmp");

        return Result.lift(Causes::fromThrowable,
                           () -> Files.createDirectories(file.getParent()))
                     .flatMap(_ -> Result.lift(Causes::fromThrowable,
                                               () -> Files.writeString(temp,
                                                                       mark.line(),
                                                                       StandardCharsets.US_ASCII)))
                     .flatMap(_ -> Result.lift(Causes::fromThrowable,
                                               () -> Files.move(temp,
                                                                file,
                                                                StandardCopyOption.ATOMIC_MOVE,
                                                                StandardCopyOption.REPLACE_EXISTING)));
    }
}
