package org.pragmatica.temper.worker.workspace;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.seed.FileLinks;
import org.pragmatica.temper.domain.seed.SeedIsolation;
import org.pragmatica.temper.worker.WorkerError;

import static org.pragmatica.lang.Promise.promise;


/// A13 / R-M2 check of a workspace repository: no symbolic links, and every regular file must have link count 1, the equivalent of
/// `find <repo> -type f -links +1` being empty. The walk reads `unix:nlink` without following links; the
/// verdict is [SeedIsolation#verify]'s (`SeedError.Hardlinked`, reported as `seed-isolation`).
public sealed interface SeedVerifier {
    static Promise<Unit> verify(Path repository) {
        return promise(() -> withoutSymlinks(repository)).flatMap(_ -> promise(() -> linkCounts(repository)))
                      .flatMap(SeedVerifier::isolated);
    }

    /// No symbolic link anywhere in the repository: the copy keeps links as links, and a link could lead a unit's
    /// install back into the seed.
    static Result<Unit> withoutSymlinks(Path repository) {
        return FileOps.walk(repository, Files::isSymbolicLink)
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("look for symbolic links under " + repository,
                                                                       origin))
                      .flatMap(links -> noLinks(repository, links));
    }

    private static Result<Unit> noLinks(Path repository, List<Path> links) {
        return links.isEmpty()
               ? Result.unitResult()
               : WorkerError.SymlinkedSeed.FACTORY.apply(links.size(),
                                                         links.stream()
                                                              .limit(SeedIsolation.REPORTED_PATHS)
                                                              .map(link -> repository.relativize(link)
                                                                                     .toString())
                                                              .toList())
                                                  .result();
    }

    private static Promise<Unit> isolated(List<FileLinks> files) {
        return SeedIsolation.verify(files).async();
    }

    /// Every regular file under `repository` with its link count.
    static Result<List<FileLinks>> linkCounts(Path repository) {
        return FileOps.walk(repository,
                            path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                      .map(paths -> linksOf(repository, paths))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("read link counts under " + repository, origin));
    }

    private static List<FileLinks> linksOf(Path repository, List<Path> paths) {
        return paths.stream()
                    .map(path -> FileLinks.fileLinks(repository.relativize(path).toString(),
                                                     nlink(path)))
                    .toList();
    }

    /// Design-out: an unreadable link count is reported as `Integer.MAX_VALUE`, so an unknown count fails the
    /// isolation check (naming the file) instead of passing it; the failure is converted, never dropped.
    private static int nlink(Path file) {
        return Result.lift(Causes::fromThrowable,
                           () -> (Integer) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                     .or(Integer.MAX_VALUE);
    }

    record unused() implements SeedVerifier {}
}
