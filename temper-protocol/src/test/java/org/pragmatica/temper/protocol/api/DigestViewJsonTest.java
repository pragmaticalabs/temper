package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Ruling R-M2-14 added `known` to the digest's finished events. An answer from a controller without the field, or with
/// it null, decodes as no annotations rather than failing the CLI's `digest`.
class DigestViewJsonTest {
    private static final Instant AT = Instant.parse("2026-10-16T10:00:00Z");

    @Test
    void finished_withoutKnown_orWithNull_decodesAsNone() {
        var view = DigestView.digestView("9.-",
                                         false,
                                         none(),
                                         AT,
                                         List.of(DigestView.Finished.finished(1, AT, some(5L), EventKind.FINISHED, some("FAILED"), none(),
                                                                              none(), none(), none(), none(), none(), List.of(), List.of())),
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         0,
                                         List.of(),
                                         MaintenanceView.maintenanceView(none(), none()));
        var mapper = TemperJson.mapper();
        var json = mapper.writeAsString(view).unwrap();

        assertThat(json).contains("\"known\":[]");
        assertThat(mapper.readString(json.replace(",\"known\":[]", ""), DigestView.class).unwrap()).isEqualTo(view);
        assertThat(mapper.readString(json.replace("\"known\":[]", "\"known\":null"), DigestView.class).unwrap()).isEqualTo(view);
    }

    /// A null element is dropped [G]: the annotations never change a verdict, so one bad element must not fail the digest.
    @Test
    void finished_knownWithANullElement_dropsIt() {
        var finished = DigestView.Finished.finished(1, AT, some(5L), EventKind.FINISHED, some("FAILED"), none(), none(), none(),
                                                    none(), none(), none(), List.of("KNOWN(#21)"), List.of());
        var mapper = TemperJson.mapper();
        var json = mapper.writeAsString(finished).unwrap().replace("[\"KNOWN(#21)\"]", "[null,\"KNOWN(#21)\",null]");

        assertThat(json).contains("[null,\"KNOWN(#21)\",null]");
        assertThat(mapper.readString(json, DigestView.Finished.class).unwrap().known()).containsExactly("KNOWN(#21)");
    }

    @Test
    void finished_known_roundTripsInOrder() {
        var finished = DigestView.Finished.finished(1, AT, some(5L), EventKind.FINISHED, some("FAILED"), none(), none(), none(),
                                                    none(), none(), none(), List.of("KNOWN(#21)", "KNOWN(#1679)"), List.of());
        var mapper = TemperJson.mapper();

        assertThat(mapper.readString(mapper.writeAsString(finished).unwrap(), DigestView.Finished.class).unwrap().known())
            .containsExactly("KNOWN(#21)", "KNOWN(#1679)");
    }
}
