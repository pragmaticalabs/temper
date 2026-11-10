package org.pragmatica.temper.domain.task;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.Sha;


/// A command to [TaskMachine] (m3-design §5.2), one per row group of the transition table. Guards that need the store
/// (the tasks in `after`, the awaited jobs, the PR's merge, a linked job) arrive as the facts the store read; the
/// lease token itself is checked by the store's conditional UPDATE, never here (§6.4).
public sealed interface Command {
    /// The wire name used in refusals and events.
    String name();

    /// T1 (dispatcher): `design` is the brief's flag; `afterReached` whether every task in `after` has reached VERIFY or
    /// later (ruling R-M3-18).
    record Advance(boolean design, boolean afterReached) implements Command {
        @Override
        public String name() {
            return "advance";
        }
    }

    /// T2 (worker): the new token's hash, who holds it, and the issued read point (§6.5). The clocks are the stage's
    /// [LeaseTerms].
    record Claim(String tokenHash, String holder, Sha readPoint) implements Command {
        @Override
        public String name() {
            return "claim";
        }
    }

    /// T3 (lease): restarts the TTL clock. A linked submit ([LinkJob]) does the same.
    record Heartbeat() implements Command {
        @Override
        public String name() {
            return "heartbeat";
        }
    }

    /// A `submit --task` under the lease (§6.4): an accepted lease-token call, so the TTL clock restarts as on T3.
    record LinkJob() implements Command {
        @Override
        public String name() {
            return "link-job";
        }
    }

    /// T4, T9, T9′, T13 (lease): an accepted evidence document (`evidence` is its `task_evidence` id), validated by WP-2.
    /// `lastFix` is the task's latest accepted FIX evidence, for a round-cap park (§7.6).
    record Evidence(long evidence, Option<Long> lastFix, EvidenceOutcome outcome) implements Command {
        @Override
        public String name() {
            return "evidence";
        }
    }

    /// T5 (lease): waits on linked jobs (the store checked each is linked to this task) or CI.
    record Wait(Waiting awaited) implements Command {
        @Override
        public String name() {
            return "wait";
        }
    }

    /// T6 (temper): `settled` is whether every awaited job is terminal.
    record Resume(boolean settled) implements Command {
        @Override
        public String name() {
            return "resume";
        }
    }

    /// T7 (lease).
    record Release(ReleaseReason reason) implements Command {
        @Override
        public String name() {
            return "release";
        }
    }

    /// T8 (sweeper): `linkedJobActive` is whether a non-terminal job is linked at the current epoch, which holds the
    /// TTL clock but not the step deadline (§6.2).
    record Expire(boolean linkedJobActive) implements Command {
        @Override
        public String name() {
            return "expire";
        }
    }

    /// The controller started (m3-design §6.2 [G]): a lease whose TTL lapsed during the downtime (its worker's
    /// heartbeats failed while the controller was away) gets one TTL from the start, unless its step deadline passed (a
    /// wall bound, never moved) or it was already extended at this epoch (`extendedThisEpoch`: at most once per epoch).
    record ControllerStart(boolean extendedThisEpoch) implements Command {
        @Override
        public String name() {
            return "controller-start";
        }
    }

    /// T10: a park by the lease holder (`byLease`, so the task must be LEASED) or by the maintainer.
    record Park(ParkRequest request, boolean byLease) implements Command {
        @Override
        public String name() {
            return "park";
        }
    }

    /// T11 (maintainer): the store checked the open ruling and that the request offered the option.
    record Rule(RulingDecision decision) implements Command {
        @Override
        public String name() {
            return "rule";
        }
    }

    /// T12 (CI watcher, WP-4).
    record CiDecision(CiOutcome outcome) implements Command {
        @Override
        public String name() {
            return "ci-decision";
        }
    }

    /// T14 (maintainer): back to FIX with the maintainer's item.
    record Bounce(String item) implements Command {
        @Override
        public String name() {
            return "bounce";
        }
    }

    /// T15 (maintainer): `prMerged` is GitHub's answer for the task's PR (§10.5).
    record CloseMerged(boolean prMerged) implements Command {
        @Override
        public String name() {
            return "close-merged";
        }
    }

    /// T16 (maintainer).
    record CloseAbandoned() implements Command {
        @Override
        public String name() {
            return "close-abandoned";
        }
    }

    /// The watcher's decision for the head (§10.2, §10.3): green → GATE, red → FIX (`ci_rounds+1`), a merged stack base
    /// → FIX with a `merge-forward` item, or a park temper fills (`ci-absent`, `ci-cancelled`, `foreign-push`).
    sealed interface CiOutcome {
        record Green() implements CiOutcome {}

        record Red(String item) implements CiOutcome {}

        record MergeForward(String item) implements CiOutcome {}

        record Parked(ParkRequest request) implements CiOutcome {}
    }

    /// Every command a lease token carries (§6.4): refused with `lease-lost` when the task is not LEASED.
    static boolean isLeaseScoped(Command command) {
        return switch (command) {
            case Heartbeat _, LinkJob _, Evidence _, Wait _, Release _ -> true;
            case Park park -> park.byLease();
            case Advance _, Claim _, Resume _, Expire _, Rule _, CiDecision _, Bounce _, CloseMerged _, CloseAbandoned _, ControllerStart _ -> false;
        };
    }
}
