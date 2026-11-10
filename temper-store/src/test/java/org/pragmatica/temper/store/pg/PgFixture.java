package org.pragmatica.temper.store.pg;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.Store;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/// One `postgres:16` container per test JVM, started with `-c timezone=UTC` as in production (m0-design §5.1).
/// Every test class gets its own freshly created database, migrated by the production [Postgres#connect], so tests
/// never share rows. Without docker the `pg` tests are skipped (reported as skipped, never as passed).
final class PgFixture {
    static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();
    private static final String USER = "temper";
    private static final String PASSWORD = "temper-test";
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static GenericContainer<?> container;
    private static Db admin;

    private PgFixture() {}

    static synchronized DbConfig freshDatabase() {
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
            admin = Db.db(builder("temper"));
        }
        var name = "t" + DATABASES.incrementAndGet() + "_" + ProcessHandle.current().pid();

        admin.executor().completeScript("CREATE DATABASE " + name).await(WAIT).unwrap();
        return config(name);
    }

    static Store freshStore() {
        return Postgres.connect(freshDatabase(), Judge.UNJUDGED).await(WAIT).unwrap();
    }

    static Db db(DbConfig config) {
        return Db.db(builder(config.database()));
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
