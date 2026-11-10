package org.pragmatica.kiln.service.store.pg;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;


/// jsonb columns, and arrays read as `to_json(column)::text` (as temper reads them). The shapes are kiln's own rows,
/// written only by this store; temper's mapper is never used (§4.5).
sealed interface Json {
    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    /// The NUL escape, not preceded by an odd run of backslashes (which would make its backslash literal).
    Pattern NUL_ESCAPE = Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u0000");

    TypeToken<List<String>> STRINGS = new TypeToken<>() {};

    TypeToken<List<Long>> LONGS = new TypeToken<>() {};

    TypeToken<List<CheckJson>> CHECKS = new TypeToken<>() {};

    TypeToken<List<BranchJson>> BRANCHES = new TypeToken<>() {};

    TypeToken<Map<String, Integer>> COUNTS = new TypeToken<>() {};

    /// One `{context, appId}` of `required_checks.checks`; `appId` is null when no app is pinned.
    record CheckJson(String context, Long appId) {}

    /// One `{name, tip}` of `checkout.branches`.
    record BranchJson(String name, String tip) {}

    /// The JSON text of `value`, with every U+0000 inside a string value written as U+FFFD: jsonb refuses the NUL escape
    /// (22P05), and one such character in a status description would block that PR's cell write on every cycle (lead
    /// ruling, WP5 verification), as [Sql#text] prevents for `text` columns.
    static Result<String> write(Object value) {
        return MAPPER.writeAsString(value).map(Json::withoutNul);
    }

    /// The mapper writes U+0000 only as its escape; one preceded by an escaped backslash is literal text and is kept.
    private static String withoutNul(String json) {
        return NUL_ESCAPE.matcher(json).replaceAll("$1" + Sql.REPLACEMENT);
    }

    static <T> Result<T> read(String json, TypeToken<T> type) {
        return MAPPER.readString(json, type);
    }

    record unused() implements Json {}
}
