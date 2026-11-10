package org.pragmatica.temper.protocol.json;

import org.pragmatica.json.JsonMapper;

import tools.jackson.databind.DeserializationFeature;


/// The one JSON mapper configuration for the HTTP API, the WebSocket messages and the controller⇄worker
/// frames. Every party must use it, so all of them read and write the same shapes.
///
/// - Pragmatica types: `Option` is the value or `null`; an absent `Option` field reads as `none()`.
/// - Unknown fields FAIL: Jackson 3 defaults this off, and m0-design §3.4 requires any unknown submit
///   field (other than the declared `env`/`environment`) to be a 400.
/// - `java.time` values are ISO-8601 strings (Jackson 3 default): `Instant` as `2026-10-13T10:00:00Z`,
///   `Duration` as `PT1H30M`.
public sealed interface TemperJson {
    static JsonMapper mapper() {
        return JsonMapper.jsonMapper()
                         .withPragmaticaTypes()
                         .configure(builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                         .build();
    }

    record unused() implements TemperJson {}
}
