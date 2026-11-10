package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;


/// The heartbeat line, the last line of every fleet events reply and the whole body of
/// `GET /api/v1/fleet/heartbeat`. `sources` keeps its insertion order (sorted by name by the controller).
@JsonPropertyOrder({"v", "kind", "epoch", "asOf", "seqHead", "sources", "selfCheck", "kilnLag"})
public record HeartbeatLine(int v,
                            String kind,
                            String epoch,
                            Instant asOf,
                            long seqHead,
                            Map<String, Source> sources,
                            SelfCheck selfCheck,
                            long kilnLag) {
    public static final String KIND = "heartbeat";

    /// `stale = now − lastOkAt > 3 × periodS`, or `lastOkAt` null.
    @JsonPropertyOrder({"lastOkAt", "periodS", "stale"})
    public record Source(Option<Instant> lastOkAt, int periodS, boolean stale) {
        public static Source source(Option<Instant> lastOkAt, int periodS, boolean stale) {
            return new Source(lastOkAt, periodS, stale);
        }
    }

    /// `ok` is false only for `ssh-banner` or `loopback`; `advisory` never sets it (R2-12).
    @JsonPropertyOrder({"ok", "failed", "advisory"})
    public record SelfCheck(boolean ok, List<String> failed, Advisory advisory) {
        public SelfCheck {
            failed = List.copyOf(failed);
        }

        public static SelfCheck selfCheck(boolean ok, List<String> failed, Advisory advisory) {
            return new SelfCheck(ok, failed, advisory);
        }
    }

    /// Unreadable values (no `/proc` on this host) are null.
    @JsonPropertyOrder({"load1", "cores", "memSomeAvg60"})
    public record Advisory(Option<Double> load1, int cores, Option<Double> memSomeAvg60) {
        public static Advisory advisory(Option<Double> load1, int cores, Option<Double> memSomeAvg60) {
            return new Advisory(load1, cores, memSomeAvg60);
        }
    }

    public HeartbeatLine {
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    public static HeartbeatLine heartbeatLine(String epoch,
                                              Instant asOf,
                                              long seqHead,
                                              Map<String, Source> sources,
                                              SelfCheck selfCheck,
                                              long kilnLag) {
        return new HeartbeatLine(FleetEventLine.VERSION, KIND, epoch, asOf, seqHead, sources, selfCheck, kilnLag);
    }
}
