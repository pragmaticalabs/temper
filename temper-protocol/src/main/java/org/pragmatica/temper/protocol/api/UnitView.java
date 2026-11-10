package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.message.KilledBy;


/// One unit in a [JobView]. `leaseExpired` marks a RUNNING unit whose host is unreachable
/// ("RUNNING(unknown)", m0-design §6.4); it is never re-granted. `portGroup` (M1, m1-design §1.3) is `waiting`
/// while bb-hold waits for the group and `held` once granted, absent for a unit without a hold; `portGroupSince`
/// is when it entered that state (Q-M1-17: a wait has no bound, so its age is shown); `ticket` is the bb-*
/// ticket once granted. M4 (m4-design §11): `attempt` is the unit's current attempt, its grant count (0 before its first
/// grant); `sharedCpu` whether that attempt was granted to a shared-CPU host (absent before a grant, §9.4); `sideEffects`
/// `none` or `external` (§5: an `external` unit is never requeued after a host loss); `lostAttempts` its earlier
/// attempts lost with their host, oldest first (§2.3). A view written without them (an older controller, a hand-written
/// answer) reads attempt 0, none and no lost attempts.
public record UnitView(String name,
                       UnitState state,
                       Option<String> reason,
                       Option<String> host,
                       Option<Integer> rc,
                       Option<Instant> startedAt,
                       Option<Instant> endedAt,
                       Option<String> imageId,
                       Option<String> containerId,
                       Option<KilledBy> killedBy,
                       boolean leaseExpired,
                       Option<String> portGroup,
                       Option<Instant> portGroupSince,
                       Option<String> ticket,
                       Integer attempt,
                       Option<Boolean> sharedCpu,
                       Option<String> sideEffects,
                       List<LostAttemptDto> lostAttempts) {
    public static final String WAITING = "waiting";
    public static final String HELD = "held";

    public UnitView {
        attempt = Option.option(attempt).or(0);
        lostAttempts = Option.option(lostAttempts).map(List::copyOf).or(List.of());
    }

    /// A unit as before M4: attempt 0, no CPU class, side effects or lost attempts.
    public static UnitView unitView(String name,
                                    UnitState state,
                                    Option<String> reason,
                                    Option<String> host,
                                    Option<Integer> rc,
                                    Option<Instant> startedAt,
                                    Option<Instant> endedAt,
                                    Option<String> imageId,
                                    Option<String> containerId,
                                    Option<KilledBy> killedBy,
                                    boolean leaseExpired,
                                    Option<String> portGroup,
                                    Option<Instant> portGroupSince,
                                    Option<String> ticket) {
        return unitView(name,
                        state,
                        reason,
                        host,
                        rc,
                        startedAt,
                        endedAt,
                        imageId,
                        containerId,
                        killedBy,
                        leaseExpired,
                        portGroup,
                        portGroupSince,
                        ticket,
                        0,
                        Option.none(),
                        Option.none(),
                        List.of());
    }

    public static UnitView unitView(String name,
                                    UnitState state,
                                    Option<String> reason,
                                    Option<String> host,
                                    Option<Integer> rc,
                                    Option<Instant> startedAt,
                                    Option<Instant> endedAt,
                                    Option<String> imageId,
                                    Option<String> containerId,
                                    Option<KilledBy> killedBy,
                                    boolean leaseExpired,
                                    Option<String> portGroup,
                                    Option<Instant> portGroupSince,
                                    Option<String> ticket,
                                    int attempt,
                                    Option<Boolean> sharedCpu,
                                    Option<String> sideEffects,
                                    List<LostAttemptDto> lostAttempts) {
        return new UnitView(name,
                            state,
                            reason,
                            host,
                            rc,
                            startedAt,
                            endedAt,
                            imageId,
                            containerId,
                            killedBy,
                            leaseExpired,
                            portGroup,
                            portGroupSince,
                            ticket,
                            attempt,
                            sharedCpu,
                            sideEffects,
                            lostAttempts);
    }
}
