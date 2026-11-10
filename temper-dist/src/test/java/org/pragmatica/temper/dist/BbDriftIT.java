package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// `ops/bb/bb-drift` run for real. `BB_DRIFT_SSH` is a shim that logs its argv and runs the remote command with `HOME` set to a
/// temp home per target, so `~/tools` of the "host" is a directory of this test; "mac" is the test's own HOME. No real host.
///
/// Probes: drop one file from the host list (or from the mac list) -> [#everyFile_ofEveryPlace_isCompared] red; report only the
/// first mismatch of a place -> [#everyMismatch_isOneLine_perFilePerPlace] red; ignore a pin -> [#aRepoFileOffItsPin_isReported]
/// red; exit 0 on drift -> [#everyFile_ofEveryPlace_isCompared] red.
class BbDriftIT {
    private static final Path OPS_BB = BbFixtures.OPS_BB;
    private static final Path OPS_KILN = OPS_BB.resolveSibling("kiln");
    private static final Path DRIFT = OPS_BB.resolve("bb-drift");

    private static final List<String> HOST_FILES = List.of("bb-build",
                                                           "bb-tickets",
                                                           "bb-queue-lib.sh",
                                                           "bb-queue",
                                                           "kiln-scan",
                                                           "kiln-ingest");

    private static final Map<String, String> VENDOR_PINS = Map.of("bb-build-direct", "50aa0b772fa7f916e2983be40e328b32",
                                                                  "bb-checkout", "7098ea8dee142600771968559e15e4db",
                                                                  "bb-hold", "0bc2401b6e94fd05b66e1df04387e2ac",
                                                                  "bb-hold-test.sh", "0f774ba7a36f0734077704319393892e",
                                                                  "bb-mutate", "f9bfbd37c5feafe67995f6716e8fecb0",
                                                                  "bb-portgroup.py", "a93ade2ff7dddcc75cee3d4169a08499",
                                                                  "bb-report", "63b01be0cf9b6fab8c4f523118025a8c",
                                                                  "bb-sockwatch", "cdcd6dc4958b5c77493d76fcb31cabfe");

    @TempDir
    Path dir;

    private Path log;
    private Path shim;
    private Path macHome;

    @BeforeEach
    void shim() throws IOException {
        assumeTrue(BbFixtures.linux(), "the remote side needs md5sum");
        log = dir.resolve("ssh.log");
        macHome = dir.resolve("mac-home");
        shim = BbFixtures.executable(dir.resolve("shim"),
                                     """
            #!/bin/bash
            # args: <target> <remote command words...>; stdin is the remote script. Logs the call, runs it in the target's home.
            target=$1; shift
            echo "$target $*" >> "%s"
            if [ "${SHIM_DOWN:-}" = "$target" ]; then exit 255; fi
            HOME="$HOME_BASE/${target#*@}" exec bash -c "$*"
            """.formatted(log));
        installMac(OPS_BB);
    }

    private void installMac(Path opsBb) throws IOException {
        Files.createDirectories(macHome.resolve("temper-wt"));
        Files.createDirectories(macHome.resolve("tools"));
        Files.copy(opsBb.resolve("bb-temper"),
                   macHome.resolve("temper-wt/bb-temper"),
                   StandardCopyOption.REPLACE_EXISTING);
        Files.copy(opsBb.resolveSibling("kiln/kiln-scan"),
                   macHome.resolve("tools/kiln-scan"),
                   StandardCopyOption.REPLACE_EXISTING);
    }

    /// a fake host: `~/tools` holding every installed file as the repo (and the pinned bb-queue) has it.
    private Path host(String name) throws Exception {
        var tools = Files.createDirectories(dir.resolve("homes").resolve(name).resolve("tools"));

        for (var f : HOST_FILES) {
            switch (f) {
                case "kiln-scan", "kiln-ingest" -> Files.copy(OPS_KILN.resolve(f), tools.resolve(f));
                default -> Files.copy(OPS_BB.resolve(f), tools.resolve(f));
            }
        }

        return tools;
    }

    private BbFixtures.Outcome drift(Path script, String... places) throws Exception {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", macHome.toString());
        env.put("BB_DRIFT_SSH", shim.toString());
        env.put("HOME_BASE",
                dir.resolve("homes").toString());
        env.put("SHIM_DOWN", "");

        return run(script, env, places);
    }

    private BbFixtures.Outcome run(Path script, Map<String, String> env, String... places) throws Exception {
        var command = new ArrayList<>(List.of("bash", script.toString()));

        command.addAll(List.of(places));

        return BbFixtures.run(command, env, null, null);
    }

    private BbFixtures.Outcome drift(String... places) throws Exception {
        return drift(DRIFT, places);
    }

    private static List<String> listing(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.sorted()
                       .filter(Files::isRegularFile)
                       .map(path -> {
                                try {
                                return root.relativize(path) + " " + Files.size(path) + " " + BbFixtures.md5(path);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                            })
                       .collect(Collectors.toList());
        }
    }

    private static String line(String place, String file, String installed, String expected) {
        return "drift " + place + " " + file + " installed=" + installed + " expected=" + expected;
    }

    private static String md5Of(String content) {
        return BbFixtures.md5(content.getBytes());
    }

    private static String expected(String file) throws IOException {
        return switch (file) {
            case "kiln-scan", "kiln-ingest" -> BbFixtures.md5(OPS_KILN.resolve(file));
            default -> BbFixtures.md5(OPS_BB.resolve(file));
        };
    }

    @Test
    void everythingInstalledAsTheRepoHasIt_isClean_andEveryPlaceIsNamed() throws Exception {
        host("h1");
        host("h2");
        var run = drift("mac", "builder@h1", "builder@h2");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout().lines().toList()).containsExactly("ok mac 2 files",
                                                                  "ok builder@h1 6 files",
                                                                  "ok builder@h2 6 files");
    }

    /// The comparison covers every file of every place: a planted mismatch in each one, alone, is reported and fails the run.
    @Test
    void everyFile_ofEveryPlace_isCompared() throws Exception {
        for (var f : HOST_FILES) {
            var tools = host("h-" + f.replace('.', '-'));

            Files.writeString(tools.resolve(f), "planted " + f + "\n");
            var place = "builder@h-" + f.replace('.', '-');
            var run = drift(place);

            assertThat(run.rc()).as(f + ": " + run.stdout() + run.stderr()).isEqualTo(1);
            assertThat(run.stdout().lines().toList()).as(f)
                      .containsExactly(line(place,
                                            f,
                                            md5Of("planted " + f + "\n"),
                                            expected(f)));
        }

        for (var f : List.of("temper-wt/bb-temper", "tools/kiln-scan")) {
            var file = macHome.resolve(f);
            var original = Files.readAllBytes(file);

            Files.writeString(file, "planted\n");
            var run = drift("mac");

            assertThat(run.rc()).as(f + ": " + run.stdout() + run.stderr()).isEqualTo(1);
            assertThat(run.stdout().lines().toList()).as(f)
                      .containsExactly(line("mac",
                                            file.getFileName().toString(),
                                            md5Of("planted\n"),
                                            expected(file.getFileName().toString())));
            Files.write(file, original);
        }
    }

    @Test
    void everyMismatch_isOneLine_perFilePerPlace() throws Exception {
        var h1 = host("h1");

        Files.writeString(h1.resolve("bb-build"), "other build\n");
        Files.writeString(h1.resolve("bb-queue"), "other queue\n");
        var h2 = host("h2");

        Files.delete(h2.resolve("kiln-scan"));
        Files.writeString(macHome.resolve("temper-wt/bb-temper"), "an old bb-temper\n");
        Files.delete(macHome.resolve("tools/kiln-scan"));
        host("h3");
        var run = drift("mac", "builder@h1", "builder@h2", "builder@h3");

        assertThat(run.rc()).isEqualTo(1);
        assertThat(run.stdout().lines().toList()).containsExactly(line("mac",
                                                                       "bb-temper",
                                                                       md5Of("an old bb-temper\n"),
                                                                       BbFixtures.md5(OPS_BB.resolve("bb-temper"))),
                                                                  line("mac",
                                                                       "kiln-scan",
                                                                       "absent",
                                                                       expected("kiln-scan")),
                                                                  line("builder@h1",
                                                                       "bb-build",
                                                                       md5Of("other build\n"),
                                                                       expected("bb-build")),
                                                                  line("builder@h1",
                                                                       "bb-queue",
                                                                       md5Of("other queue\n"),
                                                                       expected("bb-queue")),
                                                                  line("builder@h2",
                                                                       "kiln-scan",
                                                                       "absent",
                                                                       expected("kiln-scan")),
                                                                  "ok builder@h3 6 files");
    }

    @Test
    void theCheckWritesNothing_onAnyPlace() throws Exception {
        var h1 = host("h1");

        Files.writeString(h1.resolve("bb-build"), "other build\n");
        var hostsBefore = listing(dir.resolve("homes"));
        var macBefore = listing(macHome);
        var run = drift("mac", "builder@h1");

        assertThat(run.rc()).isEqualTo(1);
        assertThat(listing(dir.resolve("homes"))).isEqualTo(hostsBefore);
        assertThat(listing(macHome)).isEqualTo(macBefore);
        assertThat(Files.readAllLines(log)).allMatch(call -> call.endsWith("bash -s"));
    }

    @Test
    void anUnreachablePlace_isNamed_isNotClean_andTheOthersAreStillChecked() throws Exception {
        host("h1");
        var h2 = host("h2");

        Files.writeString(h2.resolve("bb-tickets"), "other tickets\n");
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", macHome.toString());
        env.put("BB_DRIFT_SSH", shim.toString());
        env.put("HOME_BASE",
                dir.resolve("homes").toString());
        env.put("SHIM_DOWN", "builder@h1");
        var run = run(DRIFT, env, "builder@h1", "builder@h2");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(69);
        assertThat(run.stdout().lines().toList()).containsExactly("unreachable builder@h1",
                                                                  line("builder@h2",
                                                                       "bb-tickets",
                                                                       md5Of("other tickets\n"),
                                                                       expected("bb-tickets")));
    }

    /// The reused pins: a repo file that is not at its install-bb-fields.sh pin is reported as drift of the repo itself, even when
    /// every place holds that same file.
    @Test
    void aRepoFileOffItsPin_isReported() throws Exception {
        var ops = repoCopy();

        Files.writeString(ops.resolve("bb-build"),
                          Files.readString(ops.resolve("bb-build")) + "# edited without a new pin\n");
        var tools = host("h1");

        Files.copy(ops.resolve("bb-build"), tools.resolve("bb-build"), StandardCopyOption.REPLACE_EXISTING);
        var run = drift(ops.resolve("bb-drift"), "builder@h1");

        assertThat(run.rc()).isEqualTo(1);
        assertThat(run.stdout().lines().toList()).containsExactly(line("repo",
                                                                       "bb-build",
                                                                       BbFixtures.md5(ops.resolve("bb-build")),
                                                                       pin("BB_BUILD_NEW")),
                                                                       "ok builder@h1 6 files");
    }

    @Test
    void aBadPlace_isRefusedWithoutAnySshCall() throws Exception {
        var run = drift("mac", "not a place");

        assertThat(run.rc()).isEqualTo(2);
        assertThat(log).doesNotExist();
    }

    /// With no places the defaults are mac and the host config's auto hosts, each reached at its configured address; the
    /// explicit-only host (cloudbb-d1, a timing host) is checked only when named.
    @Test
    void defaultPlaces_areMac_andTheConfigsAutoHosts_explicitOnlyWhenNamed() throws Exception {
        TestHosts.write(macHome);
        for (var address : List.of(TestHosts.BIGBOY, TestHosts.C3, TestHosts.C4, TestHosts.D1)) {
            host(address.substring(address.indexOf('@') + 1));
        }

        var run = drift();

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout().lines().toList()).containsExactly("ok mac 2 files", "ok bigboy 6 files", "ok cloudbb-3 6 files",
                                                                  "ok cloudbb-4 6 files");
        assertThat(Files.readAllLines(log)).extracting(l -> l.split(" ")[0]).containsExactly(TestHosts.BIGBOY, TestHosts.C3, TestHosts.C4);

        var named = drift("cloudbb-d1");

        assertThat(named.stdout().lines().toList()).containsExactly("ok cloudbb-d1 6 files");
        assertThat(Files.readAllLines(log)).last().asString().startsWith(TestHosts.D1 + " ");
    }

    @Test
    void noHostConfig_withDefaultPlaces_isExit78_andAnUnconfiguredName_isExit2_beforeAnySsh() throws Exception {
        var none = drift();

        assertThat(none.rc()).isEqualTo(78);
        assertThat(none.stderr()).contains("no host config");

        TestHosts.write(macHome);
        var unknown = drift("nowhere");

        assertThat(unknown.rc()).isEqualTo(2);
        assertThat(log).doesNotExist();
    }

    private void writeHosts(String text) throws IOException {
        Files.writeString(Files.createDirectories(macHome.resolve(".config/bb-temper")).resolve("hosts"), text);
    }

    /// A hosts file saved with CRLF line ends parses exactly like the LF one.
    @Test
    void aCrlfHostConfig_parsesLikeTheLfOne() throws Exception {
        writeHosts(TestHosts.TEXT.replace("\n", "\r\n"));
        for (var address : List.of(TestHosts.BIGBOY, TestHosts.C3, TestHosts.C4)) {
            host(address.substring(address.indexOf('@') + 1));
        }

        var run = drift();

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout().lines().toList()).containsExactly("ok mac 2 files", "ok bigboy 6 files", "ok cloudbb-3 6 files",
                                                                  "ok cloudbb-4 6 files");
    }

    /// Two `key` lines are malformed even when they name the same path: exit 78 naming the second line, before any ssh.
    @Test
    void aRepeatedKeyLine_isExit78_naming_theLine() throws Exception {
        writeHosts("key ~/" + TestHosts.KEY + "\nkey ~/" + TestHosts.KEY + "\nhost bigboy " + TestHosts.BIGBOY + " - - auto\n");

        var run = drift();

        assertThat(run.rc()).isEqualTo(78);
        assertThat(run.stderr()).contains("line 2: key is listed twice");
        assertThat(log).doesNotExist();
    }

    /// One host table, one parser: bb-temper and bb-drift carry the same host-config block (the drift this tool exists to catch
    /// cannot happen between two copies of a table), and no bb script carries a host address of its own.
    @Test
    void theHostConfigBlock_isIdenticalInBbTemperAndBbDrift_andNoBbScriptCarriesAnAddress() throws Exception {
        var inDrift = hostBlock(Files.readString(DRIFT));
        var inTemper = hostBlock(Files.readString(OPS_BB.resolve("bb-temper")));

        assertThat(inDrift).contains("load_hosts()").isEqualTo(inTemper);
        var ipv4 = Pattern.compile("(?<![\\w.])\\d{1,3}(\\.\\d{1,3}){3}(?![\\w.])");
        for (var script : List.of(DRIFT, OPS_BB.resolve("bb-temper"), OPS_BB.resolve("install-bb-fields.sh"))) {
            assertThat(ipv4.matcher(Files.readString(script)).results().map(m -> m.group()).toList()).as(script.toString()).isEmpty();
        }
    }

    private static String hostBlock(String script) {
        var from = script.indexOf("# ---- host config:");
        var to = script.indexOf("# ---- end of the shared host-config block");

        assertThat(from).as("block start").isNotNegative();
        assertThat(to).as("block end").isGreaterThan(from);
        return script.substring(from, to);
    }

    /// bb-queue is compared to the repo file (added by the orphan-ticket fix), not to a hard-coded md5, and the repo file is held
    /// to its install-bb-fields.sh pin like the other ops/bb files.
    @Test
    void bbQueue_isComparedToTheRepoFile_atItsPin() throws Exception {
        assertThat(Files.readString(DRIFT)).doesNotContain("readonly BB_QUEUE=");
        assertThat(BbFixtures.md5(OPS_BB.resolve("bb-queue"))).isEqualTo(pin("BB_QUEUE_NEW"));
        var ops = repoCopy();

        Files.writeString(ops.resolve("bb-queue"), Files.readString(ops.resolve("bb-queue")) + "# edited without a new pin\n");
        var tools = host("h1");

        Files.copy(ops.resolve("bb-queue"), tools.resolve("bb-queue"), StandardCopyOption.REPLACE_EXISTING);
        var run = drift(ops.resolve("bb-drift"), "builder@h1");

        assertThat(run.stdout().lines().toList()).containsExactly(line("repo",
                                                                       "bb-queue",
                                                                       BbFixtures.md5(ops.resolve("bb-queue")),
                                                                       pin("BB_QUEUE_NEW")),
                                                                  "ok builder@h1 6 files");
    }

    /// The host-only tools: the pin table is exactly the 8 names of the 2026-11-09 snapshot at its md5s (maintainer ruling: vendored in
    /// a later PR; this class then goes away).
    @Test
    void theNoRepoSourcePins_areTheSnapshotOfTheEightHostOnlyTools() throws Exception {
        var script = Files.readString(DRIFT);
        var found = new HashMap<String, String>();
        var matcher = Pattern.compile("^\\s*([a-z.-]+)\\) echo ([0-9a-f]{32}) ;;", Pattern.MULTILINE).matcher(script);

        while (matcher.find()) {
            found.put(matcher.group(1), matcher.group(2));
        }

        assertThat(found).isEqualTo(VENDOR_PINS);
    }

    /// At its pin a host-only tool is reported as to be vendored and is not drift; the pin is swapped in a copy of the script
    /// because the real bytes are not in this repo until the vendoring PR.
    @Test
    void aNoRepoSourceTool_atItsPin_isMarkedToBeVendored_andIsNotDrift() throws Exception {
        var ops = repoCopy();
        var script = ops.resolve("bb-drift");

        BbFixtures.executable(script,
                              Files.readString(script).replace(VENDOR_PINS.get("bb-report"), md5Of("a bb-report\n")));
        var tools = host("h1");

        Files.writeString(tools.resolve("bb-report"), "a bb-report\n");
        var run = drift(script, "builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout().lines().toList()).containsExactly("vendor builder@h1 bb-report md5=" + md5Of("a bb-report\n")
                                                                  + " to be vendored (PR next)",
                                                                  "ok builder@h1 6 files");
    }

    @Test
    void aNoRepoSourceTool_offItsPin_isDrift_andAnAbsentOneIsNot() throws Exception {
        var tools = host("h1");

        Files.writeString(tools.resolve("bb-report"), "an edited bb-report\n");
        var run = drift("builder@h1");

        assertThat(run.rc()).isEqualTo(1);
        assertThat(run.stdout().lines().toList()).containsExactly(line("builder@h1",
                                                                       "bb-report",
                                                                       md5Of("an edited bb-report\n"),
                                                                       VENDOR_PINS.get("bb-report")));
    }

    @Test
    void anUnlistedFile_isReported_andFailsTheRun() throws Exception {
        var tools = host("h1");

        Files.writeString(tools.resolve("mystery-tool"), "who put this here\n");
        var run = drift("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(1);
        assertThat(run.stdout().lines().toList()).containsExactly("unlisted builder@h1 mystery-tool md5=" + md5Of("who put this here\n"));
    }

    /// Backups, dotfiles and the `maven` symlink are not classified; a regular file named maven and a directory of another name are.
    @Test
    void backupsDotfilesAndTheMavenDirectory_areIgnored_butLookAlikesAreNot() throws Exception {
        var tools = host("h1");
        var mavenHome = Files.createDirectories(dir.resolve("apache-maven"));

        Files.createSymbolicLink(tools.resolve("maven"), mavenHome);
        Files.writeString(tools.resolve("bb-build.bak-999aa32577930aaf715ed4ce7c31fe02"), "old\n");
        Files.writeString(tools.resolve("bb-mutate.pre-t17-20261104"), "old\n");
        Files.writeString(tools.resolve("kiln-scan.bak-78b72195"), "old\n");
        Files.writeString(tools.resolve(".bb-guard.py"), "dot\n");
        Files.writeString(tools.resolve(".bb-build.bak-20260927"), "dot\n");
        var clean = drift("builder@h1");

        assertThat(clean.rc()).as(clean.stdout() + clean.stderr()).isZero();
        assertThat(clean.stdout().lines().toList()).containsExactly("ok builder@h1 6 files");

        Files.delete(tools.resolve("maven"));
        Files.writeString(tools.resolve("maven"), "not a directory\n");
        Files.createDirectories(tools.resolve("scratch"));
        var run = drift("builder@h1");

        assertThat(run.rc()).isEqualTo(1);
        assertThat(run.stdout().lines().toList()).containsExactlyInAnyOrder("unlisted builder@h1 maven md5=" + md5Of("not a directory\n"),
                                                                             "unlisted builder@h1 scratch md5=dir");
    }

    /// Ignored backups are anchored: only `<known tool>.bak*` / `<known tool>.pre-*` pass; any other name carrying the marker is unlisted.
    @Test
    void aBackupNameNotAnchoredOnAKnownTool_isUnlisted() throws Exception {
        for (var name : List.of("evil.bak", "x.pre-foo")) {
            var tools = host("h-" + name.replace('.', '-'));

            Files.writeString(tools.resolve(name), "x\n");
            var place = "builder@h-" + name.replace('.', '-');
            var run = drift(place);

            assertThat(run.rc()).as(name + ": " + run.stdout() + run.stderr()).isEqualTo(1);
            assertThat(run.stdout().lines().toList()).as(name).containsExactly("unlisted " + place + " " + name + " md5=" + md5Of("x\n"));
        }
    }

    @Test
    void aBackupOfAKnownTool_isStillIgnored() throws Exception {
        var tools = host("h1");

        Files.writeString(tools.resolve("bb-build.bak-20261109"), "old\n");
        Files.writeString(tools.resolve("bb-queue.pre-t17-20261104"), "old\n");
        var run = drift("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout().lines().toList()).containsExactly("ok builder@h1 6 files");
    }

    /// `maven` is accepted only when it resolves to an existing directory.
    @Test
    void maven_isAcceptedOnlyAsADirectory_realOrBySymlink() throws Exception {
        var real = host("h-real");

        Files.createDirectories(real.resolve("maven/bin"));
        var viaLink = host("h-link");

        Files.createSymbolicLink(viaLink.resolve("maven"), Files.createDirectories(dir.resolve("apache-maven-x")));

        for (var place : List.of("builder@h-real", "builder@h-link")) {
            var run = drift(place);

            assertThat(run.rc()).as(place + ": " + run.stdout() + run.stderr()).isZero();
            assertThat(run.stdout().lines().toList()).containsExactly("ok " + place + " 6 files");
        }
    }

    @Test
    void maven_asADanglingSymlink_aSymlinkToDevNull_orARegularFile_isUnlisted() throws Exception {
        var dangling = host("h-dangling");

        Files.createSymbolicLink(dangling.resolve("maven"), dir.resolve("no-such-dir"));
        var devNull = host("h-devnull");

        Files.createSymbolicLink(devNull.resolve("maven"), Path.of("/dev/null"));
        var regular = host("h-regular");

        Files.writeString(regular.resolve("maven"), "file\n");
        var expected = Map.of("builder@h-dangling", "other", "builder@h-devnull", "other", "builder@h-regular", md5Of("file\n"));

        for (var e : expected.entrySet()) {
            var run = drift(e.getKey());

            assertThat(run.rc()).as(e.getKey() + ": " + run.stdout() + run.stderr()).isEqualTo(1);
            assertThat(run.stdout().lines().toList()).as(e.getKey()).containsExactly("unlisted " + e.getKey() + " maven md5=" + e.getValue());
        }
    }

    private Path repoCopy() throws IOException {
        var ops = Files.createDirectories(dir.resolve("copy/bb"));

        Files.createDirectories(dir.resolve("copy/kiln"));
        try (var files = Files.list(OPS_BB)) {
            for (var f : files.toList()) {
                Files.copy(f, ops.resolve(f.getFileName()));
            }
        }

        try (var files = Files.list(OPS_KILN)) {
            for (var f : files.toList()) {
                Files.copy(f, dir.resolve("copy/kiln").resolve(f.getFileName()));
            }
        }

        BbFixtures.executable(ops.resolve("bb-drift"), Files.readString(ops.resolve("bb-drift")));

        return ops;
    }

    private static String pin(String name) throws IOException {
        var matcher = Pattern.compile("readonly " + name + "=([0-9a-f]{32})").matcher(Files.readString(OPS_BB.resolve("install-bb-fields.sh")));

        assertThat(matcher.find()).isTrue();

        return matcher.group(1);
    }
}
