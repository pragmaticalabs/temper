package org.pragmatica.temper.controller.it;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.evidence.EvidenceLayout;
import org.pragmatica.temper.controller.maintenance.PgDump;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.Store;

import static org.assertj.core.api.Assertions.assertThat;

/// `PgDumpIT` (m1-design §3.4, §9.5, it-pg): `docker exec` into the test's Postgres container dumps the database,
/// `pg_restore --list` verifies it, and only a verified dump is published; a corrupted dump is recorded `ok = false`
/// and leaves no file behind.
@Tag("pg")
class PgDumpIT {
    @TempDir
    Path root;

    /// Dumps a migrated database, so its archive has a table of contents to verify.
    private PgDump dump(ProcessRunner runner, Store store) {
        return PgDump.pgDump(runner, EvidenceLayout.evidenceLayout(root), Pg.containerName(), "temper", Pg.migratedDatabase(),
                             store.maintenance(), Instant::now);
    }

    @Test
    void dump_publishesAVerifiedDump_andRecordsAGoodRun() throws Exception {
        try (var store = Pg.freshStore()) {
            var run = dump(ProcessRunner.processRunner(), store).dump().await(Pg.WAIT).unwrap();

            assertThat(run.ok()).as(run.detail().or("")).isTrue();
            var file = run.path().unwrap();
            assertThat(file.getFileName().toString()).matches(PgDump.DUMP.pattern());
            assertThat(Files.size(file)).isEqualTo(run.bytes().unwrap()).isPositive();
            assertThat(Files.list(root.resolve("pgdump"))).containsExactly(file);
            assertThat(store.maintenance().lastGood(MaintenanceKind.PG_DUMP).await().unwrap().flatMap(found -> found.path()))
                .isEqualTo(Option.some(file));
        }
    }

    /// bigboy, 2026-10-15: `pg_restore --list` exits after the table of contents, so feeding it a dump larger than the
    /// pipe buffers ends in a broken pipe; the dump is still verified by the exit code and the TOC, and published.
    @Test
    void dump_largerThanThePipeBuffers_isPublished() throws Exception {
        try (var store = Pg.freshStore()) {
            var database = Pg.migratedDatabase();
            psql(database, "CREATE TABLE bulk AS SELECT md5(random()::text) || md5(random()::text) AS v FROM generate_series(1, 200000)");

            var run = PgDump.pgDump(ProcessRunner.processRunner(), EvidenceLayout.evidenceLayout(root), Pg.containerName(), "temper",
                                    database, store.maintenance(), Instant::now)
                            .dump()
                            .await(Pg.WAIT)
                            .unwrap();

            assertThat(run.ok()).as(run.detail().or("")).isTrue();
            assertThat(run.bytes().unwrap()).as("the dump must outgrow the pipe buffers").isGreaterThan(4L * 1024 * 1024);
            assertThat(Files.list(root.resolve("pgdump"))).containsExactly(run.path().unwrap());
        }
    }

    private static void psql(String database, String sql) throws Exception {
        var process = HermeticProcess.builder("docker", "exec", Pg.containerName(), "psql", "-U", "temper", "-d", database,
                                         "-v", "ON_ERROR_STOP=1", "-qc", sql).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());

        assertThat(process.waitFor()).as(output).isZero();
    }

    @Test
    void dump_corrupted_isNotPublished_andRecordedNotOk() throws Exception {
        try (var store = Pg.freshStore()) {
            var run = dump(new Corrupting(ProcessRunner.processRunner()), store).dump().await(Pg.WAIT).unwrap();

            assertThat(run.ok()).isFalse();
            assertThat(run.detail().or("")).contains("pg_restore");
            assertThat(Files.list(root.resolve("pgdump"))).isEmpty();
            assertThat(store.maintenance().lastGood(MaintenanceKind.PG_DUMP).await().unwrap().isEmpty()).isTrue();
        }
    }

    /// A database without a single object dumps an archive whose TOC is only comments: that dump is refused.
    @Test
    void dump_ofAnEmptyDatabase_isRefused_forItsEmptyToc() throws Exception {
        try (var store = Pg.freshStore()) {
            var run = PgDump.pgDump(ProcessRunner.processRunner(), EvidenceLayout.evidenceLayout(root), Pg.containerName(), "temper",
                                    "temper", store.maintenance(), Instant::now)
                            .dump()
                            .await(Pg.WAIT)
                            .unwrap();

            assertThat(run.ok()).isFalse();
            assertThat(run.detail().or("")).contains("empty table of contents");
            assertThat(Files.list(root.resolve("pgdump"))).isEmpty();
        }
    }

    /// Runs the real command, then garbles a `pg_dump` output file, so only the verification can catch it.
    record Corrupting(ProcessRunner real) implements ProcessRunner {
        @Override
        public Promise<Completed> run(Command command) {
            return real.run(command).map(completed -> corrupted(command, completed));
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            return real.spawn(command);
        }

        private static Completed corrupted(Command command, Completed completed) {
            if (command.argv().contains("pg_dump")) {
                try {
                    Files.writeString(command.stdoutFile().unwrap(), "not a dump at all");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return completed;
        }
    }
}
