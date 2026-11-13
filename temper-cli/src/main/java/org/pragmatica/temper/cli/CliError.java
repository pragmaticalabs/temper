package org.pragmatica.temper.cli;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.state.JobState;


/// Every way a CLI invocation can fail, each mapped to its exit code (spec §8.1 plus the Q-15 rulings). Exit
/// codes come from the shared [ExitCode] enum only (m0-design §8).
///
/// Mappings marked [G] have no row in §8.1 and are guesses:
/// - [NotFound] → 64: the caller named a job that does not exist, which is an argument error.
/// - [UnexpectedStatus] and [BadPayload] → 69: a controller that answers outside its contract is as unusable
///   as an absent one (`EX_UNAVAILABLE`).
/// - [General#OUTPUT_CLOSED] → 5: stdout went away, so nobody can read a line any more.
/// - [Conflict] → 2 (REFUSED): m1-design §6 marks it [G] as well; so is [ForeignCursor], the digest's 409.
/// - [Unwritable] → 5: as [General#OUTPUT_CLOSED], the reader's record of what it saw could not be kept.
/// - [General#FORBIDDEN] → 77: the controller's 403 to the read token on a write route (D3) is a token without the
///   permission, as 77 (`EX_NOPERM`) means for the 401.
///
/// The task group (m3-design §11.2): a lease the controller refused with `409 lease-lost` is [LeaseLost] (12); a claim
/// of a task that is not READY, or a maintainer command on one that is not HELD (`409`), is [NotReady] (9); a request the
/// controller parsed but refused (`422`, such as `bad-next` or `base-unknown`) is [TaskRefused] (2); a lease file the
/// CLI does not have is a usage error (64); a `--checkout` clone that failed after the claim is [CheckoutFailed] (5),
/// the lease then held and its file written, so the worker can release it [G].
///
/// `alarm-check`'s sink failures ([SinkFailed], [SinkRejected]) are 2 (ALARM_UNDELIVERED): the conditions were
/// evaluated but not delivered, and the non-zero exit marks the systemd unit failed, a second, local signal.
public sealed interface CliError extends Cause {
    ExitCode exitCode();

    enum General implements CliError {
        UNAUTHORIZED("Controller rejected the bearer token (unauthorized)"),
        FORBIDDEN("Controller refused this operation for a read-only token (forbidden)"),
        HOST_UNAVAILABLE("The job's host is unavailable (HTTP 503)"),
        OUTPUT_CLOSED("stdout is closed");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
        @Override
        public ExitCode exitCode() {
            return switch (this) {
                case UNAUTHORIZED, FORBIDDEN -> ExitCode.UNAUTHORIZED;
                case HOST_UNAVAILABLE, OUTPUT_CLOSED -> ExitCode.ERROR;
            };
        }
    }

    /// D-6: the controller's `Temper-Version` names another build than this CLI's, both judged (a `-dirty` build is
    /// judged): a CLI of another build may misdecode the controller's answers (DEPLOY F2), so it refuses before
    /// decoding. No override exists; install the CLI built from the controller's commit (the deploy runbook, step 8).
    record VersionSkew(String cliVersion, String controllerVersion, String message) implements CliError {
        public static final Fn2<VersionSkew, String, String> FACTORY = Causes.forTwoValues("CLI version %s does not match controller version %s; install the CLI built from the controller's commit",
                                                                                           VersionSkew::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.VERSION_SKEW;
        }
    }

    /// The CLI's own build version cannot be read from its jar (a packaging defect); the read's failure is the origin.
    /// Exit 5 [G]: nothing about the controller is known yet.
    @SuppressWarnings("JBCT-CAUSE-04")
    record NoVersion(Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn1<NoVersion, Cause> FACTORY = Causes.forOneValue("Cannot read this CLI's build version",
                                                                               NoVersion::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.ERROR;
        }
    }

    /// Bad command line or local configuration; usage is printed with it.
    record Usage(String detail, String message) implements CliError {
        public static final Fn1<Usage, String> FACTORY = Causes.forOneValue("%s", Usage::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.USAGE;
        }
    }

    /// A submit that the CLI's own parse of the request refused before sending it (the same domain factories the
    /// controller runs); the domain failure is the origin and is printed after it, on the same line.
    @SuppressWarnings("JBCT-CAUSE-04")
    record InvalidRequest(Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn1<InvalidRequest, Cause> FACTORY = Causes.forOneValue("Invalid submit request",
                                                                                    InvalidRequest::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.USAGE;
        }
    }

    /// A configured value (controller URL) that does not parse; the parser's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record BadSetting(String setting, String value, Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn3<BadSetting, String, String, Cause> FACTORY = Causes.forThreeValues("Invalid %s: %s",
                                                                                                   BadSetting::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.USAGE;
        }
    }

    /// A local file the CLI needs exists but cannot be read; the JDK's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unreadable(String path, Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn2<Unreadable, String, Cause> FACTORY = Causes.forTwoValues("Cannot read %s",
                                                                                         Unreadable::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.USAGE;
        }
    }

    /// A local file the CLI keeps (the digest cursor) could not be written; the JDK's failure is the origin. Exit 5,
    /// like [General#OUTPUT_CLOSED]: what was printed could not be recorded as read [G].
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unwritable(String path, Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn2<Unwritable, String, Cause> FACTORY = Causes.forTwoValues("Cannot write %s",
                                                                                         Unwritable::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.ERROR;
        }
    }

    /// The digest cursor file holds a cursor the controller does not recognise as its own (`409 CURSOR_FOREIGN`: another
    /// controller's event log, or this one before a restore); the controller's `Conflict` is the origin. Exit 2, as
    /// every 409 [G]. The file is left as it is: deleting it starts at the head.
    @SuppressWarnings("JBCT-CAUSE-04")
    record ForeignCursor(String path, Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn2<ForeignCursor, String, Cause> FACTORY = Causes.forTwoValues("Digest cursor in %s is not from this controller's event log; delete the file to start at the head",
                                                                                            ForeignCursor::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.REFUSED;
        }
    }

    /// The controller could not be reached (connect refused, timeout, connection dropped).
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unreachable(String target, Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn2<Unreachable, String, Cause> FACTORY = Causes.forTwoValues("Controller unreachable at %s",
                                                                                          Unreachable::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.UNREACHABLE;
        }
    }

    /// `404` for something other than a not-yet-terminal result: the job does not exist.
    record NotFound(String path, String message) implements CliError {
        public static final Fn1<NotFound, String> FACTORY = Causes.forOneValue("Not found: %s", NotFound::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.USAGE;
        }
    }

    /// `400`: the controller refused to parse the request; `error` is its `ErrorResponse` text.
    record Rejected(String error, String message) implements CliError {
        public static final Fn1<Rejected, String> FACTORY = Causes.forOneValue("Controller rejected the request: %s",
                                                                               Rejected::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.USAGE;
        }
    }

    /// `409` to `refusal add`: the code exists, and codes are never reused (m1-design §6); `error` is the
    /// controller's `ErrorResponse` text.
    record Conflict(String error, String message) implements CliError {
        public static final Fn1<Conflict, String> FACTORY = Causes.forOneValue("Controller refused the request: %s",
                                                                               Conflict::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.REFUSED;
        }
    }

    /// The controller answered a cancel with a live state (QUEUED/UNKNOWN/RUNNING): it did not take the cancel.
    record CancelIgnored(long job, JobState state, String message) implements CliError {
        public static final Fn2<CancelIgnored, Long, JobState> FACTORY = Causes.forTwoValues("Controller ignored the cancel of job %d (still %s)",
                                                                                             CancelIgnored::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.UNREACHABLE;
        }
    }

    /// A status outside the route's contract.
    record UnexpectedStatus(int status, String path, String message) implements CliError {
        public static final Fn2<UnexpectedStatus, Integer, String> FACTORY = Causes.forTwoValues("Unexpected HTTP %d from %s",
                                                                                                 UnexpectedStatus::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.UNREACHABLE;
        }
    }

    /// A controller answer that does not decode into the expected DTO; the decoder's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record BadPayload(String path, Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn2<BadPayload, String, Cause> FACTORY = Causes.forTwoValues("Unreadable controller answer from %s",
                                                                                         BadPayload::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.UNREACHABLE;
        }
    }

    /// The alarm sink (GitHub) could not be reached, or its answer did not decode; `request` is the method and path
    /// (never a header, so never the token), and the transport's or the decoder's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record SinkFailed(String request, Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn2<SinkFailed, String, Cause> FACTORY = Causes.forTwoValues("Alarm sink request %s failed",
                                                                                         SinkFailed::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.ALARM_UNDELIVERED;
        }
    }

    /// The alarm sink answered a status outside the request's contract (GitHub: bad credentials, no such repository,
    /// a rate limit).
    record SinkRejected(int status, String request, String message) implements CliError {
        public static final Fn2<SinkRejected, Integer, String> FACTORY = Causes.forTwoValues("Alarm sink answered HTTP %d to %s",
                                                                                             SinkRejected::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.ALARM_UNDELIVERED;
        }
    }

    /// `409 lease-lost` on a lease-token call: the epoch is gone (expired, revoked, or another claim's).
    record LeaseLost(String error, String message) implements CliError {
        public static final Fn1<LeaseLost, String> FACTORY = Causes.forOneValue("Controller refused the lease: %s",
                                                                                LeaseLost::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.LEASE_LOST;
        }
    }

    /// `409` to a claim (`not-ready`) or a maintainer command (`not-held`, a command no row admits).
    record NotReady(String error, String message) implements CliError {
        public static final Fn1<NotReady, String> FACTORY = Causes.forOneValue("Controller refused the request: %s",
                                                                               NotReady::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.PENDING;
        }
    }

    /// `422` to a task request: it parsed, and the task's state or the controller refused it.
    record TaskRefused(String error, String message) implements CliError {
        public static final Fn1<TaskRefused, String> FACTORY = Causes.forOneValue("Controller refused the request: %s",
                                                                                  TaskRefused::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.REFUSED;
        }
    }

    /// `task checkpoint` refused with a code of its own (`422 checkpoint-too-large`, `422 pushed-sha-not-on-branch`,
    /// `503` mirror unavailable): `exit` says which, so a lane script acts on the cause alone.
    record CheckpointRefused(ExitCode exit, String error, String message) implements CliError {
        public static CheckpointRefused tooLarge(String error) {
            return refused(ExitCode.CHECKPOINT_TOO_LARGE, "checkpoint too large (cap 16 KB)", error);
        }

        public static CheckpointRefused notOnBranch(String error) {
            return refused(ExitCode.SHA_NOT_ON_BRANCH, "pushed sha is not on the task branch; push it first", error);
        }

        public static CheckpointRefused mirrorUnavailable(String error) {
            return refused(ExitCode.MIRROR_UNAVAILABLE, "controller mirror unavailable, nothing stored; retry", error);
        }

        private static CheckpointRefused refused(ExitCode exit, String what, String error) {
            return new CheckpointRefused(exit, error, "Controller refused the checkpoint: " + what + " (" + error + ")");
        }

        @Override
        public ExitCode exitCode() {
            return exit;
        }
    }

    /// `task claim --checkout DIR`: the clone, the checkout or the stamp failed after the lease was granted.
    @SuppressWarnings("JBCT-CAUSE-04")
    record CheckoutFailed(String dir, Cause origin, String message) implements CliError, Cause.Wrapped {
        public static final Fn2<CheckoutFailed, String, Cause> FACTORY = Causes.forTwoValues("Checkout into %s failed; the lease is held, release it",
                                                                                             CheckoutFailed::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.ERROR;
        }
    }

    /// `events`: no heartbeat line was received for `seconds` (2 × the 55 s long-poll), counted by
    /// this process's receipt time, never by the controller's `asOf`: a dead controller, network or a total hang.
    record NoHeartbeat(long seconds, String message) implements CliError {
        public static final Fn1<NoHeartbeat, Long> FACTORY = Causes.forOneValue("No heartbeat from the controller for %d s",
                                                                                NoHeartbeat::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.NO_HEARTBEAT;
        }
    }

    /// `events --until`: a source the wait depends on is stale in the heartbeat (or absent from it), so a match could
    /// not arrive through it.
    record SourceStale(String name, String message) implements CliError {
        public static final Fn1<SourceStale, String> FACTORY = Causes.forOneValue("Source %s, which this wait depends on, is stale",
                                                                                  SourceStale::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.SOURCE_STALE;
        }
    }

    /// `events --until --timeout <dur>`: no matching event within `timeout`.
    record EventsTimeout(String timeout, String message) implements CliError {
        public static final Fn1<EventsTimeout, String> FACTORY = Causes.forOneValue("No matching event within %s",
                                                                                    EventsTimeout::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.EVENTS_TIMEOUT;
        }
    }

    /// `events --until`: the heartbeat's `selfCheck.ok` is false; `failed` names the probes (`ssh-banner`, `loopback`).
    record SelfCheckFailed(String failed, String message) implements CliError {
        public static final Fn1<SelfCheckFailed, String> FACTORY = Causes.forOneValue("Controller self-check failed: %s",
                                                                                      SelfCheckFailed::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.SELF_CHECK_FAILED;
        }
    }

    /// `events --until --after <epoch>:<seq>`: the controller's temper epoch is not `given` (a restored or replaced
    /// database), so `seq` names nothing in its log.
    record EpochChanged(String given, String current, String message) implements CliError {
        public static final Fn2<EpochChanged, String, String> FACTORY = Causes.forTwoValues("Event epoch changed: --after names %s, the controller is at %s; start again without --after",
                                                                                            EpochChanged::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.EPOCH_CHANGED;
        }
    }

    /// `events --follow`: an ack refused with 409, the epoch its line was read in (`epoch`) no longer the controller's
    /// (lead ruling F2), the cursor unchanged. Not final: `--follow` reads it as an epoch boundary and polls again.
    record AckEpochChanged(String epoch, String message) implements CliError {
        public static final Fn1<AckEpochChanged, String> FACTORY = Causes.forOneValue("Ack refused: the controller's epoch is no longer %s, the epoch the line was read in",
                                                                                      AckEpochChanged::new);

        @Override
        public ExitCode exitCode() {
            return ExitCode.EPOCH_CHANGED;
        }
    }

    /// Exit code for any failure. A composite (several parse failures collected by `Result.all`) exits with
    /// its first member's code; a cause that is not a [CliError] escaped every translation, which is a bug,
    /// reported as ERROR.
    static ExitCode exitCodeOf(Cause cause) {
        return switch (cause) {
            case CliError error -> error.exitCode();
            case Causes.CompositeCause composite -> composite.stream().findFirst().map(CliError::exitCodeOf).orElse(ExitCode.ERROR);
            default -> ExitCode.ERROR;
        };
    }
}
