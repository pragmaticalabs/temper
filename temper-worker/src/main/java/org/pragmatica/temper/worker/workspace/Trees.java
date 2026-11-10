package org.pragmatica.temper.worker.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.worker.WorkerError;

import static org.pragmatica.lang.Unit.unit;


/// Recursive delete of a worker-owned directory (a half-made workspace or seed copy). Symbolic links are
/// removed, never followed. A missing directory is not a failure.
public sealed interface Trees {
    static Promise<Unit> deleteRecursively(Path root) {
        return Files.exists(root, LinkOption.NOFOLLOW_LINKS)
               ? Promise.lift(thrown -> WorkerError.Io.FACTORY.apply("delete " + root,
                                                                     Causes.fromThrowable(thrown)),
                              () -> Files.walkFileTree(root,
                                                       new DeletingVisitor()))
                        .mapToUnit()
               : Promise.success(unit());
    }

    /// Deletes files on visit and directories after their content.
    final class DeletingVisitor extends SimpleFileVisitor<Path> {
        /// JDK `FileVisitor` contract: a failure is reported by throwing it (the enclosing `Promise.lift` turns it
        /// into `WorkerError.Io`).
        @Contract
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            Files.delete(file);

            return FileVisitResult.CONTINUE;
        }

        /// JDK `FileVisitor` contract: `failure` is null when the directory was read completely, and a failure is
        /// reported by throwing it (the enclosing `Promise.lift` turns it into `WorkerError.Io`).
        @Contract
        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
            if (failure != null) {
                throw failure;
            }

            Files.delete(directory);

            return FileVisitResult.CONTINUE;
        }
    }

    record unused() implements Trees {}
}
