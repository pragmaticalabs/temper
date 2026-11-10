package org.pragmatica.temper.acceptance.stack;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.temper.store.pg.Postgres;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/// One `postgres:16` container per test JVM (`-c timezone=UTC`, as in production); each stack gets a fresh database
/// migrated by the production `Postgres.connect`, whose job ids start at `firstJob` so container and network names
/// (`temper-<job>-<unit>`) never meet those of an earlier run.
final class Pg {
    static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();
    private static final String USER = "temper";
    private static final String PASSWORD = "temper-test";
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static GenericContainer<?> container;
    private static Connectible admin;

    private Pg() {}

    record Opened(Store store, String database) {}

    /// Where an empty database of the same container is reached, for a store other than temper's (kiln's).
    record Empty(String host, int port, String database, String user, String password) {}

    static synchronized Opened freshStore(long firstJob) {
        var name = freshDatabase();
        var store = Postgres.connect(DbConfig.dbConfig(container.getHost(), container.getMappedPort(5432), name, USER, PASSWORD, 10)
                                             .expect("acceptance setup"),
                                    Judge.UNJUDGED)
                            .await(WAIT)
                            .expect("acceptance setup");
        var direct = builder(name);
        direct.completeScript("ALTER SEQUENCE jobs_id_seq RESTART WITH " + firstJob).await(WAIT).expect("acceptance setup");
        direct.close().await(WAIT);
        return new Opened(store, name);
    }

    /// A fresh database, not migrated: its owner's own `connect` migrates it (kiln's `KilnPostgres.connect`).
    static synchronized Empty emptyDatabase() {
        return new Empty(container().getHost(), container().getMappedPort(5432), freshDatabase(), USER, PASSWORD);
    }

    private static GenericContainer<?> container() {
        if (container == null) {
            container = new GenericContainer<>("postgres:16").withEnv("POSTGRES_USER", USER)
                                                            .withEnv("POSTGRES_PASSWORD", PASSWORD)
                                                            .withEnv("POSTGRES_DB", "temper")
                                                            .withCommand("postgres", "-c", "timezone=UTC", "-c", "max_connections=300")
                                                            .withExposedPorts(5432)
                                                            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
            container.start();
            admin = builder("temper");
        }
        return container;
    }

    private static String freshDatabase() {
        container();
        var name = "acc" + DATABASES.incrementAndGet() + "_" + ProcessHandle.current().pid();
        admin.completeScript("CREATE DATABASE " + name).await(WAIT).expect("acceptance setup");
        return name;
    }

    /// The same database again (a controller restart keeps its store).
    static Store reopen(String database) {
        return Postgres.connect(DbConfig.dbConfig(container.getHost(), container.getMappedPort(5432), database, USER, PASSWORD, 10)
                                        .expect("acceptance setup"),
                               Judge.UNJUDGED)
                       .await(WAIT)
                       .expect("acceptance setup");
    }

    /// One statement through `psql` in the container, for rows the store's API does not write or read (m4-design
    /// §5.2's pre-M4 `external` row); its unaligned, tuples-only output.
    static String psql(String database, String sql) {
        try {
            var result = container.execInContainer("psql", "-q", "-U", USER, "-d", database, "-v", "ON_ERROR_STOP=1", "-tAc", sql);
            if (result.getExitCode() != 0) {
                throw new AssertionError("psql " + sql + ": " + result.getStderr());
            }
            return result.getStdout().strip();
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("psql " + sql, e);
        }
    }

    /// `pg_dump -Fc` of `database` to `file` inside the container, and `pg_restore --clean` of it back (a restore, as
    /// an operator runs it).
    static void dump(String database, String file) {
        exec("pg_dump", "-U", USER, "-Fc", "-d", database, "-f", file);
    }

    static void restore(String database, String file) {
        exec("pg_restore", "-U", USER, "--clean", "--if-exists", "-d", database, file);
    }

    private static void exec(String... argv) {
        try {
            var result = container.execInContainer(argv);
            if (result.getExitCode() != 0) {
                throw new AssertionError(String.join(" ", argv) + ": " + result.getStderr());
            }
        } catch (IOException | InterruptedException e) {
            throw new AssertionError(String.join(" ", argv), e);
        }
    }

    private static Connectible builder(String database) {
        var builder = new NettyConnectibleBuilder();
        builder.hostname(container.getHost())
               .port(container.getMappedPort(5432))
               .database(database)
               .username(USER)
               .password(PASSWORD)
               .maxConnections(2);
        return builder.pool();
    }
}
