package org.pragmatica.temper.domain.refusal;

/// One source file at the job's SHA that declares a class named by a refusal rule. `path` is
/// repository-relative (e.g. `aether/forge/forge-tests/src/test/java/.../Foo.java`).
public record ClassSource(String path, String content) {
    public static ClassSource classSource(String path, String content) {
        return new ClassSource(path, content);
    }

    /// The owning module's directory: the part of `path` before `/src/` (empty for the root module).
    public String module() {
        var index = path.indexOf("/src/");

        if (path.startsWith("src/")) {
            return "";
        }

        return index < 0
               ? parent()
               : path.substring(0, index);
    }

    private String parent() {
        return path.contains("/")
               ? path.substring(0, path.lastIndexOf('/'))
               : "";
    }
}
