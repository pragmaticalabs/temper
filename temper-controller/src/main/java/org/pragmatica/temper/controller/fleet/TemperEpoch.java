package org.pragmatica.temper.controller.fleet;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.store.api.FleetHead;
import org.pragmatica.temper.store.api.NewFleetEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.node.JsonNodeFactory;


/// temper's epoch (design §2.2 Correction 2): [EpochCheck] over the high-water mark and the
/// database, at start ([#settle]) and while running ([#verify]). A restore, an unknown history, or a high-water file
/// that cannot be written at start gets a new epoch in the database, the mark rewritten, and `epoch-changed
/// reason=restore` in the stream, whose `detail` says which ([Detail]).
public sealed interface TemperEpoch {
    Logger log = LoggerFactory.getLogger(TemperEpoch.class);

    /// Why the epoch was rotated, the event's `detail`.
    enum Detail {
        /// At start: the file's mark is ahead of the database (or of another epoch).
        FILE_AHEAD("file-ahead"),
        /// At start: no file, but the stream was written.
        NO_FILE("no-file"),
        /// At start: the file could not be written, so a later start could not detect a restore.
        UNWRITABLE("unwritable"),
        /// While running: the mark in memory is ahead of the database, or an insert reused a seq (restored under the
        /// running controller).
        RUNNING("running");
        private final String text;
        Detail(String text) {
            this.text = text;
        }
        public String text() {
            return text;
        }
    }

    /// Answers the epoch the controller runs with.
    static Promise<String> settle(FleetWriter writer, Instant now) {
        return writer.store()
                     .head()
                     .flatMap(head -> settled(writer,
                                              head,
                                              EpochCheck.decide(writer.highWater().read(),
                                                                head),
                                              now));
    }

    private static Promise<String> settled(FleetWriter writer,
                                           FleetHead head,
                                           EpochCheck.Verdict verdict,
                                           Instant now) {
        return switch (verdict) {
            case FIRST, CURRENT -> keptOrRenewed(writer, head, now);
            case RESTORED -> renewed(writer, head, now, Detail.FILE_AHEAD);
            case UNKNOWN -> renewed(writer, head, now, Detail.NO_FILE);
        };
    }

    /// The database's epoch, unless the file cannot be written: then nothing would detect a restore at the next start.
    private static Promise<String> keptOrRenewed(FleetWriter writer, FleetHead head, Instant now) {
        return kept(writer, head)
               ? Promise.success(head.epoch())
               : renewed(writer, head, now, Detail.UNWRITABLE);
    }

    /// The mark set to the database's head; whether the file was written.
    private static boolean kept(FleetWriter writer, FleetHead head) {
        return writer.highWater()
                     .force(new HighWater.Mark(head.epoch(),
                                               head.seq()))
                     .isSuccess();
    }

    /// While running: the mark in memory, read BEFORE the database (whose values only grow, so a mark above them is a
    /// restore, never a race with a later insert), against the database; a restore rotates the epoch. Then the file
    /// is rewritten; answers whether it was.
    static Promise<Boolean> verify(FleetWriter writer, Instant now) {
        var mark = writer.highWater().mark();

        return writer.store()
                     .head()
                     .flatMap(head -> verified(writer, mark, head, now));
    }

    private static Promise<Boolean> verified(FleetWriter writer, HighWater.Mark mark, FleetHead head, Instant now) {
        return EpochCheck.ahead(mark, head)
               ? renewed(writer, head, now, Detail.RUNNING).map(_ -> refreshed(writer, head))
               : Promise.success(refreshed(writer, head));
    }

    /// While running: an insert reused a seq ([FleetWriter]), which is proof of a restore without asking the database
    /// whether it is behind; the epoch rotates.
    static Promise<String> rotate(FleetWriter writer, Instant now) {
        return writer.store()
                     .head()
                     .flatMap(head -> renewed(writer, head, now, Detail.RUNNING));
    }

    private static boolean refreshed(FleetWriter writer, FleetHead head) {
        return writer.highWater()
                     .refresh(head.seq())
                     .isSuccess();
    }

    /// A new epoch; the mark is set to it even when the file cannot be written (the running checks use the mark).
    private static Promise<String> renewed(FleetWriter writer, FleetHead head, Instant now, Detail detail) {
        return writer.store()
                     .renewEpoch()
                     .flatMap(epoch -> announced(writer,
                                                 head,
                                                 epoch,
                                                 kept(writer,
                                                      FleetHead.fleetHead(epoch,
                                                                          head.seq(),
                                                                          head.sequence())),
                                                 now,
                                                 detail));
    }

    private static Promise<String> announced(FleetWriter writer,
                                             FleetHead old,
                                             String epoch,
                                             boolean written,
                                             Instant now,
                                             Detail detail) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("reason", "restore");
        payload.put("detail", detail.text());
        payload.put("old", old.epoch());
        payload.put("new", epoch);
        log.warn("fleet: epoch {} -> {} ({}; high-water file written: {})",
                 old.epoch(),
                 epoch,
                 detail.text(),
                 written);

        return writer.announce(List.of(NewFleetEvent.newFleetEvent(now,
                                                                   "controller",
                                                                   "epoch-changed",
                                                                   "temper",
                                                                   payload.toString())))
                     .map(_ -> epoch);
    }

    record unused() implements TemperEpoch {}
}
