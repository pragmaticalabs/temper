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
        assertThat(pin("BB_QUEUE_OLD")).isEqualTo(BbFixtures.BB_QUEUE_OLD)
                  .isEqualTo(BbFixtures.md5(BbFixtures.originalBbQueue()));
        assertThat(pin("BB_BUILD_OLD")).isEqualTo(BbFixtures.BB_BUILD_OLD)
                  .isEqualTo(BbFixtures.md5(BbFixtures.originalBbBuild()));
        assertThat(pin("BB_BUILD_PREV")).isEqualTo(BbFixtures.md5(prev("bb-build")));
        assertThat(pin("BB_LIB_PREV")).isEqualTo(BbFixtures.md5(prev("bb-queue-lib.sh")));
        assertThat(pin("BB_QUEUE_PREV")).isEqualTo(BbFixtures.md5(prev("bb-queue")));
        assertThat(pin("BB_QUEUE_LIVE")).isEqualTo(BbFixtures.md5(prev("bb-queue.live")));
        assertThat(pin("BB_PORTGROUP_PREV")).isEqualTo(BbFixtures.md5(prev("bb-portgroup.py")));
        assertThat(pin("BB_BUILD_LIVE")).isEqualTo(BbFixtures.md5(prev("bb-build.live")));
        assertThat(pin("BB_NETNS_PREV")).isEqualTo(BbFixtures.md5(prev("bb-netns-run")));
        assertThat(pin("BB_BUILD_PRE31")).isEqualTo(BbFixtures.md5(prev("bb-build.pre31")));
        assertThat(pin("BB_QUEUE_PRE31")).isEqualTo(BbFixtures.md5(prev("bb-queue.pre31")));
        assertThat(pin("BB_TICKETS_PRE31")).isEqualTo(BbFixtures.md5(prev("bb-tickets.pre31")));
        assertThat(pin("BB_BUILD_PRERC")).isEqualTo(BbFixtures.md5(prev("bb-build.prerc")));
        assertThat(pin("BB_QUEUE_PRERC")).isEqualTo(BbFixtures.md5(prev("bb-queue.prerc")));
        assertThat(pin("BB_BUILD_PREVIA")).isEqualTo(BbFixtures.md5(prev("bb-build.previa")));
        assertThat(pin("BB_QUEUE_PREVIA")).isEqualTo(BbFixtures.md5(prev("bb-queue.previa")));
        assertThat(pin("BB_MUTATE_PREVIA")).isEqualTo(BbFixtures.md5(prev("bb-mutate.previa")));
        assertThat(pin("BB_LIB_PRERC")).isEqualTo(BbFixtures.md5(prev("bb-queue-lib.sh.prerc")));
        assertThat(pin("BB_WAIT_PRERC")).isEqualTo(BbFixtures.md5(prev("bb-wait.prerc")));
        assertThat(pin("BB_WAIT_PREREFUSED")).isEqualTo(BbFixtures.md5(prev("bb-wait.prerefused")));
        assertThat(pin("BB_AWAIT_PREREFUSED")).isEqualTo(BbFixtures.md5(prev("bb-await.prerefused")));
        assertThat(pin("BB_BUILD_PREMEM")).isEqualTo(BbFixtures.md5(prev("bb-build.premem")));
        assertThat(pin("BB_LIB_PREMEM")).isEqualTo(BbFixtures.md5(prev("bb-queue-lib.sh.premem")));
        assertThat(pin("BB_QUEUE_PREMEM")).isEqualTo(BbFixtures.md5(prev("bb-queue.premem")));
        assertThat(pin("BB_BUILD_PRECAP")).isEqualTo(BbFixtures.md5(prev("bb-build.precap")));
        assertThat(pin("BB_BUILD_PREHELP")).isEqualTo(BbFixtures.md5(prev("bb-build.prehelp")));
        assertThat(pin("BB_LIB_PRELEDGER")).isEqualTo(BbFixtures.md5(prev("bb-queue-lib.sh.preledger")));
        assertThat(pin("BB_QUEUE_PRELEDGER")).isEqualTo(BbFixtures.md5(prev("bb-queue.preledger")));
        assertThat(pin("BB_HOLD_PRELEDGER")).isEqualTo(BbFixtures.md5(prev("bb-hold.preledger")));
        assertThat(pin("BB_LIB_PRECAP")).isEqualTo(BbFixtures.md5(prev("bb-queue-lib.sh.precap")));
        assertThat(pin("BB_QUEUE_PRECAP")).isEqualTo(BbFixtures.md5(prev("bb-queue.precap")));
        assertThat(pin("BB_LIB_PREOFFQ")).isEqualTo(BbFixtures.md5(prev("bb-queue-lib.sh.preoffq")));
        assertThat(pin("BB_QUEUE_PREOFFQ")).isEqualTo(BbFixtures.md5(prev("bb-queue.preoffq")));
        assertThat(pin("BB_TICKETS_PREOFFQ")).isEqualTo(BbFixtures.md5(prev("bb-tickets.preoffq")));
        assertThat(pin("BB_HOLD_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-hold")));
        assertThat(pin("BB_WAIT_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-wait")));
        assertThat(pin("BB_AWAIT_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-await")));
        assertThat(pin("BB_NETNS_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-netns-run")));
        assertThat(pin("BB_PORTGROUP_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-portgroup.py")));
        assertThat(pin("BB_MUTATE_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-mutate")));
        assertThat(pin("BB_MUTATE_OLD")).isEqualTo(BbFixtures.md5(prev("bb-mutate")));
        assertThat(pin("BB_CPRECORD_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-cp-record")));
        assertThat(pin("BB_TEST_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-test")));
        assertThat(pin("BB_TESTCOUNT_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-testcount")));
        assertThat(pin("BB_JBCTBOOT_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-jbct-bootstrap")));
        assertThat(pin("BB_BUILD_PREJBCT")).isEqualTo(BbFixtures.md5(prev("bb-build.prejbct")));
        assertThat(pin("BB_BUILD_PREHELP")).isEqualTo(BbFixtures.md5(prev("bb-build.prehelp")));
        assertThat(pin("BB_BUILD_PRERESULT")).isEqualTo(BbFixtures.md5(prev("bb-build.preresult")));
        assertThat(pin("BB_RESULT_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-result")));
        assertThat(pin("BB_RESULT_PRECP")).isEqualTo(BbFixtures.md5(prev("bb-result.precp")));
    }

    /// The bb-test lane helpers go in before bb-build (which calls bb-cp-record); a host holding a stranger's bb-test is refused, naming the one pin and
    /// `absent` is accepted. Mutation: drop a helper from accepted_of's new_of fallback or from FILES -> red.
    @Test
    void theBbTestLaneHelpers_areInstalledBeforeBbBuild_andAStrangersCopyIsRefused() throws Exception {
        var tools = host("h1");
        var run = install("builder@h1");
        var lines = run.stdout().lines().toList();

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(lines.stream().filter(l -> l.contains(" bb-cp-record ")).findFirst().orElseThrow()).isEqualTo("installed builder@h1 bb-cp-record before=absent after=" + pin("BB_CPRECORD_NEW"));
        assertThat(lines.indexOf("installed builder@h1 bb-cp-record before=absent after=" + pin("BB_CPRECORD_NEW"))).isLessThan(lines.indexOf("installed builder@h1 bb-build before=" + BbFixtures.BB_BUILD_OLD
                                                                                                                                             + " after=" + pin("BB_BUILD_NEW")));
        Files.writeString(tools.resolve("bb-test"), "a stranger's bb-test\n");
        var before = listing(tools);
        var refused = install("builder@h1");

        assertThat(refused.rc()).isEqualTo(65);
        assertThat(refused.stdout().trim()).isEqualTo("refused builder@h1 bb-test installed=" + BbFixtures.md5("a stranger's bb-test\n".getBytes())
                                                     + " expected=" + pin("BB_TEST_NEW"));
        assertThat(listing(tools)).isEqualTo(before);
    }

    /// The 2026-11-09 repo version of bb-build, bb-queue-lib.sh or bb-queue (what cloudbb-3 holds), vendored verbatim as `bb/<name>.prev`.
    private static byte[] prev(String name) throws IOException {
        try (var in = BbInstallIT.class.getResourceAsStream("/bb/" + name + ".prev")) {
            assertThat(in).as("test resource bb/" + name + ".prev").isNotNull();

            return in.readAllBytes();
        }
    }

    private static final List<String> NEW_FILES = List.of("bb-cp-record",
                                                          "bb-test",
                                                          "bb-testcount",
                                                          "bb-mutate",
                                                          "bb-hold",
                                                          "bb-wait",
                                                          "bb-await",
                                                          "bb-netns-run",
                                                          "bb-portgroup.py",
                                                          "bb-jbct-bootstrap",
                                                          "bb-result");

    @Test
    void success_installsAllFourFiles_keepsBackups_andASecondRunChangesNothing() throws Exception {
        var tools = host("h1");
        var oldBuild = Files.readAllBytes(tools.resolve("bb-build"));
        var oldInode = Files.readAttributes(tools.resolve("bb-build"), "unix:ino").get("ino");
        var otherBackup = tools.resolve("bb-build.pre-cap5-20261004");
        var first = install("builder@h1");

        assertThat(first.rc()).as(first.stdout() + first.stderr()).isZero();
        assertThat(first.stdout().lines().toList()).containsExactly("installed builder@h1 bb-cp-record before=absent after=" + pin("BB_CPRECORD_NEW"),
                                                                    "installed builder@h1 bb-test before=absent after=" + pin("BB_TEST_NEW"),
                                                                    "installed builder@h1 bb-testcount before=absent after=" + pin("BB_TESTCOUNT_NEW"),
                                                                    "installed builder@h1 bb-mutate before=absent after=" + pin("BB_MUTATE_NEW"),
                                                                    "installed builder@h1 bb-tickets before=absent after=" + pin("BB_TICKETS_NEW"),
                                                                    "installed builder@h1 bb-hold before=absent after=" + pin("BB_HOLD_NEW"),
                                                                    "installed builder@h1 bb-wait before=absent after=" + pin("BB_WAIT_NEW"),
                                                                    "installed builder@h1 bb-await before=absent after=" + pin("BB_AWAIT_NEW"),
                                                                    "installed builder@h1 bb-netns-run before=absent after=" + pin("BB_NETNS_NEW"),
                                                                    "installed builder@h1 bb-portgroup.py before=absent after=" + pin("BB_PORTGROUP_NEW"),
                                                                    "installed builder@h1 bb-jbct-bootstrap before=absent after=" + pin("BB_JBCTBOOT_NEW"),
                                                                    "installed builder@h1 bb-result before=absent after=" + pin("BB_RESULT_NEW"),
                                                                    "installed builder@h1 bb-queue-lib.sh before=" + BbFixtures.BB_LIB_OLD
                                                                   + " after=" + pin("BB_LIB_NEW"),
                                                                    "installed builder@h1 bb-queue before=" + BbFixtures.BB_QUEUE_OLD
                                                                   + " after=" + pin("BB_QUEUE_NEW"),
                                                                    "installed builder@h1 bb-build before=" + BbFixtures.BB_BUILD_OLD
                                                                   + " after=" + pin("BB_BUILD_NEW"));
        assertThat(tools.resolve("bb-build")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-build")));
        assertThat(tools.resolve("bb-tickets")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-tickets")));
        for (var f : NEW_FILES) {
            assertThat(tools.resolve(f)).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(f)));
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tools.resolve(f)))).as(f)
                      .isEqualTo("rwxr-xr-x");
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
        assertThat(second.stdout().lines().toList()).containsExactly("unchanged builder@h1 bb-cp-record " + pin("BB_CPRECORD_NEW"),
                                                                     "unchanged builder@h1 bb-test " + pin("BB_TEST_NEW"),
                                                                     "unchanged builder@h1 bb-testcount " + pin("BB_TESTCOUNT_NEW"),
                                                                     "unchanged builder@h1 bb-mutate " + pin("BB_MUTATE_NEW"),
                                                                     "unchanged builder@h1 bb-tickets " + pin("BB_TICKETS_NEW"),
                                                                     "unchanged builder@h1 bb-hold " + pin("BB_HOLD_NEW"),
                                                                     "unchanged builder@h1 bb-wait " + pin("BB_WAIT_NEW"),
                                                                     "unchanged builder@h1 bb-await " + pin("BB_AWAIT_NEW"),
                                                                     "unchanged builder@h1 bb-netns-run " + pin("BB_NETNS_NEW"),
                                                                     "unchanged builder@h1 bb-portgroup.py " + pin("BB_PORTGROUP_NEW"),
                                                                     "unchanged builder@h1 bb-jbct-bootstrap " + pin("BB_JBCTBOOT_NEW"),
                                                                     "unchanged builder@h1 bb-result " + pin("BB_RESULT_NEW"),
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
        assertThat(run.stdout()).as("an off-pin bb-hold is still refused")
                  .contains("refused builder@h1 bb-hold installed=");
        Files.delete(tools.resolve("bb-hold"));
        var ok = install("builder@h1");

        assertThat(ok.rc()).as(ok.stdout() + ok.stderr()).isZero();
        assertThat(ok.stdout()).contains("installed builder@h1 bb-build before=" + pin("BB_BUILD_PREV")
                                        + " after=" + pin("BB_BUILD_NEW"),
                                         "installed builder@h1 bb-queue-lib.sh before=" + pin("BB_LIB_PREV")
                                        + " after=" + pin("BB_LIB_NEW"),
                                         "installed builder@h1 bb-queue before=" + pin("BB_QUEUE_PREV")
                                        + " after=" + pin("BB_QUEUE_NEW"));
        assertThat(ok.stdout()).contains("installed builder@h1 bb-portgroup.py before=" + pin("BB_PORTGROUP_PREV")
                                        + " after=" + pin("BB_PORTGROUP_NEW"));
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
        assertThat(run.stdout()).contains("installed builder@h1 bb-build before=" + pin("BB_BUILD_LIVE")
                                         + " after=" + pin("BB_BUILD_NEW"));
        assertThat(tools.resolve("bb-build.bak-" + pin("BB_BUILD_LIVE"))).hasBinaryContent(prev("bb-build.live"));
    }

    /// The bb-build, bb-queue and bb-tickets live since #23 (BB_*_PRE31: flag always on, no [no-requireDocker] mark, capital-letter ticket keys dropped) are accepted sources and are replaced, backed up under their md5.
    @Test
    void aHostRunningThePre31Tools_isUpdatedToTheOnesThatKnowTheOptOut() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        for (var name : List.of("bb-build", "bb-queue", "bb-tickets")) {
            Files.write(tools.resolve(name), prev(name + ".pre31"));
        }

        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        for (var name : List.of("bb-build", "bb-queue", "bb-tickets")) {
            var key = name.toUpperCase().replace('-', '_') + "_";

            assertThat(run.stdout()).contains("installed builder@h1 " + name
                                             + " before=" + pin(key + "PRE31")
                                             + " after=" + pin(key + "NEW"));
            assertThat(tools.resolve(name)).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(name)));
        }

        assertThat(tools.resolve("bb-build.bak-" + pin("BB_BUILD_PRE31"))).hasBinaryContent(prev("bb-build.pre31"));
    }

    /// The bb-build, bb-queue-lib.sh, bb-queue and bb-wait live since #33 (BB_*_PRERC: the ticket rc= was read from .bb/rc) are accepted sources and are replaced, backed up under their md5.
    @Test
    void aHostRunningThePreRcTools_isUpdatedToTheOnesThatRecordTheExitStatus() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        var names = List.of("bb-build", "bb-queue", "bb-queue-lib.sh", "bb-wait");

        for (var name : names) {
            Files.write(tools.resolve(name), prev(name + ".prerc"));
        }

        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        for (var name : names) {
            var key = (name.equals("bb-queue-lib.sh")
                       ? "BB_LIB"
                       : name.toUpperCase().replace('-', '_')) + "_";

            assertThat(run.stdout()).contains("installed builder@h1 " + name
                                             + " before=" + pin(key + "PRERC")
                                             + " after=" + pin(key + "NEW"));
            assertThat(tools.resolve(name)).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(name)));
            assertThat(tools.resolve(name + ".bak-" + pin(key + "PRERC"))).hasBinaryContent(prev(name + ".prerc"));
        }
    }

    /// The bb-build, bb-queue and bb-mutate before issue #46 (BB_*_PREVIA: no via= field, no [mutant] mark, no BB_VIA) are accepted sources and are replaced, backed up under their md5.
    @Test
    void aHostRunningThePreViaTools_isUpdatedToTheOnesThatMarkMutantRuns() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        var names = List.of("bb-build", "bb-queue", "bb-mutate");

        for (var name : names) {
            Files.write(tools.resolve(name), prev(name + ".previa"));
        }

        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        for (var name : names) {
            var key = name.toUpperCase().replace('-', '_') + "_";

            assertThat(run.stdout()).contains("installed builder@h1 " + name
                                             + " before=" + pin(key + "PREVIA")
                                             + " after=" + pin(key + "NEW"));
            assertThat(tools.resolve(name)).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(name)));
            assertThat(tools.resolve(name + ".bak-" + pin(key + "PREVIA"))).hasBinaryContent(prev(name + ".previa"));
        }
    }

    /// The bb-build, bb-queue-lib.sh and bb-queue before issue #53 (BB_*_PREMEM: no memory floor, no MEMORY GATE line, pick without the caller id) are accepted sources and are replaced, backed up under their md5.
    @Test
    void aHostRunningThePreMemoryFloorQueue_isUpdatedToTheOneThatHoldsBelowTheFloor() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        var names = List.of("bb-build", "bb-queue-lib.sh", "bb-queue");

        for (var name : names) {
            Files.write(tools.resolve(name), prev(name + ".premem"));
        }

        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        for (var name : names) {
            var key = switch (name) {
                case "bb-build" -> "BB_BUILD_";
                case "bb-queue-lib.sh" -> "BB_LIB_";
                default -> "BB_QUEUE_";
            };

            assertThat(run.stdout()).contains("installed builder@h1 " + name
                                             + " before=" + pin(key + "PREMEM")
                                             + " after=" + pin(key + "NEW"));
            assertThat(tools.resolve(name)).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(name)));
            assertThat(tools.resolve(name + ".bak-" + pin(key + "PREMEM"))).hasBinaryContent(prev(name + ".premem"));
        }
    }

    /// The bb-build, bb-queue-lib.sh and bb-queue of issue #53 (BB_*_PRECAP: no host reactor cap, no off-queue JVM headroom, no HOST CAP line) are accepted sources and are replaced, backed up under their md5.
    @Test
    void aHostRunningThePreHostCapQueue_isUpdatedToTheOneWithTheHostCap() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        var names = List.of("bb-build", "bb-queue-lib.sh", "bb-queue");

        for (var name : names) {
            Files.write(tools.resolve(name), prev(name + ".precap"));
        }

        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        for (var name : names) {
            var key = switch (name) {
                case "bb-build" -> "BB_BUILD_";
                case "bb-queue-lib.sh" -> "BB_LIB_";
                default -> "BB_QUEUE_";
            };

            assertThat(run.stdout()).contains("installed builder@h1 " + name
                                             + " before=" + pin(key + "PRECAP")
                                             + " after=" + pin(key + "NEW"));
            assertThat(tools.resolve(name)).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(name)));
            assertThat(tools.resolve(name + ".bak-" + pin(key + "PRECAP"))).hasBinaryContent(prev(name + ".precap"));
        }
    }

    /// The bb-queue-lib.sh and bb-queue of #66 and the bb-hold of #56 (BB_*_PRELEDGER: the cap counted reactors by process pattern; the
    /// 2026-11-19 CTO ruling replaced that with the launch ledger) are accepted sources and are replaced, backed up under their md5.
    @Test
    void aHostRunningThePreLedgerQueue_isUpdatedToTheLaunchLedger() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        var names = List.of("bb-queue-lib.sh", "bb-queue", "bb-hold");

        for (var name : names) {
            Files.write(tools.resolve(name), prev(name + ".preledger"));
        }

        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        for (var name : names) {
            var key = switch (name) {
                case "bb-queue-lib.sh" -> "BB_LIB_";
                case "bb-queue" -> "BB_QUEUE_";
                default -> "BB_HOLD_";
            };

            assertThat(run.stdout()).contains("installed builder@h1 " + name
                                             + " before=" + pin(key + "PRELEDGER")
                                             + " after=" + pin(key + "NEW"));
            assertThat(tools.resolve(name)).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(name)));
            assertThat(tools.resolve(name + ".bak-" + pin(key + "PRELEDGER"))).hasBinaryContent(prev(name + ".preledger"));
        }
    }

    /// The bb-build of #64/#66 (BB_BUILD_PREHELP: no bb-cp-record hook, no BB_LAUNCH, no launch ledger) is an accepted source and is replaced, backed up under its md5.
    @Test
    void aHostRunningThePreBbTestBuild_isUpdatedToTheOneWithTheClasspathRecord() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        Files.write(tools.resolve("bb-build"), prev("bb-build.prehelp"));
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("installed builder@h1 bb-build before=" + pin("BB_BUILD_PREHELP")
                                         + " after=" + pin("BB_BUILD_NEW"));
        assertThat(tools.resolve("bb-build")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-build")));
        assertThat(tools.resolve("bb-build.bak-" + pin("BB_BUILD_PREHELP"))).hasBinaryContent(prev("bb-build.prehelp"));
    }

    /// The bb-queue-lib.sh, bb-queue and bb-tickets before the off-queue reactor list (BB_*_PREOFFQ, 2026-11-20) are accepted sources and are
    /// replaced, backed up under their md5. Mutation: leave a PREOFFQ out of its accepted list -> the install is refused (65), red.
    @Test
    void aHostRunningThePreOffQueueTools_isUpdatedToTheOnesThatListOffQueueReactors() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        var names = List.of("bb-queue-lib.sh", "bb-queue", "bb-tickets");

        for (var name : names) {
            Files.write(tools.resolve(name), prev(name + ".preoffq"));
        }

        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        for (var name : names) {
            var key = switch (name) {
                case "bb-queue-lib.sh" -> "BB_LIB_";
                case "bb-queue" -> "BB_QUEUE_";
                default -> "BB_TICKETS_";
            };

            assertThat(run.stdout()).contains("installed builder@h1 " + name
                                             + " before=" + pin(key + "PREOFFQ")
                                             + " after=" + pin(key + "NEW"));
            assertThat(tools.resolve(name)).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(name)));
            if (!name.equals("bb-tickets")) {
                // the installer backs up every file but bb-tickets
                assertThat(tools.resolve(name + ".bak-" + pin(key + "PREOFFQ"))).hasBinaryContent(prev(name + ".preoffq"));
            }
        }
    }

    /// The bb-netns-run of 2026-11-11 (BB_NETNS_PREV, no docker socket in the container; live on c3/c4/d1) is an accepted source and is replaced, backed up under its md5.
    @Test
    void aHostRunningThePreviousNetnsRun_isUpdatedToTheOneThatReachesDocker() throws Exception {
        var tools = host("h1");

        assertThat(install("builder@h1").rc()).isZero();
        Files.write(tools.resolve("bb-netns-run"), prev("bb-netns-run"));
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("installed builder@h1 bb-netns-run before=" + pin("BB_NETNS_PREV")
                                         + " after=" + pin("BB_NETNS_NEW"));
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
        assertThat(run.stdout()).contains("installed builder@h1 bb-queue before=" + pin("BB_QUEUE_LIVE")
                                         + " after=" + pin("BB_QUEUE_NEW"));
        assertThat(tools.resolve("bb-queue")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-queue")));
    }

    /// bb-jbct-bootstrap (2026-11-19): absent before its first install, installed BEFORE the bb-build that calls it; a host's bb-build at the
    /// previous pin (BB_BUILD_PREJBCT, no hook) is an accepted source, replaced and backed up under its md5.
    /// Mutation: move bb-jbct-bootstrap behind bb-build in FILES -> the order assertion is red.
    @Test
    void theJbctBootstrapHelper_isInstalledBeforeTheBbBuildThatCallsIt_andAPreviousBbBuildIsUpgraded() throws Exception {
        var tools = host("h1");

        Files.write(tools.resolve("bb-build"), prev("bb-build.prejbct"));
        var run = install("builder@h1");
        var lines = run.stdout().lines().toList();

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(lines).contains("installed builder@h1 bb-jbct-bootstrap before=absent after=" + pin("BB_JBCTBOOT_NEW"),
                                   "installed builder@h1 bb-build before=" + pin("BB_BUILD_PREJBCT")
                                  + " after=" + pin("BB_BUILD_NEW"));
        assertThat(lines.indexOf("installed builder@h1 bb-jbct-bootstrap before=absent after=" + pin("BB_JBCTBOOT_NEW"))).isLessThan(lines.indexOf("installed builder@h1 bb-build before=" + pin("BB_BUILD_PREJBCT")
                                                                                                                                                  + " after=" + pin("BB_BUILD_NEW")));
        assertThat(tools.resolve("bb-build.bak-" + pin("BB_BUILD_PREJBCT"))).hasBinaryContent(prev("bb-build.prejbct"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tools.resolve("bb-jbct-bootstrap")))).isEqualTo("rwxr-xr-x");
    }

    /// bb-result: absent before its first install. The bb-build the hosts run now (BB_BUILD_PREJBCT, soon BB_BUILD_PREHELP = #64's) are both accepted
    /// sources for the --help bb-build; each is replaced and backed up under its md5.
    /// Mutation: drop BB_BUILD_PREHELP from accepted_of -> the PREHELP row is refused, exit 65, red.
    @Test
    void bothBbBuildsThatPrecedeTheHelpOne_areAcceptedAndReplaced_andBbResultIsInstalled() throws Exception {
        for (var old : List.of("prejbct", "prehelp", "preresult")) {
            var tools = host("h-" + old);

            Files.write(tools.resolve("bb-build"), prev("bb-build." + old));
            var run = install("builder@h-" + old);
            var pinName = "BB_BUILD_PRE" + old.substring(3).toUpperCase();

            assertThat(run.rc()).as(old + ": " + run.stdout() + run.stderr()).isZero();
            assertThat(run.stdout().lines().toList()).contains("installed builder@h-" + old + " bb-result before=absent after=" + pin("BB_RESULT_NEW"),
                                                               "installed builder@h-" + old + " bb-build before=" + pin(pinName)
                                                              + " after=" + pin("BB_BUILD_NEW"));
            assertThat(tools.resolve("bb-build.bak-" + pin(pinName))).hasBinaryContent(prev("bb-build." + old));
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tools.resolve("bb-result")))).isEqualTo("rwxr-xr-x");
        }
    }

    /// A bb-jbct-bootstrap on a host that is not the pinned file is refused (exit 65, nothing written anywhere), naming the one accepted pin.
    /// Mutation: accept any md5 for bb-jbct-bootstrap in phase 1 -> exit 0, red.
    @Test
    void aStrangersJbctBootstrapHelper_isRefused_withTheOnePinNamed_andNothingIsWritten() throws Exception {
        var tools = host("h1");

        Files.writeString(tools.resolve("bb-jbct-bootstrap"), "someone else's helper\n");
        var before = listing(tools);
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(65);
        assertThat(run.stdout().lines().toList()).containsExactly("refused builder@h1 bb-jbct-bootstrap installed=" + BbFixtures.md5("someone else's helper\n".getBytes())
                                                                 + " expected=" + pin("BB_JBCTBOOT_NEW"));
        assertThat(listing(tools)).isEqualTo(before);
    }

    /// The bb-mutate on all four hosts (BB_MUTATE_OLD, batch credit unsound for multi-module specs) is an accepted source and is replaced, backed up under its md5.
    @Test
    void aHostRunningTheOldBbMutate_isUpdatedToTheBatchSoundOne() throws Exception {
        var tools = host("h1");

        Files.write(tools.resolve("bb-mutate"), prev("bb-mutate"));
        var run = install("builder@h1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("installed builder@h1 bb-mutate before=" + pin("BB_MUTATE_OLD")
                                         + " after=" + pin("BB_MUTATE_NEW"));
        assertThat(tools.resolve("bb-mutate")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-mutate")));
        assertThat(tools.resolve("bb-mutate.bak-" + pin("BB_MUTATE_OLD"))).hasBinaryContent(prev("bb-mutate"));
    }

    /// The class behind vbt7 P1: a pin set that refuses what the hosts run today blocks every install. `bb/live-md5s` holds the ~/tools md5s
    /// read from cloudbb-5 on 2026-11-20, one line per FILES entry. A host is built holding exactly that set (each md5 resolved to the repo
    /// file or a vendored `.prev`), and the install must accept it: exit 0, every live file either unchanged or replaced and backed up.
    /// Mutation: drop the bb-result case from accepted_of (bb-result then accepts only BB_RESULT_NEW) -> refused bb-result, exit 65, red.
    @Test
    void aHostAtTodaysLiveMd5s_isAcceptedForEveryFile_andUpgraded() throws Exception {
        var live = new java.util.LinkedHashMap<String, String>();

        try (var in = BbInstallIT.class.getResourceAsStream("/bb/live-md5s")) {
            assertThat(in).as("test resource bb/live-md5s").isNotNull();
            new String(in.readAllBytes()).lines()
                                         .filter(l -> !l.isBlank() && !l.startsWith("#"))
                                         .map(l -> l.split(" "))
                                         .forEach(f -> live.put(f[0], f[1]));
        }

        var files = Pattern.compile("(?m)^FILES=\\(([^)]*)\\)").matcher(Files.readString(INSTALL));

        assertThat(files.find()).as("FILES=(...) in install-bb-fields.sh").isTrue();
        assertThat(live.keySet()).as("bb/live-md5s names every FILES entry, and only those")
                                 .containsExactlyInAnyOrder(files.group(1).trim().split("\\s+"));

        var candidates = new HashMap<String, byte[]>();

        try (Stream<Path> walk = Files.list(Path.of(BbInstallIT.class.getResource("/bb").toURI()))) {
            for (var path : walk.filter(p -> p.getFileName().toString().endsWith(".prev")).toList()) {
                var bytes = Files.readAllBytes(path);
                candidates.putIfAbsent(BbFixtures.md5(bytes), bytes);
            }
        }
        for (var f : live.keySet()) {
            var bytes = Files.readAllBytes(OPS_BB.resolve(f));
            candidates.put(BbFixtures.md5(bytes), bytes);
        }

        var tools = host("h-live");

        for (var e : live.entrySet()) {
            if (e.getValue().equals("absent")) {
                continue;
            }
            assertThat(candidates).as("live " + e.getKey() + " " + e.getValue() + " is the repo file or a vendored .prev").containsKey(e.getValue());
            Files.write(tools.resolve(e.getKey()), candidates.get(e.getValue()));
        }

        var run = install("builder@h-live");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        for (var e : live.entrySet()) {
            var after = BbFixtures.md5(OPS_BB.resolve(e.getKey()));
            var expected = e.getValue().equals(after)
                           ? "unchanged builder@h-live " + e.getKey()
                           : "installed builder@h-live " + e.getKey() + " before=" + e.getValue() + " after=" + after;

            assertThat(run.stdout().lines().toList()).as(e.getKey()).anyMatch(l -> l.startsWith(expected));
            assertThat(tools.resolve(e.getKey())).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve(e.getKey())));
        }
    }

    /// c4/d1 shape: a host already holding the live set is accepted and nothing is written.
    @Test
    void aHostAtTheLiveSet_isAccepted_andNothingIsWritten() throws Exception {
        var tools = host("h1");

        for (var f : List.of("bb-build",
                             "bb-queue-lib.sh",
                             "bb-queue",
                             "bb-tickets",
                             "bb-hold",
                             "bb-wait",
                             "bb-await",
                             "bb-netns-run",
                             "bb-portgroup.py",
                             "bb-mutate",
                             "bb-jbct-bootstrap",
                             "bb-cp-record",
                             "bb-test",
                             "bb-testcount",
                             "bb-result")) {
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
        assertThat(run.stdout().lines().toList()).containsExactly("refused builder@h2 bb-build installed=" + BbFixtures.md5("a diverged copy\n".getBytes())
                                                                 + " expected=" + BbFixtures.BB_BUILD_OLD
                                                                 + "|a1100c916b866ad3716bc4095ddb1991|cae49573d06f632d3081f7d5be1e9b66|" + pin("BB_BUILD_LIVE")
                                                                 + "|" + pin("BB_BUILD_PRE31")
                                                                 + "|" + pin("BB_BUILD_PRERC")
                                                                 + "|" + pin("BB_BUILD_PREVIA")
                                                                 + "|" + pin("BB_BUILD_PREMEM")
                                                                 + "|" + pin("BB_BUILD_PRECAP")
                                                                 + "|" + pin("BB_BUILD_PREJBCT")
                                                                 + "|" + pin("BB_BUILD_PREHELP")
                                                                 + "|" + pin("BB_BUILD_PRERESULT")
                                                                 + "|" + pin("BB_BUILD_NEW"));
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
        assertThat(run.stdout()
                      .lines()
                      .map(line -> line.replaceAll("installed=[0-9a-z]+", "installed=X")
                                       .replaceAll("expected=[0-9a-f|]+", "expected=Y"))
                      .toList()).containsExactly("refused builder@h1 bb-queue-lib.sh installed=X expected=Y",
                                                 "refused builder@h1 bb-queue installed=X expected=Y",
                                                 "refused builder@h1 bb-tickets installed=X expected=Y",
                                                 "refused builder@h1 bb-hold installed=X expected=Y",
                                                 "refused builder@h2 bb-build installed=X expected=Y");
        assertThat(run.stdout()).contains("bb-build installed=absent");
        assertThat(run.stdout()).as("every accepted pin is named")
                  .contains("bb-queue-lib.sh installed=" + BbFixtures.md5("other lib\n".getBytes())
                           + " expected=" + BbFixtures.BB_LIB_OLD
                           + "|621b2252c3da05e7dd1161d076908864|" + pin("BB_LIB_PRERC")
                           + "|" + pin("BB_LIB_PREMEM")
                           + "|" + pin("BB_LIB_PRECAP")
                           + "|" + pin("BB_LIB_PREOFFQ")
                           + "|" + pin("BB_LIB_PRELEDGER")
                           + "|" + pin("BB_LIB_NEW"))
                  .contains("bb-queue installed=" + BbFixtures.md5("unrelated tool\n".getBytes())
                           + " expected=" + BbFixtures.BB_QUEUE_OLD
                           + "|a04b7be02f840d4f1e2bd19c54c52b3f|" + pin("BB_QUEUE_LIVE")
                           + "|" + pin("BB_QUEUE_PRE31")
                           + "|" + pin("BB_QUEUE_PRERC")
                           + "|" + pin("BB_QUEUE_PREVIA")
                           + "|" + pin("BB_QUEUE_PREMEM")
                           + "|" + pin("BB_QUEUE_PRECAP")
                           + "|" + pin("BB_QUEUE_PREOFFQ")
                           + "|" + pin("BB_QUEUE_PRELEDGER")
                           + "|" + pin("BB_QUEUE_NEW"));
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

        for (var name : List.of("bb-build",
                                "bb-tickets",
                                "bb-queue-lib.sh",
                                "bb-queue",
                                "bb-hold",
                                "bb-wait",
                                "bb-await",
                                "bb-netns-run",
                                "bb-portgroup.py",
                                "bb-jbct-bootstrap",
                                "bb-result",
                                "bb-mutate",
                                "bb-cp-record",
                                "bb-test",
                                "bb-testcount",
                                "install-bb-fields.sh")) {
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
