package org.pragmatica.temper.controller.maintenance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.evidence.EvidenceLayout;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.MaintenanceStore;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// The `pg_dump` verification's second half (m1-design §3.4): `pg_restore --list` exiting 0 is not enough, the table of
/// contents must name an archive entry. A stubbed runner isolates that check from the exit code.
class PgDumpTest {
    @TempDir
    Path root;
    private final List<MaintenanceRun> runs = new CopyOnWriteArrayList<>();

    private final MaintenanceStore store = new MaintenanceStore() {
        @Override
        public Promise<Long> record(MaintenanceRun run) {
            runs.add(run);
            return Promise.success((long) runs.size());
        }

        @Override
        public Promise<Option<MaintenanceRun>> lastGood(MaintenanceKind kind) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Option<MaintenanceRun>> latest(MaintenanceKind kind) {
            return Promise.success(Option.none());
        }
    };

    private MaintenanceRun dumpWithToc(String toc) {
        return dumpWith(new Stub(toc, new Exited(0, OutputStream.nullOutputStream())));
    }

    private MaintenanceRun dumpWith(Stub stub) {
        return PgDump.pgDump(stub, EvidenceLayout.evidenceLayout(root), "temper-pg", "temper", "temper", store, Instant::now)
                     .dump()
                     .await()
                     .unwrap();
    }

    /// `pg_restore --list` exits once it has read the TOC: the rest of a large dump meets a closed pipe.
    private static OutputStream brokenPipe() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("Broken pipe");
            }
        };
    }

    @Test
    void dump_isRefused_whenTheTocHasOnlyComments() throws Exception {
        var run = dumpWithToc(";\n; Archive created at 2026-10-13\n;     dbname: temper\n;\n");

        assertThat(run.ok()).isFalse();
        assertThat(run.detail().or("")).contains("empty table of contents");
        assertThat(Files.list(root.resolve("pgdump"))).isEmpty();
    }

    @Test
    void dump_isPublished_whenTheTocNamesAnEntry() throws Exception {
        var run = dumpWithToc(";\n; Archive created at 2026-10-13\n216; 1259 16385 TABLE public jobs temper\n");

        assertThat(run.ok()).as(run.detail().or("")).isTrue();
        assertThat(Files.list(root.resolve("pgdump"))).containsExactly(run.path().unwrap());
    }

    @Test
    void dump_isPublished_whenPgRestoreStopsReadingAfterTheToc_andExitsZero() throws Exception {
        var run = dumpWith(new Stub(";\n216; 1259 16385 TABLE public jobs temper\n", new Exited(0, brokenPipe())));

        assertThat(run.ok()).as(run.detail().or("")).isTrue();
        assertThat(Files.list(root.resolve("pgdump"))).containsExactly(run.path().unwrap());
    }

    /// The outer step message alone hid the I/O error (bigboy, Deploy 6); the line carries the whole chain.
    @Test
    void stoppedReadingLine_namesTheChainedIoError() {
        var logged = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(PgDump.class);
        logged.start();
        logger.addAppender(logged);
        try {
            dumpWith(new Stub(";\n216; 1259 16385 TABLE public jobs temper\n", new Exited(0, brokenPipe())));
        } finally {
            logger.detachAppender(logged);
        }

        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.INFO)
                               .extracting(ILoggingEvent::getFormattedMessage)
                               .filteredOn(line -> line.contains("stopped reading"))
                               .isNotEmpty()
                               .allSatisfy(line -> assertThat(line).contains("Broken pipe"));
    }

    @Test
    void dump_isRefused_whenTheStdinBreaks_andPgRestoreExitsNonZero() throws Exception {
        var run = dumpWith(new Stub(";\n216; 1259 16385 TABLE public jobs temper\n", new Exited(1, brokenPipe())));

        assertThat(run.ok()).isFalse();
        assertThat(run.detail().or("")).contains("exit 1");
        assertThat(Files.list(root.resolve("pgdump"))).isEmpty();
    }

    /// `pg_dump` writes some bytes and exits 0; `pg_restore --list` writes `toc` and ends as `listing`.
    record Stub(String toc, Exited listing) implements ProcessRunner {
        @Override
        public Promise<Completed> run(Command command) {
            write(command.stdoutFile().unwrap(), "PGDMP-archive");
            return Promise.success(Completed.completed("docker", 0, new byte[0], new byte[0], false));
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            write(command.stdoutFile().unwrap(), toc);
            return Result.success(listing);
        }

        private static void write(Path file, String text) {
            try {
                Files.writeString(file, text);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    record Exited(int rc, OutputStream stdin) implements Spawned {
        @Override
        public InputStream stdout() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream stdin() {
            return stdin;
        }

        @Override
        public InputStream stderr() {
            return InputStream.nullInputStream();
        }

        @Override
        public Promise<Integer> exit() {
            return Promise.success(rc);
        }

        @Override
        public Unit kill() {
            return unit();
        }

        @Override
        public long pid() {
            return 1;
        }
    }
}
