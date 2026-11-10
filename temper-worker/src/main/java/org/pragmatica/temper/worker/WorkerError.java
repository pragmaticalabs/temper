package org.pragmatica.temper.worker;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.seed.SeedError;


/// Failures of the worker's own operations. A failed request is answered with `Failure{code, detail}`, where
/// `code` comes from [#code] and `detail` is the top cause's message; origins are logged on stderr, never sent
/// (git and docker stderr can carry credentials or host internals).
public sealed interface WorkerError extends Cause {
    enum General implements WorkerError {
        NO_HOME("HOME is not set"),
        RELATIVE_WORKER_HOME("TEMPER_WORKER_HOME is not an absolute path"),
        NO_VERSION("The worker version resource is missing or unfiltered"),
        NO_JUDGE("The worker judge line is missing from the version resource, or unfiltered"),
        SEED_MISSING("The host seed ~/.temper/seed-m2 does not exist; run ops/seed-host.sh"),
        BEFORE_HANDSHAKE("Request received before the handshake completed"),
        NO_IPV4_GATEWAY("The unit network has no IPv4 gateway"),
        CANCELLED_BEFORE_RUN("The unit was cancelled before its container started"),
        NO_MARKER("A container with the unit's name exists but the unit has no run marker"),
        /// No `manifest: ` prefix: it is an `Unsupported` reason, which the store prefixes itself (F8).
        MANIFEST_NOT_RECORDED("the outcome was not recorded when the exit was processed");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// The bundled `version=` and `judge=` lines do not make a build version (D-6): a project version or judge outside
    /// its pattern, or a result outside the worker-launch alphabet; `BuildVersion`'s refusal is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record BadVersion(Cause origin, String message) implements WorkerError, Cause.Wrapped {
        public static final Fn1<BadVersion, Cause> FACTORY = Causes.forOneValue("The bundled worker version is invalid",
                                                                                BadVersion::new);
    }

    /// A filesystem operation failed; the JDK failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Io(String operation, Cause origin, String message) implements WorkerError, Cause.Wrapped {
        public static final Fn2<Io, String, Cause> FACTORY = Causes.forTwoValues("Failed to %s", Io::new);
    }

    /// A docker CLI call failed (start, timeout or non-zero exit); the exec failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Docker(String operation, Cause origin, String message) implements WorkerError, Cause.Wrapped {
        public static final Fn2<Docker, String, Cause> FACTORY = Causes.forTwoValues("docker %s failed", Docker::new);
    }

    /// A git call of workspace preparation failed; the exec failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Git(String operation, Cause origin, String message) implements WorkerError, Cause.Wrapped {
        public static final Fn2<Git, String, Cause> FACTORY = Causes.forTwoValues("git %s failed", Git::new);
    }

    /// The mirror does not hold the job's commit even after a fetch (force-pushed away, or never pushed).
    record MissingCommit(String sha, String message) implements WorkerError, Cause.Terminal {
        public static final Fn1<MissingCommit, String> FACTORY = Causes.forOneValue("Commit %s is not in the mirror after a fetch",
                                                                                    MissingCommit::new);
    }

    /// The workspace repository holds symbolic links: one pointing into the host seed would let a unit's install
    /// write the seed through it, the same hazard as a hard link (A13, R-M2).
    record SymlinkedSeed(Integer count, List<String> firstPaths, String message) implements WorkerError, Cause.Terminal {
        public static final Fn2<SymlinkedSeed, Integer, List<String>> FACTORY = Causes.forTwoValues("%d symbolic link(s) in the workspace repository, e.g. %s",
                                                                                                    SymlinkedSeed::new);

        public SymlinkedSeed {
            firstPaths = List.copyOf(firstPaths);
        }
    }

    /// A unit state file exists but does not parse; the parse failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record CorruptState(Path file, Cause origin, String message) implements WorkerError, Cause.Wrapped {
        public static final Fn2<CorruptState, Path, Cause> FACTORY = Causes.forTwoValues("Unit state file %s is corrupt",
                                                                                         CorruptState::new);
    }

    /// A unit state file the operation requires does not exist (distinct from [CorruptState]: nothing to parse).
    record MissingState(Path file, String message) implements WorkerError {
        public static final Fn1<MissingState, Path> FACTORY = Causes.forOneValue("Unit state file %s is missing",
                                                                                 MissingState::new);
    }

    /// A tool printed something the worker cannot interpret (an exit code that is not a number, ...).
    /// `output` is data only: it is not formatted into the message, which becomes `Failure.detail` (tool output
    /// can carry host internals). It is logged with the cause on stderr.
    @SuppressWarnings("JBCT-CAUSE-04")
    record UnexpectedOutput(String operation, String output, String message) implements WorkerError {
        public static final Fn2<UnexpectedOutput, String, String> FACTORY = (operation, output) -> new UnexpectedOutput(operation,
                                                                                                                        output,
                                                                                                                        "Unexpected output of " + operation);
    }

    /// `worker.lock` stayed held by another worker past the wait (m0-design §7.2: exit 75).
    record LockTimeout(Path lock, String message) implements WorkerError {
        public static final Fn1<LockTimeout, Path> FACTORY = Causes.forOneValue("Host lock %s is held by another worker",
                                                                                LockTimeout::new);
    }

    /// A unit's container is gone and the unit has no `rc`: nothing is left to re-attach to.
    record ContainerLost(String container, String message) implements WorkerError, Cause.Terminal {
        public static final Fn1<ContainerLost, String> FACTORY = Causes.forOneValue("Container %s is gone and no rc was recorded",
                                                                                    ContainerLost::new);
    }

    /// The effective-POM probe ran and exited non-zero (m1-design §2.3, Q-M1-7). The message is the unit's
    /// `ERROR` reason as sent; the build does not run.
    record EffectivePomFailed(Integer rc, String message) implements WorkerError, Cause.Terminal {
        public static final Fn1<EffectivePomFailed, Integer> FACTORY = Causes.forOneValue("manifest: effective-pom rc=%d",
                                                                                          EffectivePomFailed::new);
    }

    /// The effective-POM probe could not run or left no output: `stage` names the step that failed (one of the
    /// `STAGE_*` constants), the docker or file failure is the origin. The message is the unit's `ERROR` reason as
    /// sent.
    @SuppressWarnings("JBCT-CAUSE-04")
    record EffectivePomNotRun(String stage, Cause origin, String message) implements WorkerError, Cause.Wrapped {
        public static final String STAGE_REMOVE = "stale probe container not removed";
        public static final String STAGE_PREPARE = "output directory not prepared";
        public static final String STAGE_RUN = "probe container did not run";
        public static final String STAGE_OUTPUT = "no output";

        public static final Fn2<EffectivePomNotRun, String, Cause> FACTORY = Causes.forTwoValues("manifest: effective-pom did not run: %s",
                                                                                                 EffectivePomNotRun::new);
    }

    /// A unit request names another attempt than the one this host holds of the unit (m4-design §4.2, §4.4): the
    /// controller's view and the host's disagree, so the worker acts on neither and says so.
    /// `unit` is the unit's container name, `temper-<job>-<unit>`.
    record StaleAttempt(String unit, Integer held, Integer requested, String message) implements WorkerError, Cause.Terminal {
        public static final Fn3<StaleAttempt, String, Integer, Integer> FACTORY = Causes.forThreeValues("Unit %s holds attempt %d, not attempt %d",
                                                                                                        StaleAttempt::new);
    }

    /// A message the worker never accepts from the controller (a worker-to-controller type).
    record UnexpectedMessage(String type, String message) implements WorkerError, Cause.Terminal {
        public static final Fn1<UnexpectedMessage, String> FACTORY = Causes.forOneValue("Unexpected message %s",
                                                                                        UnexpectedMessage::new);
    }

    /// The `Failure.code` for a failed request. A composite (several parse failures at once) takes the code of
    /// its first member.
    static String code(Cause cause) {
        return cause instanceof Causes.CompositeCause composite
               ? Option.from(composite.stream().findFirst())
                       .map(WorkerError::code)
                       .or(WORKER)
               : singleCode(cause);
    }

    String WORKER = "worker";
    /// The `Failure.code` of a request for another attempt than the host holds (protocol v3, m4-design §7).
    String STALE_ATTEMPT = "stale-attempt";

    private static String singleCode(Cause cause) {
        return switch (cause) {
            case SeedError _, SymlinkedSeed _ -> "seed-isolation";
            case General.SEED_MISSING -> "seed-missing";
            case General.BEFORE_HANDSHAKE -> "handshake";
            case Git _, MissingCommit _ -> "git";
            case Docker _ -> "docker";
            case ContainerLost _ -> "lost";
            case StaleAttempt _ -> STALE_ATTEMPT;
            case UnexpectedMessage _ -> "unexpected";
            case DomainError _ -> "bad-request";
            default -> WORKER;
        };
    }
}
