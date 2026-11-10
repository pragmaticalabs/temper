package org.pragmatica.temper.domain.task;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.Sha;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A task's lease (m3-design §6): the fencing `epoch`, which never decreases, and while LEASED the sha256 of the lease
/// token (`hash`, the token itself is never stored), its holder, the TTL clock `until`, the wall bound `stepDeadline`,
/// and the read point issued at the claim (§6.5), which stays until the next claim issues another.
public record LeaseState(long epoch,
                         Option<String> hash,
                         Option<String> holder,
                         Option<Instant> until,
                         Option<Instant> stepDeadline,
                         Option<Sha> readPoint) {
    /// Never leased.
    public static final LeaseState NONE = new LeaseState(0, none(), none(), none(), none(), none());

    public static LeaseState leaseState(long epoch,
                                        Option<String> hash,
                                        Option<String> holder,
                                        Option<Instant> until,
                                        Option<Instant> stepDeadline,
                                        Option<Sha> readPoint) {
        return new LeaseState(epoch, hash, holder, until, stepDeadline, readPoint);
    }

    /// A claim (T2): the next epoch and its clocks.
    LeaseState granted(String tokenHash, String leaseHolder, Instant now, LeaseTerms terms, Sha issued) {
        return new LeaseState(epoch + 1,
                              some(tokenHash),
                              some(leaseHolder),
                              some(now.plus(terms.ttl())),
                              some(now.plus(terms.stepTimeout())),
                              some(issued));
    }

    /// The lease ends without a revocation (accepted evidence hands the task on): the epoch stays.
    LeaseState ended() {
        return new LeaseState(epoch, none(), none(), none(), none(), readPoint);
    }

    /// The lease is revoked (expiry, wait, release, park, close of a leased task): the epoch moves on, so no call
    /// carrying the old epoch is accepted again.
    LeaseState revoked() {
        return new LeaseState(epoch + 1, none(), none(), none(), none(), readPoint);
    }

    /// T3 and every accepted lease-token call: the TTL clock starts again; the step deadline never moves.
    LeaseState extended(Instant now, LeaseTerms terms) {
        return new LeaseState(epoch,
                              hash,
                              holder,
                              some(now.plus(terms.ttl())),
                              stepDeadline,
                              readPoint);
    }

    boolean isStepDeadlinePassed(Instant now) {
        return stepDeadline.filter(deadline -> deadline.isBefore(now))
                           .isPresent();
    }

    boolean isTtlPassed(Instant now) {
        return until.filter(ttl -> ttl.isBefore(now))
                    .isPresent();
    }
}
