package org.pragmatica.kiln.service.scan;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;

/// The real Mac-side `ops/kiln/kiln-work` and the owner's `ops/kiln/install-kiln-work.sh`. `ssh` is a stub on `PATH`
/// that records its arguments and stdin: kiln-work names no remote command (the key's forced command picks the route),
/// sends one JSON object that reads back as exactly the given fields, never evaluates a text, and refuses a malformed
/// id, phase, round, name, instant, an unknown option or a control character before anything is sent (64). The
/// install script binds a key to `kiln-ingest work` once, backs up `authorized_keys`, and refuses a bad or a bound key.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KilnWorkIT {
    private static final Path SCRIPT = Path.of("..", "ops", "kiln", "kiln-work").toAbsolutePath().normalize();
    private static final Path INSTALL = Path.of("..", "ops", "kiln", "install-kiln-work.sh").toAbsolutePath().normalize();
    private static final Path INGEST = Path.of("..", "ops", "kiln", "kiln-ingest").toAbsolutePath().normalize();
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGq0vZm9bBf3Jk2Yb2hVvV1oJm8c1S2u0m7x8bq1yQ5n kiln-work";

    @TempDir
    Path dir;

    private Path bin;

    @BeforeEach
    void stubSsh() throws IOException {
        bin = dir.resolve("bin");
        ScanFixture.script(bin.resolve("ssh"), "printf '%s\\n' \"$@\" > \"$HOME/ssh-args\"\ncat > \"$HOME/ssh-stdin\"\necho '{\"event\":1}'\n");
        // the bb host config (ops/bb/hosts.example): kiln-work's default target is bigboy's user@address there
        Files.writeString(Files.createDirectories(dir.resolve(".config/bb-temper")).resolve("hosts"),
                          "key ~/.ssh/id_test\nhost bigboy builder@192.0.2.71 - - auto\n");
    }

    private ScanFixture.Ran work(String... args) {
        var builder = HermeticProcess.builder(Stream.concat(Stream.of("bash", SCRIPT.toString()), Stream.of(args)).toList());
        builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", bin + ":" + System.getenv("PATH"), "HOME", dir.toString(), "LC_ALL", "C.UTF-8"));
        return ScanFixture.ran(builder, "");
    }

    private Map<String, Object> sent() throws IOException {
        return MAPPER.readString(Files.readString(dir.resolve("ssh-stdin")), new TypeToken<Map<String, Object>>() {}).unwrap();
    }

    @Test
    void anEvent_isOneJsonObject_overTheWorkKey_withNoRemoteCommand() throws IOException {
        var ran = work("T1-WP5", "verify", "--round", "02", "--title", "a \"quoted\" \\ title", "--coder", "wtab", "--verifier", "t1wp5.v",
                       "--note", "it's $(touch " + dir.resolve("pwned") + ") `id` ünï", "--at", "2026-11-03T11:00:00Z");

        assertThat(ran.rc()).as(ran.stderr()).isZero();
        assertThat(ran.stdout()).isEqualTo("{\"event\":1}\n");
        assertThat(sent()).isEqualTo(Map.of("item", "T1-WP5", "phase", "verify", "round", 2, "title", "a \"quoted\" \\ title", "coder", "wtab",
                                            "verifier", "t1wp5.v", "note", "it's $(touch " + dir.resolve("pwned") + ") `id` ünï",
                                            "at", "2026-11-03T11:00:00Z"));
        assertThat(Files.readAllLines(dir.resolve("ssh-args"))).containsExactly("-i", dir.resolve(".ssh/kiln_work").toString(), "-o", "IdentitiesOnly=yes",
                                                                                "-o", "BatchMode=yes", "-T", "builder@192.0.2.71");
        assertThat(dir.resolve("pwned")).doesNotExist();
    }

    /// No address is baked into the script: without KILN_WORK_HOST and without the host config nothing is sent (exit 78).
    @Test
    void noTarget_isExit78_andNothingIsSent() throws IOException {
        Files.delete(dir.resolve(".config/bb-temper/hosts"));

        var ran = work("T1-WP5", "design", "--round", "0");

        assertThat(ran.rc()).isEqualTo(78);
        assertThat(ran.stderr()).contains("no target");
        assertThat(dir.resolve("ssh-stdin")).doesNotExist();
    }

    @Test
    void theRequiredFieldsAlone_areSent() throws IOException {
        assertThat(work("T2-WP-0T", "in-pr", "--round", "0").rc()).isZero();
        assertThat(sent()).isEqualTo(Map.of("item", "T2-WP-0T", "phase", "in-pr", "round", 0));
    }

    @Test
    void aMalformedRequest_isRefused64_andNothingIsSent() {
        var refusals = Map.<String, List<String>>ofEntries(Map.entry("no arguments", List.of()),
                                                           Map.entry("no phase", List.of("T1-WP5")),
                                                           Map.entry("no round", List.of("T1-WP5", "verify")),
                                                           Map.entry("a bad id", List.of("T1 WP5", "verify", "--round", "1")),
                                                           Map.entry("an id with a quote", List.of("T1\"x", "verify", "--round", "1")),
                                                           Map.entry("an unknown phase", List.of("T1-WP5", "reviewing", "--round", "1")),
                                                           Map.entry("a negative round", List.of("T1-WP5", "verify", "--round", "-1")),
                                                           Map.entry("a round that is no integer", List.of("T1-WP5", "verify", "--round", "1.5")),
                                                           Map.entry("an option without a value", List.of("T1-WP5", "verify", "--round")),
                                                           Map.entry("an unknown option", List.of("T1-WP5", "verify", "--round", "1", "--host", "x")),
                                                           Map.entry("a bad coder", List.of("T1-WP5", "verify", "--round", "1", "--coder", "a\"b")),
                                                           Map.entry("a bad at", List.of("T1-WP5", "verify", "--round", "1", "--at", "now")),
                                                           Map.entry("a repeated round", List.of("T1-WP5", "verify", "--round", "1", "--round", "2")),
                                                           Map.entry("a repeated title", List.of("T1-WP5", "verify", "--round", "1", "--title", "a", "--title", "b")),
                                                           Map.entry("a newline in the note", List.of("T1-WP5", "verify", "--round", "1", "--note", "a\nb")),
                                                           Map.entry("an escape in the title", List.of("T1-WP5", "verify", "--round", "1", "--title", "\u001b[31m")));

        refusals.forEach((why, args) -> assertThat(work(args.toArray(String[]::new)).rc()).as(why).isEqualTo(64));
        assertThat(dir.resolve("ssh-stdin")).doesNotExist();
    }

    // ---- install-kiln-work.sh ------------------------------------------------------------------------------------------
    private ScanFixture.Ran install(String... args) {
        var builder = HermeticProcess.builder(Stream.concat(Stream.of("bash", INSTALL.toString()), Stream.of(args)).toList());
        builder.environment().clear();
        builder.environment().putAll(new HashMap<>(Map.of("PATH", System.getenv("PATH"), "HOME", dir.toString())));
        return ScanFixture.ran(builder, "");
    }

    @Test
    void install_bindsTheKeyToTheWorkVerb_once_andBacksUpAuthorizedKeys() throws IOException {
        var keys = dir.resolve(".ssh/authorized_keys");
        var pub = Files.writeString(dir.resolve("kiln_work.pub"), KEY + "\n");

        Files.createDirectories(keys.getParent());
        Files.writeString(keys, "restrict,command=\"exec ~/tools/kiln-ingest mac\" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOther kiln-ingest");

        var dry = install("--dry-run", pub.toString());

        assertThat(dry.rc()).as(dry.stderr()).isZero();
        assertThat(dir.resolve("tools")).doesNotExist();

        var ran = install(pub.toString());

        assertThat(ran.rc()).as(ran.stderr()).isZero();
        assertThat(Files.readAllLines(keys)).containsExactly("restrict,command=\"exec ~/tools/kiln-ingest mac\" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOther kiln-ingest",
                                                             "restrict,command=\"exec ~/tools/kiln-ingest work\" " + KEY);
        assertThat(Files.readString(dir.resolve("tools/kiln-ingest"))).isEqualTo(Files.readString(INGEST));
        assertThat(Files.isExecutable(dir.resolve("tools/kiln-ingest"))).isTrue();
        try (var files = Files.list(keys.getParent())) {
            assertThat(files.map(file -> file.getFileName().toString())).anyMatch(name -> name.startsWith("authorized_keys.bak-"));
        }

        assertThat(install(pub.toString()).rc()).as("the key is bound already").isEqualTo(3);
        assertThat(Files.readAllLines(keys)).hasSize(2);
    }

    @Test
    void install_refusesAnythingButOneEd25519Key() throws IOException {
        var rsa = Files.writeString(dir.resolve("rsa.pub"), "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ kiln-work\n");
        var two = Files.writeString(dir.resolve("two.pub"), KEY + "\n" + KEY + "\n");
        var injected = Files.writeString(dir.resolve("bad.pub"), "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5\"x,command=\"sh\n");

        assertThat(install(rsa.toString()).rc()).isEqualTo(3);
        assertThat(install(two.toString()).rc()).isEqualTo(3);
        assertThat(install(injected.toString()).rc()).isEqualTo(3);
        assertThat(install(dir.resolve("absent.pub").toString()).rc()).isEqualTo(3);
        assertThat(install().rc()).isEqualTo(2);
        assertThat(dir.resolve(".ssh/authorized_keys")).doesNotExist();
        assertThat(dir.resolve("tools")).doesNotExist();
    }
}
