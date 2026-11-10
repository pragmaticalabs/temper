package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/pg-up.sh` against a fake `docker` first on `PATH` (it logs each call's argv and answers `inspect` with
/// `FAKE_STATE`, or "no such container"): no real container is ever touched.
///
/// Q-10 (kiln-f1-design §6): `TEMPER_PG_DB` and `TEMPER_PG_USER` name kiln-pg's database and owner, and temper's
/// behaviour must not change. [#defaults_runTheSameDockerCalls_asBeforeTheParameters] pins the defaults to the docker
/// calls of the script before the change (`92a94c6c:ops/pg-up.sh`, captured with this fake: identical). Probes: keep
/// `POSTGRES_DB=temper` hard-coded → [#kilnParameters_nameTheDatabaseAndItsOwner] red; drop the identifier check →
/// [#refusesADatabaseOrUserThatIsNotAnIdentifier_beforeAnyDockerCall] red.
class PgUpIT {
    private static final Path SCRIPT = Path.of("..", "ops", "pg-up.sh").toAbsolutePath().normalize();
    private static final String FAKE_DOCKER = """
        #!/usr/bin/env bash
        printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
        case "$1" in inspect) [ -n "${FAKE_STATE:-}" ] && { echo "$FAKE_STATE"; exit 0; }; exit 1;; esac
        exit 0
        """;

    record Outcome(int rc, String output, List<String> docker) {}

    @Test
    void defaults_runTheSameDockerCalls_asBeforeTheParameters(@TempDir Path dir) throws Exception {
        var password = password(dir.resolve("home/.config/temper/pg-password"));

        var outcome = pgUp(dir, Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.docker()).containsExactly("inspect --format {{.State.Status}} temper-pg",
                                                     "run -d --name temper-pg --restart unless-stopped -v temper-pg:/var/lib/postgresql/data "
                                                     + "-p 127.0.0.1:55432:5432 -e POSTGRES_DB=temper -e POSTGRES_USER=temper "
                                                     + "-e POSTGRES_PASSWORD_FILE=/run/secrets/pw -v " + password
                                                     + ":/run/secrets/pw:ro postgres:16 -c timezone=UTC",
                                                     "exec temper-pg pg_isready -U temper -d temper -q");
        assertThat(outcome.output()).contains("pg-up: temper-pg created on 127.0.0.1:55432");
    }

    @Test
    void kilnParameters_nameTheDatabaseAndItsOwner(@TempDir Path dir) throws Exception {
        var password = password(dir.resolve("kiln-pg-password"));

        var outcome = pgUp(dir, Map.of("TEMPER_PG_NAME", "kiln-pg",
                                       "TEMPER_PG_VOLUME", "kiln-pg",
                                       "TEMPER_PG_PORT", "55442",
                                       "TEMPER_PG_DB", "kiln",
                                       "TEMPER_PG_USER", "kiln",
                                       "TEMPER_PG_PASSWORD_FILE", password.toString()));

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.docker()).containsExactly("inspect --format {{.State.Status}} kiln-pg",
                                                     "run -d --name kiln-pg --restart unless-stopped -v kiln-pg:/var/lib/postgresql/data "
                                                     + "-p 127.0.0.1:55442:5432 -e POSTGRES_DB=kiln -e POSTGRES_USER=kiln "
                                                     + "-e POSTGRES_PASSWORD_FILE=/run/secrets/pw -v " + password
                                                     + ":/run/secrets/pw:ro postgres:16 -c timezone=UTC",
                                                     "exec kiln-pg pg_isready -U kiln -d kiln -q");
    }

    /// A running container is left alone; readiness is still asked as the configured owner of the configured database.
    @Test
    void running_isLeftAlone_andAskedAsTheConfiguredUser(@TempDir Path dir) throws Exception {
        var password = password(dir.resolve("pw"));

        var outcome = pgUp(dir, Map.of("FAKE_STATE", "running",
                                       "TEMPER_PG_NAME", "kiln-pg",
                                       "TEMPER_PG_DB", "kiln",
                                       "TEMPER_PG_USER", "kiln",
                                       "TEMPER_PG_PASSWORD_FILE", password.toString()));

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.docker()).containsExactly("inspect --format {{.State.Status}} kiln-pg", "exec kiln-pg pg_isready -U kiln -d kiln -q");
        assertThat(outcome.output()).contains("pg-up: kiln-pg is running");
    }

    /// The names reach the container's environment and `pg_isready`'s argv: anything but a lower-case SQL identifier is
    /// refused before docker is asked anything.
    @Test
    void refusesADatabaseOrUserThatIsNotAnIdentifier_beforeAnyDockerCall(@TempDir Path dir) throws Exception {
        var password = password(dir.resolve("pw"));

        for (var bad : List.of(Map.of("TEMPER_PG_DB", "kiln db"),
                               Map.of("TEMPER_PG_DB", "Kiln"),
                               Map.of("TEMPER_PG_DB", "1kiln"),
                               Map.of("TEMPER_PG_USER", "kiln;x"),
                               Map.of("TEMPER_PG_USER", "-U"))) {
            var env = new HashMap<>(bad);
            env.put("TEMPER_PG_PASSWORD_FILE", password.toString());

            var outcome = pgUp(dir, env);

            assertThat(outcome.rc()).as(bad + ": " + outcome.output()).isEqualTo(1);
            assertThat(outcome.output()).as(bad.toString()).containsPattern("TEMPER_PG_(DB|USER) must be a lower-case SQL identifier");
            assertThat(outcome.docker()).as(bad.toString()).isEmpty();
        }
    }

    private static Path password(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "pw\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private static Outcome pgUp(Path dir, Map<String, String> env) throws IOException, InterruptedException {
        var bin = Files.createDirectories(dir.resolve("bin"));
        var docker = bin.resolve("docker");
        var log = dir.resolve("docker.log");
        Files.writeString(docker, FAKE_DOCKER);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"));
        Files.deleteIfExists(log);
        Files.createFile(log);
        var builder = HermeticProcess.builder(List.of("bash", SCRIPT.toString()));
        builder.environment().clear();
        builder.environment().put("HOME", Files.createDirectories(dir.resolve("home")).toString());
        builder.environment().put("PATH", bin + ":/usr/bin:/bin");
        builder.environment().put("FAKE_DOCKER_LOG", log.toString());
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var rc = process.waitFor();

        return new Outcome(rc, output, Files.readAllLines(log));
    }
}
