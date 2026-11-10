package org.pragmatica.kiln.service;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.pg.KilnPgFixture;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// [Main]'s start path against a real kiln-pg (docker), from a real `kiln.toml` and secret files, with fake ports, so
/// GitHub, temper, git and ssh are never reached: the config and secrets are read, the store opened (migrations run), kiln
/// assembled and served on the configured loopback port; and a start whose port is taken fails, naming the cause.
@Tag("pg")
class MainIT {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();

    @TempDir
    Path home;

    private Path config(DbConfig db, int port) throws Exception {
        var dir = Files.createDirectories(home.resolve(".config/kiln"));

        for (var secret : java.util.Map.of("pg-password", db.password(), "read-token", "temper-read").entrySet()) {
            var file = Files.writeString(dir.resolve(secret.getKey()), secret.getValue());

            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        return Files.writeString(home.resolve("kiln.toml"), """
            [server]
            port = %d
            [db]
            host = "%s"
            port = %d
            name = "%s"
            user = "%s"
            passwordFile = "~/.config/kiln/pg-password"
            [github]
            repo = "o/r"
            bases = ["main"]
            anonymous = true
            [mirror]
            origin = "https://github.com/o/r.git"
            [temper]
            url = "http://127.0.0.1:9"
            readTokenFile = "~/.config/kiln/read-token"
            """.formatted(port, db.host(), db.port(), db.database(), db.user()));
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void start_servesTheConfiguredPort_overARealStore() throws Exception {
        var port = freePort();
        var started = Main.start(config(KilnPgFixture.freshConfig(), port), home, _ -> Result.success(KilnStartTest.failingPorts()))
                          .await(WAIT)
                          .unwrap();

        try {
            assertThat(started.running().port()).isEqualTo(port);
            assertThat(HttpClient.newHttpClient()
                                 .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/sources")).build(),
                                       HttpResponse.BodyHandlers.ofString())
                                 .statusCode()).isEqualTo(200);
            assertThat(started.store().changes().watermark()).isEqualTo(Instant.EPOCH);
        } finally {
            started.running().stop().await(WAIT);
            started.store().close().await(WAIT);
        }
    }

    @Test
    void start_failsWhenThePortIsTaken() throws Exception {
        try (var taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var started = Main.start(config(KilnPgFixture.freshConfig(), taken.getLocalPort()), home, _ -> Result.success(KilnStartTest.failingPorts()))
                              .await(WAIT);

            assertThat(started.isFailure()).isTrue();
        }
    }
}
