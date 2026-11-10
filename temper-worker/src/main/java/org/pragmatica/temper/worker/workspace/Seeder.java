package org.pragmatica.temper.worker.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.worker.WorkerError;


/// Copies the host seed repository into a workspace (A13, R-M2): **copy, never hardlink**. An in-place install
/// through a hard link would write the seed's original, so the workspace repository must hold its own inodes.
@FunctionalInterface
public interface Seeder {
    /// Copies the tree at `seed` to `target` (which must not exist yet).
    Promise<Unit> copy(Path seed, Path target);

    /// The production seeder: a NIO walk with `Files.copy(…, COPY_ATTRIBUTES)`. A plain copy always creates a
    /// new inode, so every copied file has link count 1. Chosen over `cp -a --reflink=auto` for portability
    /// (m0-design §7.3); symbolic links are copied as links, never followed.
    static Seeder copying() {
        return (seed, target) -> Promise.lift(thrown -> WorkerError.Io.FACTORY.apply("copy the seed " + seed
                                                                                    + " to " + target,
                                                                                     Causes.fromThrowable(thrown)),
                                              () -> Files.walkFileTree(seed,
                                                                       new CopyingVisitor(seed, target)))
                                        .mapToUnit();
    }

    /// Mirrors directories, copies files and symbolic links. Thread-confined to one copy.
    final class CopyingVisitor extends SimpleFileVisitor<Path> {
        private final Path seed;
        private final Path target;

        CopyingVisitor(Path seed, Path target) {
            this.seed = seed;
            this.target = target;
        }

        /// JDK `FileVisitor` contract: a failure is reported by throwing it (the enclosing `Promise.lift` turns it
        /// into `WorkerError.Io`).
        @Contract
        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            Files.createDirectories(target.resolve(seed.relativize(directory).toString()));

            return FileVisitResult.CONTINUE;
        }

        /// JDK `FileVisitor` contract, as above.
        @Contract
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            Files.copy(file,
                       target.resolve(seed.relativize(file).toString()),
                       StandardCopyOption.COPY_ATTRIBUTES,
                       LinkOption.NOFOLLOW_LINKS);

            return FileVisitResult.CONTINUE;
        }
    }
}
