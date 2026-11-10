package org.pragmatica.temper.controller.fake;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.UnitRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Store rows for tests.
public final class Rows {
    private Rows() {}

    public static UnitRow unit(UnitState state, Option<HostName> host, Option<Instant> leaseUntil) {
        return UnitRow.unitRow(1, UnitName.MAIN, UnitKind.MVN, state, none(), host,
                               List.of(List.of("mvn", "-B", "verify")), true, false, 6144, Duration.ofMinutes(60),
                               leaseUntil, none(), none(), none(), none(), none(), none(), none(), none(), none(), false, none(),
                               none(), none(), none(), 1);
    }

    /// A unit with id `id` in `state`, on no host (D1: a job's units by id).
    public static UnitRow unit(long id, UnitState state) {
        return UnitRow.unitRow(id, UnitName.MAIN, UnitKind.MVN, state, none(), none(),
                               List.of(List.of("mvn", "-B", "verify")), true, false, 6144, Duration.ofMinutes(60),
                               none(), none(), none(), none(), none(), none(), none(), none(), none(), none(), false, none(),
                               none(), none(), none(), 1);
    }

    /// A RUNNING unit named `name` on `host` (a Gate's unit is `gate`).
    public static UnitRow named(String name, Option<HostName> host) {
        return UnitRow.unitRow(1, UnitName.unitName(name).unwrap(), UnitKind.MVN, UnitState.RUNNING, none(), host,
                               List.of(List.of("mvn", "-B", "verify")), true, false, 6144, Duration.ofMinutes(60),
                               none(), none(), none(), none(), none(), none(), none(), none(), none(), none(), false, none(),
                               none(), none(), none(), 1);
    }

    /// A GRANTED unit on host `bb` granted at `grantedAt`, not started; `portGroup` its hold, if any (R-13, amended).
    public static UnitRow granted(Instant grantedAt, Option<PortGroup> portGroup) {
        return granted(grantedAt, portGroup, 1);
    }

    /// The same, granted as attempt `attempt` (m4-design §4.1).
    public static UnitRow granted(Instant grantedAt, Option<PortGroup> portGroup, int attempt) {
        return UnitRow.unitRow(1, UnitName.MAIN, UnitKind.MVN, UnitState.GRANTED, none(), some(HostName.hostName("bb").unwrap()),
                               List.of(List.of("mvn", "-B", "verify")), true, portGroup.isPresent(), 6144, Duration.ofMinutes(60),
                               none(), some(grantedAt), none(), none(), none(), none(), none(), none(), none(), none(), false, none(),
                               portGroup, portGroup.map(_ -> grantedAt), none(), attempt);
    }

    /// A unit holding the port group since `since` with bb-* ticket `ticket` (m1-design §1.3).
    public static UnitRow held(Instant since, String ticket) {
        return UnitRow.unitRow(1, UnitName.MAIN, UnitKind.MVN, UnitState.RUNNING, none(), none(),
                               List.of(List.of("mvn", "-B", "verify")), true, true, 6144, Duration.ofMinutes(60),
                               none(), none(), none(), none(), none(), none(), none(), none(), none(), none(), false, none(),
                               some(PortGroup.HELD), some(since), some(ticket), 1);
    }

    public static JobRow job(long id, JobState state, List<UnitRow> units) {
        return JobRow.jobRow(JobId.jobId(id).unwrap(), OwnerTag.ownerTag("v2174").unwrap(), Instant.now(), -20, false,
                             false, false, none(), some(Sha.sha("a".repeat(40)).unwrap()),
                             Recipe.mvn(List.of("verify")).unwrap(), none(), state, none(), none(), false, none(), none(),
                             none(), none(), none(), units, List.of());
    }
}
