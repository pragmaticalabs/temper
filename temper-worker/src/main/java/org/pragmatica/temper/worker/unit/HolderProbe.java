package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.temper.domain.hold.GrantLine;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.facts.ProcParsers;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// A hold unit's bb-hold holder as the worker observes it (m1-design §1.4). Reads `/proc` and the unit's
/// `bbhold.pid`; never writes anything outside the unit's own files.
///
/// **Identity:** `/proc/<pid>` exists, its stat start time equals the recorded start ticks, and its cmdline
/// contains `bb-hold` and the unit's container name (bb-hold re-execs itself under the same pid as
/// `bash <bb-hold> __hold <owner> <prio> <name> …`). A recycled pid fails the start-time check; a zombie has an
/// empty cmdline; both read as `DEAD`.
public interface HolderProbe {
    String BB_HOLD = "bb-hold";
    /// `none()` when the unit is not a hold unit (neither `bbhold.pid` nor `bbhold.err` exists). A hold unit whose
    /// pid was never recorded (a worker died between the spawn and the write) cannot be identified and is `DEAD`.
    /// Alive: `HELD` once `bbhold.err` holds the `granted` line, `WAITING` before.
    Result<Option<HolderStatus>> status(UnitRef ref);
    /// Sends SIGTERM to the identified holder; `false` when no live process has the recorded identity (nothing was
    /// signalled). The identity is checked first, so a recycled pid is never signalled.
    Result<Boolean> terminate(UnitRef ref);

    /// Sends SIGTERM to `pid`; `false` when no such process exists (nothing was signalled).
    @FunctionalInterface
    interface Signaller {
        boolean signal(long pid);

        /// `ProcessHandle.destroy`: SIGTERM, which bb-hold traps to dequeue its ticket before it exits.
        static Signaller sigterm() {
            return Signaller::destroyed;
        }

        private static boolean destroyed(long pid) {
            return Option.from(ProcessHandle.of(pid))
                         .map(ProcessHandle::destroy)
                         .or(false);
        }
    }

    /// The production probe over `procRoot` (`/proc`), signalling with SIGTERM ([Signaller#sigterm]).
    static HolderProbe procHolderProbe(Path procRoot, UnitFiles files) {
        return procHolderProbe(procRoot, files, Signaller.sigterm());
    }

    static HolderProbe procHolderProbe(Path procRoot, UnitFiles files, Signaller signaller) {
        return new ProcHolders(procRoot, files, signaller);
    }

    /// [HolderProbe] over a `/proc` tree and the unit files.
    record ProcHolders(Path procRoot, UnitFiles files, Signaller signaller) implements HolderProbe {
        @Override
        public Result<Option<HolderStatus>> status(UnitRef ref) {
            return isHoldUnit(ref)
                   ? holder(ref).flatMap(holder -> statusOf(ref, holder))
                           .map(Option::some)
                   : success(none());
        }

        @Override
        public Result<Boolean> terminate(UnitRef ref) {
            return holder(ref).map(holder -> holder.filter(alive -> isAlive(ref, alive)))
                         .map(this::signalled);
        }

        private boolean isHoldUnit(UnitRef ref) {
            return files.exists(ref, UnitFile.BBHOLD_PID) || files.exists(ref, UnitFile.BBHOLD_ERR);
        }

        private Result<Option<HolderRecord>> holder(UnitRef ref) {
            return files.text(ref, UnitFile.BBHOLD_PID)
                        .flatMap(text -> Values.optional(text.map(String::trim),
                                                         raw -> parsed(files.path(ref, UnitFile.BBHOLD_PID),
                                                                       raw)));
        }

        private static Result<HolderRecord> parsed(Path file, String raw) {
            return HolderRecord.holderRecord(raw).mapError(origin -> WorkerError.CorruptState.FACTORY.apply(file, origin));
        }

        private Result<HolderStatus> statusOf(UnitRef ref, Option<HolderRecord> holder) {
            return holder.filter(alive -> isAlive(ref, alive))
                         .isPresent()
                   ? granted(ref).map(ProcHolders::liveStatus)
                   : success(HolderStatus.DEAD);
        }

        private Result<Boolean> granted(UnitRef ref) {
            return files.text(ref, UnitFile.BBHOLD_ERR)
                        .map(text -> text.flatMap(GrantLine::ticket)
                                         .isPresent());
        }

        private static HolderStatus liveStatus(boolean granted) {
            return granted
                   ? HolderStatus.HELD
                   : HolderStatus.WAITING;
        }

        private boolean isAlive(UnitRef ref, HolderRecord holder) {
            var process = procRoot.resolve(Long.toString(holder.pid()));

            return holder.startedAt(startTicks(process)) && commandLine(process).filter(cmdline -> isHolderOf(cmdline,
                                                                                                              ref))
                                                                       .isPresent();
        }

        /// FER (degrade forward): an unreadable `/proc/<pid>/stat` means the process is gone (or never was ours to
        /// read), so it has no start time and the holder reads as dead; the callers act on `DEAD` conservatively (no
        /// grant: requeue; a running container: void only after two such readings).
        private static Option<Long> startTicks(Path process) {
            return FileOps.readString(process.resolve("stat"))
                          .option()
                          .flatMap(ProcParsers::startTicks);
        }

        /// FER (degrade forward): an unreadable cmdline is a process that is gone; see [#startTicks].
        private static Option<String> commandLine(Path process) {
            return FileOps.readBytes(process.resolve("cmdline"))
                          .option()
                          .map(ProcParsers::cmdline);
        }

        private static boolean isHolderOf(String cmdline, UnitRef ref) {
            return cmdline.contains(BB_HOLD) && cmdline.contains(ref.containerName());
        }

        private boolean signalled(Option<HolderRecord> holder) {
            return holder.map(alive -> signaller.signal(alive.pid()))
                         .or(false);
        }
    }
}
