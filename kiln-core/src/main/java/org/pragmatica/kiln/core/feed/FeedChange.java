package org.pragmatica.kiln.core.feed;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.some;


/// One change of a [FeedKind], as the `change` row records it: `pr` none for a change about no PR (a base run, a bot
/// issue, a required list), `before` [#NONE] when nothing was observed before, and `payload` the kind's further facts in
/// insertion order, each value a string, a number, a boolean or a list of strings (absent facts are left out).
public record FeedChange(FeedKind kind,
                         Option<PrNumber> pr,
                         String subject,
                         String before,
                         String after,
                         Instant observedAt,
                         Map<String, Object> payload) {
    /// `before` of a first observation.
    public static final String NONE = "none";

    public FeedChange {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /// A payload from alternating keys and values, in order: an [Option] value is unwrapped, and left out when empty.
    public static Map<String, Object> payload(Object... pairs) {
        return IntStream.range(0, pairs.length / 2)
                        .boxed()
                        .flatMap(index -> entry(pairs[2 * index].toString(),
                                                pairs[2 * index + 1]).stream())
                        .collect(Collectors.toMap(Map.Entry::getKey,
                                                  Map.Entry::getValue,
                                                  (_, last) -> last,
                                                  LinkedHashMap::new));
    }

    private static Option<Map.Entry<String, Object>> entry(String key, Object value) {
        return switch (value) {
            case Option<?> option -> option.map(present -> Map.entry(key, (Object) present));
            default -> some(Map.entry(key, value));
        };
    }
}
