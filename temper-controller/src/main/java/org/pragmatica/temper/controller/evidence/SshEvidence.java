package org.pragmatica.temper.controller.evidence;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.host.SshCommand;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.evidence.EvidenceRequest;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.store.api.StoredEvidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// [EvidencePuller] over a second ssh session (m1-design §3.2, Zone C). One pull:
///
/// 1. spawn `ssh … evidence <job> <unit> small|full` with stdout redirected to `<file>.partial` (stderr to
///    `<file>.stderr`, logged on failure);
/// 2. a size watch every [#SIZE_CHECK] kills the transfer once the file passes the kind's byte cap; the transfer is
///    killed at `timeout` too;
/// 3. the file must be within the cap, the exit code 0, and the content must start with the gzip magic;
/// 4. it is forced, hashed (sha256), renamed atomically over `<file>` and its directory forced.
///
/// A failed attempt deletes its partial and stderr files (BER: the evidence store never keeps half a tarball), and
/// the pull is retried once after `retryDelay`. Terminal failures (`worker-launch` refused the request or found no
/// unit directory, or the cap was exceeded) are not retried: the same request gets the same answer.
public record SshEvidence(ProcessRunner runner,
                          EvidenceLayout layout,
                          long maxSmallBytes,
                          long maxFullBytes,
                          TimeSpan timeout,
                          Retry retry) implements EvidencePuller {
    private static final Logger log = LoggerFactory.getLogger(SshEvidence.class);
    public static final TimeSpan TIMEOUT = timeSpan(10).minutes();
    public static final TimeSpan RETRY_DELAY = timeSpan(30).seconds();
    public static final TimeSpan SIZE_CHECK = timeSpan(1).seconds();
    private static final String STDERR = ".stderr";

    public static SshEvidence sshEvidence(ProcessRunner runner, ControllerConfig.Evidence config) {
        return sshEvidence(runner, config, TIMEOUT, RETRY_DELAY);
    }

    /// With explicit timing (tests use short ones).
    public static SshEvidence sshEvidence(ProcessRunner runner,
                                          ControllerConfig.Evidence config,
                                          TimeSpan timeout,
                                          TimeSpan retryDelay) {
        return new SshEvidence(runner,
                               EvidenceLayout.evidenceLayout(config.root()),
                               config.maxSmallBytes(),
                               config.maxFullBytes(),
                               timeout,
                               Retry.retry().attempts(2).strategy(Retry.BackoffStrategy.fixed().interval(retryDelay)));
    }

    @Override
    public Promise<StoredEvidence> pull(ControllerConfig.Host host, UnitRef ref, int unitAttempt, EvidenceKind kind) {
        return retry.execute(() -> attempt(host, ref, unitAttempt, kind));
    }

    /// One attempt; a failure removes what it wrote.
    Promise<StoredEvidence> attempt(ControllerConfig.Host host, UnitRef ref, int unitAttempt, EvidenceKind kind) {
        var target = layout.tarball(ref, unitAttempt, kind);

        return pulled(host, ref, kind, target).fold(result -> cleaned(target, result));
    }

    private Promise<StoredEvidence> pulled(ControllerConfig.Host host, UnitRef ref, EvidenceKind kind, Path target) {
        return Tarballs.directoriesFor(target)
                       .async()
                       .flatMap(_ -> transferred(host, ref, kind, target))
                       .flatMap(rc -> checkedAndStored(target,
                                                       cap(kind),
                                                       rc).async());
    }

    private static Result<StoredEvidence> checkedAndStored(Path target, long cap, int rc) {
        return verified(target, cap, rc).flatMap(size -> stored(target, size));
    }

    /// The failed attempt's files are gone before its result is seen, so a caller never observes half a pull.
    private static Promise<StoredEvidence> cleaned(Path target, Result<StoredEvidence> result) {
        return result.onFailure(_ -> discarded(target))
                     .async();
    }

    private long cap(EvidenceKind kind) {
        return switch (kind) {
            case SMALL -> maxSmallBytes;
            case FULL -> maxFullBytes;
        };
    }

    // ---- transfer ----------------------------------------------------------------------------------------------
    /// The exit code of the ssh process; the size watch and the timeout kill it.
    private Promise<Integer> transferred(ControllerConfig.Host host, UnitRef ref, EvidenceKind kind, Path target) {
        return command(host, ref, kind, target).flatMap(runner::spawn)
                      .async()
                      .flatMap(process -> watched(process,
                                                  EvidenceLayout.partial(target),
                                                  cap(kind)))
                      .mapError(cause -> transferFailed(target, cause));
    }

    private Result<Command> command(ControllerConfig.Host host, UnitRef ref, EvidenceKind kind, Path target) {
        return Command.command(SshCommand.evidence(host,
                                                   EvidenceRequest.evidenceRequest(ref, kind)),
                               Map.of(),
                               timeout)
                      .map(Command::inheritingEnvironment)
                      .map(command -> command.stdoutTo(EvidenceLayout.partial(target)))
                      .map(command -> command.stderrTo(stderr(target)));
    }

    private Promise<Integer> watched(Spawned process, Path partial, long cap) {
        var watch = SharedScheduler.scheduleAtFixedRate(() -> killIfOver(process, partial, cap), SIZE_CHECK);
        // Both may run late: the kill is a best-effort signal to an abandoned process, and a size check that runs
        // once more after the exit finds nothing to kill.
        return process.exit()
                      .timeout(timeout)
                      .onFailure(_ -> process.kill())
                      .onResultRun(() -> stopWatch(watch));
    }

    private static Unit killIfOver(Spawned process, Path partial, long cap) {
        return isOver(partial, cap)
               ? process.kill()
               : unit();
    }

    /// An unreadable size (the file not created yet) is not over the cap.
    private static boolean isOver(Path partial, long cap) {
        return FileOps.size(partial)
                      .map(size -> size > cap)
                      .or(false);
    }

    private static Unit stopWatch(ScheduledFuture<?> watch) {
        watch.cancel(false);

        return unit();
    }

    // ---- verify and store --------------------------------------------------------------------------------------
    /// Answers the size. The cap is checked first: a transfer killed by the size watch also exits non-zero.
    private static Result<Long> verified(Path target, long cap, int rc) {
        var partial = EvidenceLayout.partial(target);

        return Tarballs.size(partial)
                       .filter(_ -> EvidenceError.CapExceeded.FACTORY.apply(target.toString(),
                                                                            cap),
                               size -> size <= cap)
                       .flatMap(size -> verifiedContent(partial, target, rc, size));
    }

    private static Result<Long> verifiedContent(Path partial, Path target, int rc, long size) {
        return exitedZero(target, rc).flatMap(_ -> gzip(partial, target))
                         .map(_ -> size);
    }

    private static Result<Unit> exitedZero(Path target, int rc) {
        return rc == 0
               ? success(unit())
               : refused(target, rc);
    }

    private static Result<Unit> refused(Path target, int rc) {
        log.warn("evidence pull of {} exited {}; ssh stderr: {}", target, rc, stderrTail(target));

        return EvidenceError.Refused.FACTORY.apply(target.toString(),
                                                   rc)
                                            .result();
    }

    private static Result<Unit> gzip(Path partial, Path target) {
        return Tarballs.isGzip(partial)
                       .filter(_ -> EvidenceError.NotGzip.FACTORY.apply(target.toString()),
                               isGzip -> isGzip)
                       .map(_ -> unit());
    }

    private static Result<StoredEvidence> stored(Path target, long size) {
        return Tarballs.published(EvidenceLayout.partial(target),
                                  target)
                       .onSuccess(_ -> deleted(stderr(target)))
                       .map(sha -> StoredEvidence.storedEvidence(target, size, sha));
    }

    /// BER (compensate by inverse): the attempt's own files are removed, so only a published tarball stays.
    private static Unit discarded(Path target) {
        deleted(EvidenceLayout.partial(target));

        return deleted(stderr(target));
    }

    /// FER: a file that cannot be removed is logged and left; the next attempt truncates it, and only a renamed
    /// tarball is ever named by an `evidence` row.
    private static Unit deleted(Path file) {
        return FileOps.deleteIfExists(file)
                      .onFailure(cause -> log.warn("cannot remove {}: {}",
                                                   file,
                                                   cause.message()))
                      .mapToUnit()
                      .or(unit());
    }

    private static Path stderr(Path target) {
        return target.resolveSibling(target.getFileName() + STDERR);
    }

    private static String stderrTail(Path target) {
        return FileOps.readString(stderr(target))
                      .map(String::strip)
                      .map(SshEvidence::tail)
                      .or("");
    }

    private static String tail(String text) {
        return text.substring(Math.max(0, text.length() - 2048));
    }

    private static EvidenceError transferFailed(Path target, Cause cause) {
        return switch (cause) {
            case EvidenceError error -> error;
            default -> EvidenceError.TransferFailed.FACTORY.apply(target.toString(), cause);
        };
    }
}
