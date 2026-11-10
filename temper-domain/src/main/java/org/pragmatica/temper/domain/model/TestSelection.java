package org.pragmatica.temper.domain.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;


/// A surefire/failsafe `-Dtest` / `-Dit.test` value, restricted to the surefire pattern alphabet.
public record TestSelection(String value) {
    private static final Pattern PATTERN = Pattern.compile("^(?!-)[A-Za-z0-9_*?!#,.$/-]+$");

    public static Result<TestSelection> testSelection(String raw) {
        return Values.matching(raw, PATTERN, "test selection").map(TestSelection::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
