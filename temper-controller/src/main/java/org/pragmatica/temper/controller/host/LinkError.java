package org.pragmatica.temper.controller.host;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.store.api.HostName;


/// Failures of a request over a host link (m0-design §6.4).
public sealed interface LinkError extends Cause {
    /// The link is not UP (or went down while the request was pending); retrying after reconnect may succeed.
    record Down(HostName host, String message) implements LinkError, Cause.Transient {
        public static final Fn1<Down, HostName> FACTORY = Causes.forOneValue("Host link %s is down", Down::new);
    }

    /// The worker answered a request with `Failure{code, detail}`; `code` (e.g. `seed-isolation`) is the verdict
    /// reason the controller records.
    record WorkerFailure(String code, String detail, String message) implements LinkError {
        public static final Fn2<WorkerFailure, String, String> FACTORY = Causes.forTwoValues("Worker failed: %s (%s)",
                                                                                             WorkerFailure::new);
    }

    /// The worker answered with a message the request does not expect: a protocol mismatch.
    record Unexpected(String expected, String actual, String message) implements LinkError, Cause.Terminal {
        public static final Fn2<Unexpected, String, String> FACTORY = Causes.forTwoValues("Expected %s from the worker, got %s",
                                                                                          Unexpected::new);
    }

    /// Writing a frame failed (the ssh process is gone).
    @SuppressWarnings("JBCT-CAUSE-04")
    record SendFailed(HostName host, Cause origin, String message) implements LinkError, Cause.Wrapped {
        public static final Fn2<SendFailed, HostName, Cause> FACTORY = Causes.forTwoValues("Cannot send to host %s",
                                                                                           SendFailed::new);
    }

    /// Deploying the worker jar through the `install` verb failed.
    @SuppressWarnings("JBCT-CAUSE-04")
    record DeployFailed(HostName host, Cause origin, String message) implements LinkError, Cause.Wrapped {
        public static final Fn2<DeployFailed, HostName, Cause> FACTORY = Causes.forTwoValues("Deploying the worker to %s failed",
                                                                                             DeployFailed::new);
    }

    /// ssh refused the host's key (m4-design §2.2, [SshDiagnosis]): it is not the one in the configured `knownHosts`, or
    /// that file has no entry. The host is LOST at once (ruling R-M4-16, Q-M4-2); the link keeps retrying, and the host
    /// recovers once the operator puts the right key in the file (ssh reads it per connect).
    record HostKey(HostName host, String message) implements LinkError {
        public static final Fn1<HostKey, HostName> FACTORY = Causes.forOneValue("ssh refused the host key of %s (knownHosts)",
                                                                                HostKey::new);
    }

    /// L4 (D-6): the configured worker jar is of another build than this controller (its `version=` and `judge=` lines
    /// derive another build version), so it is not installed: the worker it starts would be `Incompatible` and be
    /// redeployed forever. Nothing a retry does changes it; the operator fixes the dist's worker jar.
    record ForeignWorkerJar(HostName host, String jarVersion, String controllerVersion, String message) implements LinkError, Cause.Terminal {
        public static final Fn3<ForeignWorkerJar, HostName, String, String> FACTORY = Causes.forThreeValues("Not deploying the worker to %s: the worker jar is build %s, this controller is %s; install the dist of one build",
                                                                                                            ForeignWorkerJar::new);
    }

    /// Why a background write of the link (a start retry, an UNKNOWN mark) stops without trying again: nothing a retry
    /// does changes either.
    enum Stopped implements LinkError, Cause.Terminal {
        LINK_STOPPED("The host link stopped"),
        START_SETTLED("The unit's start is recorded or the unit completed");
        private final String message;
        Stopped(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
