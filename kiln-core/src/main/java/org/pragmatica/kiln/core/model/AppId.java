package org.pragmatica.kiln.core.model;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.kiln.core.model.ModelError.invalid;


/// A GitHub app id (`app_id` of a required check, `app.id` of a check run): 1 or more.
public record AppId(long value) {
    public static Result<AppId> appId(long raw) {
        return Verify.ensure(raw, Verify.Is::positive, invalid("app id")).map(AppId::new);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
