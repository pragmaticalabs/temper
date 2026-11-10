package org.pragmatica.temper.domain.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;


/// A `-pl` entry: a reactor-relative module path, or `:artifactId`.
public record ModulePath(String value) {
    private static final Pattern PATTERN = Pattern.compile("^(?!-):?[A-Za-z0-9._/-]+$");

    /// Whether `raw` parses as a module path (a manifest's module directory is checked so before it is `-pl`'d).
    public static boolean isModulePath(String raw) {
        return modulePath(raw).isSuccess();
    }

    public static Result<ModulePath> modulePath(String raw) {
        return Values.matchingWithout(raw, PATTERN, "..", "module path").map(ModulePath::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
