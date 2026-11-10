package org.pragmatica.temper.store.pg;

import java.util.List;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.temper.protocol.json.TemperJson;


/// jsonb columns through the one temper mapper ([TemperJson]; unknown fields fail). Readers wrap a failure into
/// `StoreError.CorruptRow` of their table ([Rows#corrupt]).
sealed interface Json {
    JsonMapper MAPPER = TemperJson.mapper();

    TypeToken<List<List<String>>> STEPS = new TypeToken<>() {};

    TypeToken<List<String>> STRINGS = new TypeToken<>() {};

    static Result<String> write(Object value) {
        return MAPPER.writeAsString(value);
    }

    static <T> Result<T> read(String json, Class<T> type) {
        return MAPPER.readString(json, type);
    }

    static <T> Result<T> read(String json, TypeToken<T> type) {
        return MAPPER.readString(json, type);
    }

    record unused() implements Json {}
}
