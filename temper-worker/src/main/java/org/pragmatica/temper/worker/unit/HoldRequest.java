package org.pragmatica.temper.worker.unit;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.protocol.message.PortGroupHoldDto;


/// `StartUnit.portGroup` parsed (m1-design §1.2): the unit starts through `bb-hold <owner> <priority> …`.
/// `owner` is restricted to bb-hold's own owner alphabet (`[A-Za-z0-9_]+`, the script refuses anything else
/// with rc 2), so a bad owner fails the request instead of reaching a bb-hold argv. `priority` is advisory to
/// bb-hold (Q-M1-12) and any integer.
public record HoldRequest(String owner, int priority) {
    public static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9_]{1,32}$");

    public static Result<HoldRequest> holdRequest(PortGroupHoldDto hold) {
        return Values.matching(hold.owner(),
                               OWNER,
                               "portGroup.owner")
                     .map(owner -> new HoldRequest(owner,
                                                   hold.priority()));
    }
}
