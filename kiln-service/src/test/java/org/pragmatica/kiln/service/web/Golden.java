package org.pragmatica.kiln.service.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// Golden-file comparison: the expected HTML is `src/test/resources/web/<name>.html`, compared byte for byte. Every
/// render is also written to `target/golden-actual/<name>.html`, so a reviewer can diff a failure; a golden is never
/// written by the test itself.
final class Golden {
    private static final Path ACTUAL = Path.of("target", "golden-actual");

    private Golden() {}

    static void assertGolden(String name, String actual) {
        write(name, actual);
        assertThat(actual).as("golden " + name).isEqualTo(expected(name));
    }

    private static String expected(String name) {
        try (InputStream in = Golden.class.getResourceAsStream("/web/" + name + ".html")) {
            if (in == null) {
                return fail("no golden file web/" + name + ".html; the render is in " + ACTUAL.resolve(name + ".html"));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Writes a render to `target/golden-actual/` without comparing it: an artifact for a reviewer or a screenshot.
    static void write(String name, String actual) {
        try {
            Files.createDirectories(ACTUAL);
            Files.writeString(ACTUAL.resolve(name + ".html"), actual, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
