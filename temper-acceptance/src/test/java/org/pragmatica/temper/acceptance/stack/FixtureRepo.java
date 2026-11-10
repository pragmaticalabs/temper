package org.pragmatica.temper.acceptance.stack;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/// The fixture project of this module's test resources as a git origin (a bare repository): `main` is
/// `manifest-fixture/`, and each overlay of `manifest-fixture-overlays/<name>/` is a branch `<name>` = `main` plus
/// the overlay's files (an overlay with a `legacy/` or `hold/` module adds it to the root pom's `<modules>`).
public final class FixtureRepo {
    public static final String MAIN = "main";
    private static final String FIXTURE = "manifest-fixture";
    private static final String OVERLAYS = "manifest-fixture-overlays";
    private static final List<String> ADDED_MODULES = List.of("legacy", "hold");

    private final Path origin;
    private final Map<String, String> shas;

    private FixtureRepo(Path origin, Map<String, String> shas) {
        this.origin = origin;
        this.shas = Map.copyOf(shas);
    }

    /// Builds the repository under `dir` with the given overlays as branches.
    public static FixtureRepo create(Path dir, List<String> overlays) throws Exception {
        var work = dir.resolve("fixture-src");
        var shas = new LinkedHashMap<String, String>();

        copyTree(resource(FIXTURE), work);
        git(work, "init", "-q", "-b", MAIN);
        git(work, "add", "-A");
        git(work, "-c", "user.name=acceptance", "-c", "user.email=acceptance@temper", "commit", "-q", "-m", "fixture");
        shas.put(MAIN, git(work, "rev-parse", "HEAD"));
        for (var overlay : overlays) {
            git(work, "checkout", "-q", "-b", overlay, MAIN);
            copyTree(resource(OVERLAYS + "/" + overlay), work);
            addModules(work, overlay);
            git(work, "add", "-A");
            git(work, "-c", "user.name=acceptance", "-c", "user.email=acceptance@temper", "commit", "-q", "-m", overlay);
            shas.put(overlay, git(work, "rev-parse", "HEAD"));
        }
        git(work, "checkout", "-q", MAIN);
        var origin = dir.resolve("origin.git");
        git(dir, "clone", "-q", "--bare", work.toString(), origin.toString());
        return new FixtureRepo(origin, shas);
    }

    public Path origin() {
        return origin;
    }

    public String sha(String branch) {
        var sha = shas.get(branch);
        if (sha == null) {
            throw new IllegalArgumentException("no fixture branch " + branch);
        }
        return sha;
    }

    private static void addModules(Path work, String overlay) throws IOException {
        var pom = work.resolve("pom.xml");
        var text = Files.readString(pom);
        for (var module : ADDED_MODULES) {
            if (Files.isDirectory(work.resolve(module)) && !text.contains("<module>" + module + "</module>")) {
                text = text.replace("        <module>disabled</module>\n",
                                    "        <module>disabled</module>\n        <module>" + module + "</module>\n");
            }
        }
        Files.writeString(pom, text);
    }

    private static Path resource(String name) throws URISyntaxException {
        var url = FixtureRepo.class.getResource("/" + name);
        if (url == null) {
            throw new IllegalStateException("missing test resource " + name);
        }
        return Path.of(url.toURI());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (var source : walk.toList()) {
                var target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /// Runs git in `dir` (no user config, no prompts) and answers its trimmed stdout.
    static String git(Path dir, String... args) throws IOException, InterruptedException {
        var argv = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        var builder = HermeticProcess.builder(argv).directory(dir.toFile())
                                              .redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Files.createDirectories(dir);
        var process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", argv) + " failed: " + output);
        }
        return output.strip();
    }
}
