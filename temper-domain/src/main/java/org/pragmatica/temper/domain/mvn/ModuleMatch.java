package org.pragmatica.temper.domain.mvn;

import java.util.List;


/// Path arithmetic for matching `-pl` entries and `-f` pom paths against reactor-relative module paths.
sealed interface ModuleMatch {
    /// Whether a `-pl` entry (path, `:artifactId` or `groupId:artifactId`), resolved against the `-f` base
    /// directory, names `modulePath`. An artifactId is matched against the module directory's last segment.
    static boolean matches(String entry, String baseDir, String modulePath) {
        return entry.contains(":")
               ? lastSegment(artifactOrPath(entry)).equals(lastSegment(modulePath))
               : join(baseDir, normalize(entry)).equals(normalize(modulePath));
    }

    /// Whether `modulePath` lies inside the reactor rooted at `baseDir` (empty means the repository root).
    static boolean under(String baseDir, String modulePath) {
        var module = normalize(modulePath);

        return baseDir.isEmpty() || module.equals(baseDir) || module.startsWith(baseDir + "/");
    }

    /// Directory of a `-f` argument: the pom's parent directory, or the argument itself when it names a directory.
    static String directoryOf(String pomFile) {
        var path = normalize(pomFile);

        return path.endsWith(".xml")
               ? parentOf(path)
               : path;
    }

    /// Last `/`-separated segment of a normalized path.
    static String lastSegment(String path) {
        var normalized = normalize(path);

        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    /// The artifactId of a `:artifactId` / `groupId:artifactId` entry, or the entry itself when it is a path.
    static String artifactOrPath(String entry) {
        return entry.substring(entry.lastIndexOf(':') + 1);
    }

    /// Whether `segment` is one of the `/`-separated segments of `path`.
    static boolean hasSegment(String path, String segment) {
        return List.of(normalize(path).split("/")).contains(segment);
    }

    private static String parentOf(String path) {
        return path.contains("/")
               ? path.substring(0, path.lastIndexOf('/'))
               : "";
    }

    private static String join(String baseDir, String entry) {
        return baseDir.isEmpty()
               ? entry
               : normalize(baseDir + "/" + entry);
    }

    /// `path` without `./` prefixes, repeated or trailing slashes; `.` and the empty path are the root (`""`).
    static String normalize(String path) {
        var trimmed = path.trim().replaceAll("/+", "/").replaceAll("^(\\./)+", "").replaceAll("/$", "");

        return trimmed.equals(".")
               ? ""
               : trimmed;
    }

    record unused() implements ModuleMatch {}
}
