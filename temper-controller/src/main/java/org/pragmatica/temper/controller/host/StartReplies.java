package org.pragmatica.temper.controller.host;

import java.time.Instant;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.MarkerEpoch;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.HoldingRow;
import org.pragmatica.temper.store.api.UnitFinishRow;
import org.pragmatica.temper.store.api.UnitStartedRow;


/// The worker's answers to a start, as store rows (m1-design §1.3). A reply to `StartUnit` and the unsolicited
/// messages of a bb-hold unit (its `UnitStarted` once the hold is granted, its `UnitStartFailed` when the hold
/// fails) go through the same conversions, so both paths record a start the same way. Pure.
public sealed interface StartReplies {
    /// `UnitStarted` → the started row; `ticket` is the bb-* ticket of a hold unit.
    static Result<UnitStartedRow> startedRow(Message.UnitStarted started) {
        return Result.all(marker(started.markerEpochNs()),
                          started.facts().toDomain())
                     .map((marker, facts) -> UnitStartedRow.unitStartedRow(started.containerId(),
                                                                           started.imageId(),
                                                                           marker,
                                                                           started.startedAt(),
                                                                           facts,
                                                                           started.ticket()));
    }

    /// `UnitHolding` → the holding row: the marker written before bb-hold was spawned, and the facts then.
    static Result<HoldingRow> holdingRow(Message.UnitHolding holding) {
        return Result.all(marker(holding.markerEpochNs()),
                          holding.facts().toDomain())
                     .map(HoldingRow::holdingRow);
    }

    /// `UnitStartFailed` → how the unit ends: VOID (disk), ERROR (infra, port-group, manifest) or CANCELLED.
    static UnitFinishRow startFailure(Message.UnitStartFailed failed, Instant now) {
        return switch (failed.verdictKind()) {
            case VOID -> UnitFinishes.voided(failed.reason(), now);
            case ERROR -> UnitFinishes.errored(failed.reason(), now);
            case CANCELLED -> UnitFinishes.cancelled(now);
        };
    }

    private static Result<MarkerEpoch> marker(long markerEpochNs) {
        return MarkerEpoch.markerEpoch(Long.toString(markerEpochNs));
    }

    record unused() implements StartReplies {}
}
