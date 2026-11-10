package org.pragmatica.temper.domain.seed;

import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// A13 / R-M2: the workspace `.m2-local` must be a copy, so every file has exactly one link. Equivalent to
/// `find <repo> -type f -links +1` being empty.
public sealed interface SeedIsolation {
    int REPORTED_PATHS = 5;

    static Result<Unit> verify(List<FileLinks> files) {
        var linked = files.stream().filter(file -> file.nlink() > 1).map(FileLinks::relativePath).toList();

        return linked.isEmpty()
               ? Result.unitResult()
               : SeedError.Hardlinked.FACTORY.apply(linked.size(),
                                                    linked.subList(0,
                                                                   Math.min(REPORTED_PATHS,
                                                                            linked.size())))
                                             .result();
    }

    record unused() implements SeedIsolation {}
}
