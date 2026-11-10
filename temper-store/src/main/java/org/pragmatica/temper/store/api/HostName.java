package org.pragmatica.temper.store.api;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Values;


/// A configured host's name (`hosts.name`); the alphabet is a guess [G].
public record HostName(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public static Result<HostName> hostName(String raw) {
        return Values.matching(raw, PATTERN, "host name").map(HostName::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
