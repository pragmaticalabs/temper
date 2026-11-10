package org.pragmatica.kiln.service.store.pg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assumptions;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/// temper-store's `PgFixture` for kiln: one `postgres:16` container per test JVM, started with `-c timezone=UTC` as
/// kiln-pg runs (`ops/pg-up.sh`). Every test gets its own freshly created database, migrated by the production
/// [PgKilnStore#connect], so tests never share rows. Without docker the tests are skipped (reported as skipped, never as
/// passed).
public final class KilnPgFixture {
    static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();
    private static final String USER = "kiln";
    private static final String PASSWORD = "kiln-test";
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static GenericContainer<?> container;
    private static Db admin;

    private KilnPgFixture() {}

    static synchronized DbConfig freshDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "docker is not available");
        if (container == null) {
            container = new GenericContainer<>("postgres:16")
                .withEnv("POSTGRES_USER", USER)
                .withEnv("POSTGRES_PASSWORD", PASSWORD)
                .withEnv("POSTGRES_DB", "kiln")
                .withCommand("postgres", "-c", "timezone=UTC", "-c", "max_connections=300")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
            container.start();
            admin = Db.db(builder("kiln").pool());
        }
        var name = "k" + DATABASES.incrementAndGet() + "_" + ProcessHandle.current().pid();

        admin.executor().completeScript("CREATE DATABASE " + name).await(WAIT).unwrap();
        return config(name);
    }

    /// A fresh, empty database's connection settings, for a test that opens the store itself (WP9 `MainIT`).
    public static DbConfig freshConfig() {
        return freshDatabase();
    }

    /// The `host_pass` rows as JSON text, ordered, for the ITs of other packages (the scan pipeline, WP8).
    public static List<String> hostPass(KilnStore store) {
        return StoreFixtures.dump(store, "host_pass");
    }

    /// A migrated store with the store ITs' change rule, for the ITs of other packages (the scan pipeline, WP8).
    public static KilnStore freshStore() {
        return freshStore(StoreFixtures.DIFF);
    }

    /// A migrated store whose PR and check writes record changes with `diff`, stamped from a [#ticking] clock.
    static KilnStore freshStore(ChangeDiff diff) {
        return PgKilnStore.connect(freshDatabase(), StoreFixtures.REPO, StoreFixtures.highWater(), diff, ticking()).await(WAIT).unwrap();
    }

    /// A clock one second later at each read, from [StoreFixtures#RECORDED]: successive change writes get distinct,
    /// ordered `recordedAt` stamps whatever the wall clock does.
    static Supplier<Instant> ticking() {
        var next = new AtomicReference<>(StoreFixtures.RECORDED);

        return () -> next.getAndUpdate(at -> at.plusSeconds(1));
    }

    /// A real `pg_dump -Fc` of `config`'s database into `path` inside the container (the verifier's R2-F1 method).
    static void dump(DbConfig config, String path) {
        exec("pg_dump", "-U", USER, "-Fc", "-f", path, config.database());
    }

    /// A real `pg_restore --clean` of `path` into `config`'s database, with nothing restarted: every object is dropped
    /// and recreated as dumped, its sequence included.
    static void restore(DbConfig config, String path) {
        exec("pg_restore", "--clean", "-U", USER, "-d", config.database(), path);
    }

    private static void exec(String... command) {
        try {
            var result = container.execInContainer(command);

            if (result.getExitCode() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " exited " + result.getExitCode() + ": " + result.getStderr());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static Db db(DbConfig config) {
        return Db.db(builder(config.database()).pool());
    }

    static DbConfig config(String database) {
        return DbConfig.dbConfig(container.getHost(), container.getMappedPort(5432), database, USER, PASSWORD, 10)
                       .unwrap();
    }

    private static NettyConnectibleBuilder builder(String database) {
        var builder = new NettyConnectibleBuilder();

        builder.hostname(container.getHost())
               .port(container.getMappedPort(5432))
               .database(database)
               .username(USER)
               .password(PASSWORD)
               .maxConnections(10);
        return builder;
    }
}
