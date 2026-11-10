package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// The bb host config the ops scripts read (`~/.config/bb-temper/hosts`, format `ops/bb/hosts.example`), as the tests write it
/// into their sandbox HOME. Every address is a documentation address (TEST-NET, RFC 5737); cloudbb-d1 is the explicit-only host.
final class TestHosts {
    static final String BIGBOY = "builder@192.0.2.10";
    static final String C3 = "builder@198.51.100.3";
    static final String C4 = "builder@198.51.100.4";
    static final String D1 = "builder@198.51.100.11";
    /// The key and known-hosts paths as the config writes them; the scripts expand `~/` to HOME.
    static final String KEY = ".ssh/id_test";
    static final String KNOWN = ".config/bb-temper/known_hosts";
    static final String TEXT = """
            # test host config (TEST-NET addresses)
            key ~/%s
            host bigboy     %s  -  -  auto
            host cloudbb-3  %s  %s  ~/%s  auto
            host cloudbb-4  %s  %s  ~/%s  auto
            host cloudbb-d1 %s  %s  ~/%s  explicit
            """.formatted(KEY, BIGBOY, C3, BIGBOY, KNOWN, C4, BIGBOY, KNOWN, D1, BIGBOY, KNOWN);

    private TestHosts() {}

    /// Writes the config at its default place under `home` and returns its path.
    static Path write(Path home) throws IOException {
        return Files.writeString(Files.createDirectories(home.resolve(".config/bb-temper")).resolve("hosts"), TEXT);
    }
}
