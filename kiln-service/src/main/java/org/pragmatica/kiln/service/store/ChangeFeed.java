package org.pragmatica.kiln.service.store;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.vo.Uuid;


/// One read of the change feed: kiln's `epoch`, `head` the last id handed out (0 before the first,
/// kept when every row is pruned), `floor` the smallest id kept (`head + 1` when none is), and the rows with an id above
/// the cursor, ascending. `highWaterOkAt` is when kiln last wrote its restore-detection file: the feed's
/// `kiln:highwater` source, whose period is [#HIGH_WATER_PERIOD_S] (lead ruling R2-F3).
public record ChangeFeed(Uuid epoch, long floor, long head, List<Row> changes, Option<Instant> highWaterOkAt) {
    /// The `kiln:highwater` source's period: a healthy file is rewritten well inside it.
    public static final long HIGH_WATER_PERIOD_S = 60;

    /// One `change` row as stored: `pr` none for a change about no PR, `payload` the kind's extra facts as JSON text.
    /// `kind` is the stored name, which the `change.kind` CHECK constrains, not a `ChangeKind`: a row a newer kiln wrote
    /// (its kinds outnumber this build's after a rollback) passes through, as the feed asks of every reader, and never fails
    /// the feed.
    public record Row(long id,
                      String kind,
                      String repo,
                      Option<PrNumber> pr,
                      String subject,
                      String before,
                      String after,
                      Instant observedAt,
                      Instant recordedAt,
                      Option<String> payload) {}

    public ChangeFeed {
        changes = List.copyOf(changes);
    }
}
