package org.pragmatica.temper.cli;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.pragmatica.temper.cli.FakeController.Reply;

/// Fleet lines as WP3 writes them, built as text so a test reads the wire exactly.
final class FleetFixtures {
    static final String EPOCH = "0f1e2d3c-4b5a-4968-8776-655443322110";
    static final String OTHER_EPOCH = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
    static final Map<String, Boolean> FRESH = Map.of("kiln", false,
                                                     "kiln:github", false,
                                                     "kiln:highwater", false,
                                                     "hostlink:bigboy", false,
                                                     "hostlink:cloudbb-3", false,
                                                     "selfcheck", false,
                                                     "selfcheck:highwater", false);

    private FleetFixtures() {}

    /// An event line of `EPOCH`, `payload` the payload object's JSON.
    static String event(long seq, String kind, String subject, boolean catchUp, String payload) {
        return event(EPOCH, seq, kind, subject, catchUp, payload);
    }

    static String event(String epoch, long seq, String kind, String subject, boolean catchUp, String payload) {
        return "{\"v\":1,\"epoch\":\"" + epoch + "\",\"seq\":" + seq + ",\"at\":\"2026-11-03T10:00:00Z\","
               + "\"observedAt\":\"2026-11-03T09:59:58Z\",\"kind\":\"" + kind + "\",\"subject\":\"" + subject + "\","
               + "\"catchUp\":" + catchUp + ",\"source\":\"kiln\",\"payload\":" + payload + "}";
    }

    /// A heartbeat of `EPOCH` at `seqHead`, every source fresh, the self-check ok.
    static String heartbeat(long seqHead) {
        return heartbeat(EPOCH, seqHead, FRESH, true);
    }

    static String heartbeat(String epoch, long seqHead, Map<String, Boolean> stale, boolean ok, String... failed) {
        return "{\"v\":1,\"kind\":\"heartbeat\",\"epoch\":\"" + epoch + "\",\"asOf\":\"2026-11-03T10:00:01Z\",\"seqHead\":"
               + seqHead + ",\"sources\":{" + sources(stale) + "},\"selfCheck\":{\"ok\":" + ok + ",\"failed\":["
               + Arrays.stream(failed)
                       .map(name -> "\"" + name + "\"")
                       .collect(Collectors.joining(",")) + "],\"advisory\":{\"load1\":1.5,\"cores\":8,\"memSomeAvg60\":0.2}},"
               + "\"kilnLag\":3}";
    }

    /// The fresh sources with `name` stale.
    static Map<String, Boolean> staleSource(String name) {
        var sources = new TreeMap<>(FRESH);

        sources.put(name, true);

        return sources;
    }

    private static String sources(Map<String, Boolean> stale) {
        return new TreeMap<>(stale).entrySet()
                                   .stream()
                                   .map(source -> "\"" + source.getKey() + "\":{\"lastOkAt\":\"2026-11-03T09:59:00Z\","
                                                  + "\"periodS\":60,\"stale\":" + source.getValue() + "}")
                                   .collect(Collectors.joining(","));
    }

    /// A 200 reply of these lines, NDJSON.
    static Reply reply(String... lines) {
        return Reply.text(200, String.join("\n", lines) + "\n");
    }
}
