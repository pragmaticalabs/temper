package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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


/// `ops/bb/install-bb-fields.sh` run for real. `BB_INSTALL_SSH` is a shim that logs its argv and runs the
/// remote command with `HOME` set to a temp home per target, so `~/tools` of the "host" is a directory of this test. Nothing
/// here touches a real host.
///
/// Probes (C10): check after writing / per target only -> [#oneMismatchedTarget_refusesAll_andWritesNothingAnywhere] red; drop
/// the phase-2 re-check -> [#aFileChangedBetweenPhases_isNotReplaced_andTheNewFileIsRemoved] red; compute the new md5 at run
/// time -> [#aLocalFileNotAtThePin_isRefused_withoutAnySshCall] red; drop the payload md5 check ->
/// [#aTruncatedPayload_isRefusedAsAPayloadMismatch_andNothingIsReplaced] red.
class BbInstallIT {
    private static final Path OPS_BB = BbFixtures.OPS_BB;
    private static final Path INSTALL = OPS_BB.resolve("install-bb-fields.sh");

    @TempDir
    Path dir;

    private Path log;
    private Path shim;
    private String oldBuildMd5;

    @BeforeEach
    void shim() throws IOException {
        assumeTrue(BbFixtures.linux(), "the remote side needs md5sum and base64 -d");
        log = dir.resolve("ssh.log");
        shim = BbFixtures.executable(dir.resolve("shim"),
                                     """
            #!/bin/bash
            # args: <target> <remote command words...>; stdin is the remote script. Logs the call, runs it in the target's home.
            target=$1; shift
            echo "$target $*" >> "%s"
            if [ -n "${SHIM_BEFORE_BUILD:-}" ] && [[ "$*" == *"-- bb-build "* ]] && [ ! -e "$SHIM_BEFORE_BUILD.done" ]; then
              touch "$SHIM_BEFORE_BUILD.done"; echo "# changed behind the install's back" >> "$HOME_BASE/${target#*@}/tools/bb-build"
            fi
            if [ "${SHIM_DOWN:-}" = "$target" ]; then exit 255; fi
            if [ -n "${SHIM_TRUNCATE:-}" ] && [[ "$*" == *"-- bb-build "* ]]; then
              # drop the first base64 line of the payload: a truncated transfer that still decodes
              HOME="$HOME_BASE/${target#*@}" bash -c "$*" < <(awk '!d && length($0)==76 && $0 !~ " " {d=1; next} {print}'); exit $?
            fi
            HOME="$HOME_BASE/${target#*@}" exec bash -c "$*"
            """.formatted(log));
    }

    /// a fake host `h`: `~/tools` holding the verbatim bigboy bb-build, the lib, and optional extras.
    private Path host(String name) throws Exception {
        var tools = Files.createDirectories(dir.resolve("homes").resolve(name).resolve("tools"));
        var old = BbFixtures.originalBbBuild();

        oldBuildMd5 = BbFixtures.md5(old);
        Files.write(tools.resolve("bb-build"), old);
        Files.write(tools.resolve("bb-queue-lib.sh"), libOld());
        Files.writeString(tools.resolve("bb-build.pre-cap5-20261004"), "an older backup\n");
        Files.write(tools.resolve("bb-queue"), BbFixtures.originalBbQueue());

        return tools;
    }

    /// The bigboy bb-queue-lib.sh (BB_LIB_OLD), vendored verbatim as the resource `bb/bb-queue-lib.sh.original` (md5 checked).
    private static byte[] libOld() throws IOException {
        try (var in = BbInstallIT.class.getResourceAsStream("/bb/bb-queue-lib.sh.original")) {
            assertThat(in).as("test resource bb/bb-queue-lib.sh.original").isNotNull();
            var bytes = in.readAllBytes();

            assertThat(BbFixtures.md5(bytes)).isEqualTo(BbFixtures.BB_LIB_OLD);

            return bytes;
        }
    }

    private BbFixtures.Outcome install(Path script, Map<String, String> extra, String... targets) throws Exception {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME",
                dir.resolve("mac-home").toString());
        env.put("BB_INSTALL_SSH", shim.toString());
        env.put("HOME_BASE",
                dir.resolve("homes").toString());
        env.putAll(extra);
        var command = new ArrayList<>(List.of("bash", script.toString()));

        command.addAll(List.of(targets));

        return BbFixtures.run(command, env, null, null);
    }

    private BbFixtures.Outcome install(String... targets) throws Exception {
        return install(INSTALL, Map.of(), targets);
    }

    private List<String> sshCalls() throws IOException {
        return Files.exists(log)
               ? Files.readAllLines(log)
               : List.of();
    }

    /// names, sizes, md5s and modes of everything under a tools directory
    private static List<String> listing(Path tools) throws IOException {
        try (Stream<Path> walk = Files.list(tools)) {
            return walk.sorted()
                       .map(path -> {
                                try {
                                return path.getFileName()
                                     + " " + Files.size(path)
                                     + " " + BbFixtures.md5(path)
                                     + " " + PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                            })
                       .collect(Collectors.toList());
        }
    }

    private static String pin(String name) throws IOException {
        var matcher = Pattern.compile("readonly " + name + "=([0-9a-f]{32})").matcher(Files.readString(INSTALL));

        assertThat(matcher.find()).as(name + " is pinned to an md5 in install-bb-fields.sh").isTrue();

        return matcher.group(1);
    }

    /// The bb-tickets before `progressAt` (BB_TICKETS_OLD), vendored verbatim as the resource `bb/bb-tickets.previous`.
    private static byte[] ticketsPrevious() throws IOException {
        try (var in = BbInstallIT.class.getResourceAsStream("/bb/bb-tickets.previous")) {
            assertThat(in).as("test resource bb/bb-tickets.previous").isNotNull();

            return in.readAllBytes();
        }
    }

    /// A host that already runs the previous bb-tickets is updated to the new one (not refused as a stranger's file).
    /// Mutation: accept only absent|NEW in phase 1 -> refused, exit 65, red.
    @Test
    void aPreviousBbTickets_isReplacedByTheNewOne() throws Exception {
        var tools = host("h1");

        Files.write(tools.resolve("bb-tickets"), ticketsPrevious());
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout().lines().toList()).contains("installed builder@h1 bb-tickets before=" + pin("BB_TICKETS_OLD")
                                                          + " after=" + pin("BB_TICKETS_NEW"));
        assertThat(tools.resolve("bb-tickets")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-tickets")));
    }

    @Test
    void pins_areTheReviewedFiles_andTheOldOnesAreTheBigboyOnes() throws Exception {
        assertThat(pin("BB_BUILD_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-build")));
        assertThat(pin("BB_TICKETS_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-tickets")));
        assertThat(pin("BB_LIB_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-queue-lib.sh")));
        assertThat(pin("BB_QUEUE_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-queue")));
        assertThat(pin("BB_TICKETS_OLD")).isEqualTo(BbFixtures.md5(ticketsPrevious()));
        assertThat(pin("BB_LIB_OLD")).isEqualTo(BbFixtures.BB_LIB_OLD).isEqualTo(BbFixtures.md5(libOld()));
        assertThat(pin("BB_QUEUE_OLD")).isEqualTo(BbFixtures.BB_QUEUE_OLD).isEqualTo(BbFixtures.md5(BbFixtures.originalBbQueue()));
        assertThat(pin("BB_BUILD_OLD")).isEqualTo(BbFixtures.BB_BUILD_OLD).isEqualTo(BbFixtures.md5(BbFixtures.originalBbBuild()));
        assertThat(pin("BB_BUILD_PREV")).isEqualTo(BbFixtures.md5(prev("bb-build")));
        assertThat(pin("BB_LIB_PREV")).isEqualTo(BbFixtures.md5(prev("bb-queue-lib.sh")));
        assertThat(pin("BB_QUEUE_PREV")).isEqualTo(BbFixtures.md5(prev("bb-queue")));
        assertThat(pin("BB_QUEUE_LIVE")).isEqualTo(BbFixtures.md5(prev("bb-queue.live")));
        assertThat(pin("BB_PORTGROUP_PREV")).isEqualTo(BbFixtures.md5(prev("bb-portgroup.py")));
        assertThat(pin("BB_BUILD_LIVE")).isEqualTo(BbFixtures.md5(prev("bb-build.live")));
        assertThat(pin("BB_NETNS_PREV")).isEqualTo(BbFixtures.md5(prev("bb-netns-run")));
        assertThat(pin("BB_HOLD_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-hold")));
        assertThat(pin("BB_WAIT_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-wait")));
        assertThat(pin("BB_NETNS_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-netns-run")));
        assertThat(pin("BB_PORTGROUP_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-portgroup.py")));
    }

    /// The 2026-11-09 repo version of bb-build, bb-queue-lib.sh or bb-queue (what cloudbb-3 holds), vendored verbatim as `bb/<name>.prev`.
    private static byte[] prev(String name) throws IOException {
        try (var in = BbInstallIT.class.getResourceAsStream("/bb/" + name + ".prev")) {
            assertThat(in).as("test resource bb/" + name + ".prev").isNotNull();
            return in.readAllBytes();
        }
    }

    private static final List<String> NEW_FILES = List.of("bb-hold", "bb-wait", "bb-netns-run", "bb-portgroup.py");

    @Test
    void success_installsAllFourFiles_keepsBackups_andASecondRunChangesNothing() throws Exception {
        var tools = host("h1");
        var oldBuild = Files.readAllBytes(tools.resolve("bb-build"));
        var oldInode = Files.readAttributes(tools.resolve("bb-build"), "unix:ino").get("ino");
        var otherBackup = tools.resolve("bb-build.pre-cap5-20261004");
        var first = install("builder@h1");

        assertThat(first.rc()).as(first.stdout() + first.stderr()).isZero();
        assertThat(first.stdout().lines().toList()).containsExactly(
            "installed builder@h1 bb-tickets before=absent after=" + pin("BB_TICKETS_NEW"),
            "installed builder@h1 bb-hold before=absent after=" + pin("BB_HOLD_NEW"),
            "installed builder@h1 bb-wait before=absent after=" + pin("BB_WAIT_NEW"),
            "installed builder@h1 bb-netns-run before=absent after=" + pin("BB_NETNS_NEW"),
            "installed builder@h1 bb-portgroup.py before=absent after=" + pin("BB_PORTGROUP_NEW"),
            "installed builder@h1 bb-queue-lib.sh before=" + BbFixtures.BB_LIB_OLD + " after=" + pin("BB_LIB_NEW"),
            "installed builder@h1 bb-queue before=" + BbFixtures.BB_QUEUE_OLD + " after=" + pin("BB_QUEUE_NEW"),
            "installed builder@h1 bb-build before=" + BbFixtures.BB_BUILD_OLD + " after=" + pin("BB_BUILD_NEW"));
        assertThat(tools.resolve("bb-build")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-build")));
        assertThat(tools.resolve("bb-tickets")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-tickets")));
        for (var f : NEW_FILES) {
            assertThat(tools.resolve(f)).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(f)));
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tools.resolve(f)))).as(f).isEqualTo("rwxr-xr-x");
        }
        assertThat(tools.resolve("bb-build.bak-" + BbFixtures.BB_BUILD_OLD)).hasBinaryContent(oldBuild);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tools.resolve("bb-build")))).isEqualTo("rwxr-xr-x");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tools.resolve("bb-tickets")))).isEqualTo("rwxr-xr-x");
        assertThat(Files.readAttributes(tools.resolve("bb-build"),
                                        "unix:ino").get("ino")).as("replaced by rename, never overwritten in place")
                  .isNotEqualTo(oldInode);
        assertThat(otherBackup).hasContent("an older backup\n");
        assertThat(tools.resolve("bb-queue-lib.sh")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-queue-lib.sh")));
        assertThat(tools.resolve("bb-queue")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-queue")));
        assertThat(tools.resolve("bb-queue-lib.sh.bak-" + BbFixtures.BB_LIB_OLD)).hasBinaryContent(libOld());
        assertThat(tools.resolve("bb-queue.bak-" + BbFixtures.BB_QUEUE_OLD)).hasBinaryContent(BbFixtures.originalBbQueue());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tools.resolve("bb-queue")))).isEqualTo("rwxr-xr-x");
        try (var names = Files.list(tools)) {
            assertThat(names.map(path -> path.getFileName()
                                             .toString()).filter(name -> name.endsWith(".new"))).isEmpty();
        }

        var after = listing(tools);
        var calls = sshCalls().size();
        var second = install("builder@h1");

        assertThat(second.rc()).as(second.stdout() + second.stderr()).isZero();
        assertThat(second.stdout().lines().toList()).containsExactly(
            "unchanged builder@h1 bb-tickets " + pin("BB_TICKETS_NEW"),
            "unchanged builder@h1 bb-hold " + pin("BB_HOLD_NEW"),
            "unchanged builder@h1 bb-wait " + pin("BB_WAIT_NEW"),
            "unchanged builder@h1 bb-netns-run " + pin("BB_NETNS_NEW"),
            "unchanged builder@h1 bb-portgroup.py " + pin("BB_PORTGROUP_NEW"),
            "unchanged builder@h1 bb-queue-lib.sh " + pin("BB_LIB_NEW"),
            "unchanged builder@h1 bb-queue " + pin("BB_QUEUE_NEW"),
            "unchanged builder@h1 bb-build " + pin("BB_BUILD_NEW"));
        assertThat(listing(tools)).isEqualTo(after);
        assertThat(sshCalls()).as("the second run only probes").hasSize(calls + 1);
    }

    /// cloudbb-3 shape: the 2026-11-09 repo set (bb-build/lib/queue at PREV, bb-hold at the host snapshot, no bb-wait/bb-netns-run) is an
    /// accepted upgrade source; every file ends at the live pin and the replaced ones are backed up under their old md5.
    @Test
    void aHostAtThePreviousRepoSet_isUpgraded() throws Exception {
        var tools = host("h1");

        Files.write(tools.resolve("bb-build"), prev("bb-build"));
        Files.write(tools.resolve("bb-queue-lib.sh"), prev("bb-queue-lib.sh"));
        Files.write(tools.resolve("bb-queue"), prev("bb-queue"));
        Files.writeString(tools.resolve("bb-hold"), "the snapshot hold\n");
        Files.write(tools.resolve("bb-portgroup.py"), prev("bb-portgroup.py"));
        var run = install(INSTALL, Map.of(), "builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(65);
        assertThat(run.stdout()).as("an off-pin bb-hold is still refused").contains("refused builder@h1 bb-hold installed=");
        Files.delete(tools.resolve("bb-hold"));

        var ok = install("builder@h1");

        assertThat(ok.rc()).as(ok.stdout() + ok.stderr()).isZero();
        assertThat(ok.stdout()).contains("installed builder@h1 bb-build before=" + pin("BB_BUILD_PREV") + " after=" + pin("BB_BUILD_NEW"),
                                         "installed builder@h1 bb-queue-lib.sh before=" + pin("BB_LIB_PREV") + " after=" + pin("BB_LIB_NEW"),
                                         "installed builder@h1 bb-queue before=" + pin("BB_QUEUE_PREV") + " after=" + pin("BB_QUEUE_NEW"));
        assertThat(ok.stdout()).contains("installed builder@h1 bb-portgroup.py before=" + pin("BB_PORTGROUP_PREV") + " after=" + pin("BB_PORTGROUP_NEW"));
        assertThat(tools.resolve("bb-portgroup.py.bak-" + pin("BB_PORTGROUP_PREV"))).hasBinaryContent(prev("bb-portgroup.py"));
        assertThat(tools.resolve("bb-build.bak-" + pin("BB_BUILD_PREV"))).hasBinaryContent(prev("bb-build"));
    }

    /// The bb-build the 2026-11-11 hosts run (BB_BUILD_LIVE, no requireDocker flag) is an accepted source and is replaced, backed up under its md5.
    @Test
    void aHostRunningTheLiveBbBuild_isUpdatedToTheOneThatRequiresDocker() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        Files.write(tools.resolve("bb-build"), prev("bb-build.live"));
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("installed builder@h1 bb-build before=" + pin("BB_BUILD_LIVE") + " after=" + pin("BB_BUILD_NEW"));
        assertThat(tools.resolve("bb-build.bak-" + pin("BB_BUILD_LIVE"))).hasBinaryContent(prev("bb-build.live"));
    }

    /// The bb-netns-run of 2026-11-11 (BB_NETNS_PREV, no docker socket in the container; live on c3/c4/d1) is an accepted source and is replaced, backed up under its md5.
    @Test
    void aHostRunningThePreviousNetnsRun_isUpdatedToTheOneThatReachesDocker() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        Files.write(tools.resolve("bb-netns-run"), prev("bb-netns-run"));
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("installed builder@h1 bb-netns-run before=" + pin("BB_NETNS_PREV") + " after=" + pin("BB_NETNS_NEW"));
        assertThat(tools.resolve("bb-netns-run")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-netns-run")));
        assertThat(tools.resolve("bb-netns-run.bak-" + pin("BB_NETNS_PREV"))).hasBinaryContent(prev("bb-netns-run"));
    }

    /// The bb-queue the 2026-11-11 hosts run (BB_QUEUE_LIVE) printed a header the worker could not parse; it is an accepted source and is replaced.
    @Test
    void aHostRunningTheLiveBbQueue_isUpdatedToTheCompatibleOne() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        Files.write(tools.resolve("bb-queue"), prev("bb-queue.live"));
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("installed builder@h1 bb-queue before=" + pin("BB_QUEUE_LIVE") + " after=" + pin("BB_QUEUE_NEW"));
        assertThat(tools.resolve("bb-queue")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-queue")));
    }

    /// c4/d1 shape: a host already holding the live set is accepted and nothing is written.
    @Test
    void aHostAtTheLiveSet_isAccepted_andNothingIsWritten() throws Exception {
        var tools = host("h1");

        for (var f : List.of("bb-build", "bb-queue-lib.sh", "bb-queue", "bb-tickets", "bb-hold", "bb-wait", "bb-netns-run", "bb-portgroup.py")) {
            Files.copy(OPS_BB.resolve(f), tools.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        var before = listing(tools);
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout().lines()).allMatch(l -> l.startsWith("unchanged "));
        assertThat(listing(tools)).isEqualTo(before);
    }

    @Test
    void anExistingBakOfThatName_isKept() throws Exception {
        var tools = host("h1");

        Files.writeString(tools.resolve("bb-build.bak-" + BbFixtures.BB_BUILD_OLD), "owner's own backup\n");
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(tools.resolve("bb-build.bak-" + BbFixtures.BB_BUILD_OLD)).hasContent("owner's own backup\n");
        assertThat(tools.resolve("bb-build")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-build")));
    }

    @Test
    void oneMismatchedTarget_refusesAll_andWritesNothingAnywhere() throws Exception {
        var good = host("h1");
        var diverged = host("h2");

        Files.writeString(diverged.resolve("bb-build"), "a diverged copy\n");
        var goodBefore = listing(good);
        var divergedBefore = listing(diverged);
        var run = install("builder@h1", "builder@h2");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(65);
        assertThat(run.stdout().lines().toList()).containsExactly(
            "refused builder@h2 bb-build installed=" + BbFixtures.md5("a diverged copy\n".getBytes()) + " expected=" + BbFixtures.BB_BUILD_OLD + "|a1100c916b866ad3716bc4095ddb1991|cae49573d06f632d3081f7d5be1e9b66|" + pin("BB_BUILD_LIVE") + "|" + pin("BB_BUILD_NEW"));
        assertThat(listing(good)).isEqualTo(goodBefore);
        assertThat(listing(diverged)).isEqualTo(divergedBefore);
        assertThat(sshCalls()).as("phase 1 only: one probe per target").hasSize(2);
        assertThat(sshCalls()).allMatch(call -> call.endsWith("bash -s"));
    }

    @Test
    void everyMismatch_ofEveryFile_isNamed() throws Exception {
        var h1 = host("h1");

        Files.writeString(h1.resolve("bb-queue-lib.sh"), "other lib\n");
        Files.writeString(h1.resolve("bb-tickets"), "someone else's tickets\n");
        Files.writeString(h1.resolve("bb-queue"), "unrelated tool\n");
        Files.writeString(h1.resolve("bb-hold"), "a hold of its own\n");
        var h2 = host("h2");

        Files.delete(h2.resolve("bb-build"));
        var run = install("builder@h1", "builder@h2");

        assertThat(run.rc()).isEqualTo(65);
        assertThat(run.stdout().lines().map(line -> line.replaceAll("installed=[0-9a-z]+", "installed=X").replaceAll("expected=[0-9a-f|]+", "expected=Y")).toList())
            .containsExactly("refused builder@h1 bb-queue-lib.sh installed=X expected=Y", "refused builder@h1 bb-queue installed=X expected=Y",
                             "refused builder@h1 bb-tickets installed=X expected=Y",
                             "refused builder@h1 bb-hold installed=X expected=Y",
                             "refused builder@h2 bb-build installed=X expected=Y");
        assertThat(run.stdout()).contains("bb-build installed=absent");
        assertThat(run.stdout()).as("every accepted pin is named")
                  .contains("bb-queue-lib.sh installed=" + BbFixtures.md5("other lib\n".getBytes()) + " expected=" + BbFixtures.BB_LIB_OLD
                            + "|621b2252c3da05e7dd1161d076908864|" + pin("BB_LIB_NEW"))
                  .contains("bb-queue installed=" + BbFixtures.md5("unrelated tool\n".getBytes()) + " expected=" + BbFixtures.BB_QUEUE_OLD
                            + "|a04b7be02f840d4f1e2bd19c54c52b3f|" + pin("BB_QUEUE_LIVE") + "|" + pin("BB_QUEUE_NEW"));
    }

    @Test
    void aFileChangedBetweenPhases_isNotReplaced_andTheNewFileIsRemoved() throws Exception {
        var tools = host("h1");
        var run = install(INSTALL,
                          Map.of("SHIM_BEFORE_BUILD",
                                 dir.resolve("raced").toString()),
                          "builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(65);
        assertThat(Files.readString(tools.resolve("bb-build"))).endsWith("# changed behind the install's back\n");
        assertThat(BbFixtures.md5(tools.resolve("bb-build"))).isNotEqualTo(pin("BB_BUILD_NEW"));
        assertThat(tools.resolve("bb-build.new")).doesNotExist();
        assertThat(tools.resolve("bb-build.bak-" + BbFixtures.BB_BUILD_OLD)).doesNotExist();
        assertThat(tools.resolve("bb-tickets")).as("the file moved before the race stays, whole")
                  .hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-tickets")));
    }

    @Test
    void aTruncatedPayload_isRefusedAsAPayloadMismatch_andNothingIsReplaced() throws Exception {
        var tools = host("h1");
        var before = listing(tools);
        var run = install(INSTALL, Map.of("SHIM_TRUNCATE", "1"), "builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(65);
        assertThat(run.stderr()).contains("refused: payload md5 mismatch after transfer (bb-build on builder@h1)")
                  .doesNotContain("re-check");
        assertThat(tools.resolve("bb-build.new")).doesNotExist();
        assertThat(BbFixtures.md5(tools.resolve("bb-build"))).isEqualTo(oldBuildMd5);
        assertThat(listing(tools).stream().filter(l -> l.startsWith("bb-build ")).toList()).isEqualTo(before.stream()
                                                                                                            .filter(l -> l.startsWith("bb-build "))
                                                                                                            .toList());
    }

    @Test
    void aLocalFileNotAtThePin_isRefused_withoutAnySshCall() throws Exception {
        host("h1");
        var copy = Files.createDirectories(dir.resolve("checkout/ops/bb"));
        for (var name : List.of("bb-build", "bb-tickets", "bb-queue-lib.sh", "bb-queue", "bb-hold", "bb-wait", "bb-netns-run", "bb-portgroup.py", "install-bb-fields.sh")) {
            Files.copy(OPS_BB.resolve(name), copy.resolve(name));
        }

        Files.writeString(copy.resolve("bb-build"),
                          Files.readString(copy.resolve("bb-build")) + "# edited after review\n");
        var run = install(copy.resolve("install-bb-fields.sh"), Map.of(), "builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(65);
        assertThat(run.stderr()).contains("local file is not the reviewed one");
        assertThat(sshCalls()).isEmpty();
    }

    @Test
    void anUnreachableTarget_isExit69_andNothingIsWritten() throws Exception {
        var tools = host("h1");

        host("h2");
        var before = listing(tools);
        var run = install(INSTALL, Map.of("SHIM_DOWN", "builder@h2"), "builder@h1", "builder@h2");

        assertThat(run.rc()).isEqualTo(69);
        assertThat(listing(tools)).isEqualTo(before);
    }

    @Test
    void aBadTarget_isUsageExit2_beforeAnySsh() throws Exception {
        var run = install("builder@h1;id");
        var noUser = install("192.0.2.10");

        assertThat(List.of(run.rc(), noUser.rc())).containsOnly(2);
        assertThat(sshCalls()).isEmpty();
    }

    /// There is no default host: a public script must not carry one, and an install must never go to a host nobody named.
    @Test
    void noTarget_isUsageExit2_beforeAnySsh() throws Exception {
        var run = install();

        assertThat(run.rc()).isEqualTo(2);
        assertThat(run.stderr()).contains("no default host");
        assertThat(sshCalls()).isEmpty();
    }

    /// Without the test seam the ssh key is the host config's; with no key configured nothing is reached (exit 78).
    @Test
    void realSsh_withoutAConfiguredKey_isExit78_beforeAnySsh() throws Exception {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME",
                dir.resolve("mac-home").toString());
        var run = BbFixtures.run(new ArrayList<>(List.of("bash", INSTALL.toString(), "builder@h1")),
                                 env,
                                 null,
                                 null);

        assertThat(run.rc()).isEqualTo(78);
        assertThat(run.stderr()).contains("no ssh key");
        assertThat(sshCalls()).isEmpty();
    }

    private BbFixtures.Outcome realSshInstall(String hostsText) throws Exception {
        var macHome = dir.resolve("mac-home");

        Files.writeString(Files.createDirectories(macHome.resolve(".config/bb-temper")).resolve("hosts"),
                          hostsText);
        var bin = Files.createDirectories(dir.resolve("fake-bin"));

        BbFixtures.executable(bin.resolve("ssh"),
                              "#!/bin/bash\necho \"$*\" >> \"" + dir.resolve("real-ssh.log") + "\"\nexit 255\n");
        var env = new HashMap<String, String>();

        env.put("PATH", bin + ":" + System.getenv("PATH"));
        env.put("HOME", macHome.toString());

        return BbFixtures.run(new ArrayList<>(List.of("bash", INSTALL.toString(), "builder@h1")),
                              env,
                              null,
                              null);
    }

    /// A hosts file saved with CRLF line ends gives the same ssh key as the LF one: the \r is never part of the path.
    @Test
    void aCrlfHostConfig_givesTheSameKeyAsTheLfOne() throws Exception {
        var run = realSshInstall("key ~/.ssh/k\r\nhost h1 builder@h1 - - auto\r\n");

        assertThat(run.rc()).as(run.stderr()).isEqualTo(69);
        assertThat(Files.readString(dir.resolve("real-ssh.log"))).contains("-i " + dir.resolve("mac-home")
                                                                          + "/.ssh/k -o BatchMode=yes")
                  .doesNotContain("\r");
    }

    /// Two `key` lines are malformed even when they name the same path: exit 78 naming the second line, before any ssh.
    @Test
    void aRepeatedKeyLine_isExit78_naming_theLine() throws Exception {
        var run = realSshInstall("key ~/.ssh/k\nkey ~/.ssh/k\nhost h1 builder@h1 - - auto\n");

        assertThat(run.rc()).isEqualTo(78);
        assertThat(run.stderr()).contains("line 2: key is listed twice");
        assertThat(dir.resolve("real-ssh.log")).doesNotExist();
    }
}
