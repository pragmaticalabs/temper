package org.pragmatica.temper.domain.seed;

/// A regular file of the unit workspace's `.m2-local` with its hard-link count (`unix:nlink`).
public record FileLinks(String relativePath, int nlink) {
    public static FileLinks fileLinks(String relativePath, int nlink) {
        return new FileLinks(relativePath, nlink);
    }
}
