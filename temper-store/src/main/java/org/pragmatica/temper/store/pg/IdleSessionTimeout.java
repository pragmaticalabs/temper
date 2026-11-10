package org.pragmatica.temper.store.pg;

import java.util.Map;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.store.api.StoreError;


/// The start guard on `idle_session_timeout`: a lock session sits idle for as long as the locked
/// work runs (up to 150 s), so a server setting that closes idle sessions sooner would end the lock mid-work. `0`
/// (disabled) and any value of at least 150 s pass.
final class IdleSessionTimeout {
    /// Milliseconds per unit Postgres reports (`ms`, `s`, `min`, `h`, `d`).
    private static final Map<String, Long> UNITS = Map.of("ms",
                                                          1L,
                                                          "s",
                                                          1000L,
                                                          "min",
                                                          60_000L,
                                                          "h",
                                                          3_600_000L,
                                                          "d",
                                                          86_400_000L);

    private static final long MINIMUM_MILLIS = StoreError.IdleSessionTimeout.MINIMUM_SECONDS * 1000;

    private IdleSessionTimeout() {}

    static Result<Unit> check(String reported) {
        return millis(reported.strip()).flatMap(millis -> millis > 0 && millis < MINIMUM_MILLIS
                                                          ? StoreError.IdleSessionTimeout.FACTORY.apply(reported.strip(),
                                                                                                        Long.toString(StoreError.IdleSessionTimeout.MINIMUM_SECONDS))
                                                                                                 .<Unit> result()
                                                          : Result.unitResult());
    }

    static Result<Long> millis(String text) {
        var split = 0;

        while (split < text.length() && Character.isDigit(text.charAt(split))) {
            split++;
        }

        var unit = text.substring(split);

        return split == 0 || split > 15 || (!unit.isEmpty() && !UNITS.containsKey(unit))
               ? Causes.cause("Unreadable idle_session_timeout: " + text).<Long> result()
               : Result.success(Long.parseLong(text.substring(0, split)) * UNITS.getOrDefault(unit, 1L));
    }
}
