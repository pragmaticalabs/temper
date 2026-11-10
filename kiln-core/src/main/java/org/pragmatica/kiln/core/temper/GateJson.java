package org.pragmatica.kiln.core.temper;

import org.pragmatica.json.JsonMapper;

import tools.jackson.core.StreamReadFeature;


/// kiln's own mapper for temper's answers: Jackson's defaults, which ignore unknown fields. Never `TemperJson`, which
/// fails on them (spec §4.5). A key given twice fails the read: temper never sends one, and last-wins would pick an
/// answer silently.
sealed interface GateJson {
    JsonMapper MAPPER = JsonMapper.jsonMapper()
                                  .withPragmaticaTypes()
                                  .configure(builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION))
                                  .build();

    record unused() implements GateJson {}
}
