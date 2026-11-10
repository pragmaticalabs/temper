package org.pragmatica.temper.controller.it;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.temper.store.pg.Postgres;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/// One `postgres:16` container per test JVM (`-c timezone=UTC`, as in production, m0-design §5.1); every caller
/// gets a freshly created database migrated by the production `Postgres.connect`. Without docker the tests are
/// skipped (reported as skipped, never as passed).
final class Pg {
    static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();
    private static final String USER = "temper";
    private static final String PASSWORD = "temper-test";
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static GenericContainer<?> container;
    private static Connectible admin;

    private Pg() {}

    /// A fresh store and its database's name (for `docker exec` dumps and restores of that database).
    record Named(Store store, String database) {}

    static synchronized Store freshStore() {
        return freshNamed().store();
    }

    static synchronized Named freshNamed() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "docker is not available");
        if (container == null) {
            container = new GenericContainer<>("postgres:16")
                .withEnv("POSTGRES_USER", USER)
                .withEnv("POSTGRES_PASSWORD", PASSWORD)
                .withEnv("POSTGRES_DB", "temper")
                .withCommand("postgres", "-c", "timezone=UTC", "-c", "max_connections=300")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
            container.start();
            admin = builder("temper");
        }
        var name = "c" + DATABASES.incrementAndGet() + "_" + ProcessHandle.current().pid();
        admin.completeScript("CREATE DATABASE " + name).await(WAIT).unwrap();
        return new Named(Postgres.connect(DbConfig.dbConfig(container.getHost(), container.getMappedPort(5432), name, USER, PASSWORD, 10)
                                                  .unwrap(),
                                             Judge.UNJUDGED)
                                 .await(WAIT)
                                 .unwrap(),
                         name);
    }

    /// The container's name, for `docker exec` (the `pg_dump` IT); starts the container when needed.
    static synchronized String containerName() {
        freshStore().close();
        return container.getContainerName().replaceFirst("^/", "");
    }

    /// The name of a database the production migrations were applied to (it has every table).
    static synchronized String migratedDatabase() {
        freshStore().close();
        return "c" + DATABASES.get() + "_" + ProcessHandle.current().pid();
    }

    /// Runs `sql` on `database` (rows no route writes yet, e.g. GO rows before WP-A2) and answers the first row's `v`
    /// column, empty when the statement returns no such row.
    static synchronized String sql(String database, String sql) {
        var pool = builder(database);
        try {
            var resultSet = pool.completeQuery(sql).await(WAIT).unwrap();
            return resultSet.size() == 0 || !resultSet.columnsByName().containsKey("v")
                   ? ""
                   : String.valueOf(resultSet.index(0).getString("v"));
        } finally {
            pool.close().await(WAIT);
        }
    }

    private static Connectible builder(String database) {
        var builder = new NettyConnectibleBuilder();
        builder.hostname(container.getHost())
               .port(container.getMappedPort(5432))
               .database(database)
               .username(USER)
               .password(PASSWORD)
               .maxConnections(4);
        return builder.pool();
    }
}
