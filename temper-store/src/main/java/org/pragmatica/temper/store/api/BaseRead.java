package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.lang.Option;


/// What `diff <job> --base <sha>` reads in ONE snapshot ([ResultStore#diffBase]): the Gate candidates at the SHAs the
/// prefix names, and, when the caller's selection over them named a base, both sides (A the base, B the job).
public record BaseRead(List<GateRow> candidates, Option<StoredSides> sides) {
    public BaseRead {
        candidates = List.copyOf(candidates);
    }

    public static BaseRead baseRead(List<GateRow> candidates, Option<StoredSides> sides) {
        return new BaseRead(candidates, sides);
    }
}
