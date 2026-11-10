package org.pragmatica.temper.domain.task;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Values;


/// Why a worker released its lease (T7): a short token [G], such as `no-evidence` or `budget`. Every reason but
/// `budget` counts as a worker failure of the visit (§6.2, §14.3).
public record ReleaseReason(String value) {
    public static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
    public static final String BUDGET = "budget";

    public static Result<ReleaseReason> releaseReason(String raw) {
        return Values.matching(raw, PATTERN, "reason").map(ReleaseReason::new);
    }

    public boolean isBudget() {
        return BUDGET.equals(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
