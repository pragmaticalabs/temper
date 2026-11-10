package org.pragmatica.temper.cli.transport;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// One fleet events reply: its event lines in order, and its heartbeat, none when the reply
/// ended without one (cut off before its last line). A reply with several heartbeats keeps the last.
public record FleetReply(List<FleetLine.Event> events, Option<FleetLine.Heartbeat> heartbeat) {
    public static FleetReply fleetReply(List<FleetLine> lines) {
        return new FleetReply(lines.stream().flatMap(FleetReply::event).toList(),
                              Option.from(lines.stream()
                                               .flatMap(FleetReply::heartbeat)
                                               .reduce((first, second) -> second)));
    }

    private static Stream<FleetLine.Event> event(FleetLine line) {
        return switch (line) {
            case FleetLine.Event event -> Stream.of(event);
            case FleetLine.Heartbeat _ -> Stream.empty();
        };
    }

    private static Stream<FleetLine.Heartbeat> heartbeat(FleetLine line) {
        return switch (line) {
            case FleetLine.Event _ -> Stream.empty();
            case FleetLine.Heartbeat beat -> Stream.of(beat);
        };
    }
}
