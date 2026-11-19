package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;


/// `ops/bb/bb-temper` path choice for a host behind a jump host (2026-11-19): direct first, jump on a CONNECT failure only,
/// the last working path cached per host. Fake `ssh` first on `PATH`: it tells the path by the ProxyCommand option, the kind
/// by the remote command (`true` = the path probe, anything else = the real command), logs both, and behaves per
/// `direct.mode` / `jump.mode` (ok | refuse = ssh 255 at once | hang) and `cmd.rc` (the real command's own exit status).
class BbTemperPathIT {
    private static final String HOST = "cloudbb-3";

    @TempDir
    Path dir;

    private Path home;

    @BeforeEach
    void stubs() throws Exception {
        home = Files.createDirectories(dir.resolve("home"));
        var bin = Files.createDirectories(dir.resolve("bin"));

        TestHosts.write(home);
        Files.writeString(home.resolve(TestHosts.KNOWN), "x ssh-ed25519 AAAA\n");
        Files.createDirectories(home.resolve(".temper/bb-bind"));
        Files.writeString(home.resolve(".temper/bb-bind/t3-x"), HOST + "\n");
        // %C stands for the host here. A live master is a file at the ControlPath ssh would use: the command line's, else the
        // one ~/.ssh/config would give (an alias with ControlPath ~/.ssh/cm/%C). A live master answers whatever the network does.
        MacScripts.executable(bin.resolve("ssh"),
                              """
                path=direct; host=; cp=
                for a in "$@"; do case $a in
                  ProxyCommand=*) path=jump ;;
                  ControlPath=*) cp=$(printf %s "${a#ControlPath=}" | tr -d '"') ;;
                  *@*) [ -n "$host" ] || host=$a ;;
                esac; done
                [ -n "$cp" ] || cp=$HOME/.ssh/cm/%C
                sock=${cp//%C/C-$host}
                cmd=${@: -1}; kind=cmd; [ "$cmd" != true ] || kind=probe
                echo "$path $kind" >> "$STUBS/calls.log"
                echo "$path $*" >> "$STUBS/args.log"
                if [ ! -e "$sock" ]; then
                  case $(cat "$STUBS/$path.mode" 2> /dev/null || echo ok) in
                    refuse) exit 255 ;;
                    hang) sleep 30; exit 255 ;;
                  esac
                  mkdir -p "$(dirname "$sock")"; : > "$sock"
                fi
                [ "$kind" = probe ] && exit 0
                exit "$(cat "$STUBS/cmd.rc" 2> /dev/null || echo 0)"
                """);
        MacScripts.executable(bin.resolve("fake-submit"),
                              "printf '%s|%s|%s\\n' \"$1\" \"${BB_SSH_PROXY-unset}\" \"${BB_KNOWN_HOSTS-unset}\" > \"$STUBS/submit.env\"\n");
    }

    private void mode(String path, String mode) throws IOException {
        Files.writeString(dir.resolve(path + ".mode"), mode);
    }

    private void cache(String path, long ageSeconds) throws IOException {
        Files.createDirectories(home.resolve(".temper/bb-path"));
        Files.writeString(home.resolve(".temper/bb-path/" + HOST),
                          path + " " + (Instant.now().getEpochSecond() - ageSeconds) + "\n");
    }

    private String cached() throws IOException {
        return Files.readString(home.resolve(".temper/bb-path/" + HOST)).split(" ") [0];
    }

    private MacScripts.Result build(String... more) throws Exception {
        return run(List.of("build", "t3-x", "-B"), more);
    }

    private MacScripts.Result run(List<String> verb, String... more) throws Exception {
        var env = new LinkedHashMap<>(MacScripts.env(Map.of("HOME",
                                                            home.toString(),
                                                            "PATH",
                                                            dir.resolve("bin") + ":/usr/bin:/bin",
                                                            "STUBS",
                                                            dir.toString(),
                                                            "BB_OWNER",
                                                            "t3",
                                                            "BB_SUBMIT",
                                                            dir.resolve("absent").toString())));

        for (var i = 0; i < more.length; i += 2) {
            env.put(more[i], more[i + 1]);
        }

        var command = new ArrayList<>(List.of("bash", MacScripts.BB_TEMPER.toString()));

        command.addAll(verb);

        return MacScripts.run(command, env, dir);
    }

    private List<String> calls() throws IOException {
        var file = dir.resolve("calls.log");

        return Files.exists(file)
               ? Files.readAllLines(file)
               : List.of();
    }

    private String cacheDirListing() throws IOException {
        try (var files = Files.list(home.resolve(".temper/bb-path"))) {
            return files.map(f -> f.getFileName()
                                   .toString())
                        .sorted()
                        .toList()
                        .toString();
        }
    }

    private long count(String call) throws IOException {
        return calls().stream()
                    .filter(call::equals)
                    .count();
    }

    @Test
    void directAnswers_directIsUsed_andNamedOnStderr() throws Exception {
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("bb-temper: cloudbb-3 via direct\n");
        assertThat(calls()).containsExactly("direct probe", "direct cmd");
        assertThat(cached()).isEqualTo("direct");
    }

    @Test
    void directRefused_theJumpIsUsed() throws Exception {
        mode("direct", "refuse");
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("bb-temper: cloudbb-3 via jump(" + TestHosts.BIGBOY + ")\n");
        assertThat(calls()).containsExactly("direct probe", "jump probe", "jump cmd");
        assertThat(cached()).isEqualTo("jump");
    }

    @Test
    void directHangs_theJumpIsUsedAfterTheProbeWall() throws Exception {
        mode("direct", "hang");
        var t0 = System.nanoTime();
        var run = build();
        var seconds = (System.nanoTime() - t0) / 1_000_000_000.0;

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(calls()).containsExactly("direct probe", "jump probe", "jump cmd");
        assertThat(seconds).as("5 s timeout + 2 s wall + the jump").isBetween(6.5, 12.0);
    }

    @Test
    void cacheHit_noProbeOfTheOtherPath() throws Exception {
        cache("jump", 60);
        mode("direct", "refuse");
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("via jump(");
        assertThat(calls()).containsExactly("jump probe", "jump cmd");
    }

    @Test
    void cacheExpired_reprobesDirectFirst() throws Exception {
        cache("jump", 31 * 60);
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(calls()).containsExactly("direct probe", "direct cmd");
        assertThat(cached()).isEqualTo("direct");
    }

    @Test
    void cacheTtl_isConfigurable() throws Exception {
        cache("jump", 3 * 60);
        var run = build("BB_PATH_TTL", "2");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(calls()).containsExactly("direct probe", "direct cmd");
    }

    @Test
    void cachedPathFails_reprobesAndSwitches() throws Exception {
        cache("direct", 60);
        mode("direct", "refuse");
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("cached path direct failed to connect").contains("via jump(");
        assertThat(calls()).containsExactly("direct probe", "jump probe", "jump cmd");
        assertThat(cached()).isEqualTo("jump");
    }

    @Test
    void remoteCommandExit1_onDirect_isFinal_noRetryViaTheJump() throws Exception {
        Files.writeString(dir.resolve("cmd.rc"), "1");
        var run = build();

        assertThat(run.rc()).as(run.err()).isEqualTo(1);
        assertThat(count("direct cmd")).as("the command ran once").isEqualTo(1);
        assertThat(calls()).noneMatch(c -> c.startsWith("jump"));
    }

    @Test
    void remoteCommandExit255_onDirect_isFinal_noRetryViaTheJump() throws Exception {
        Files.writeString(dir.resolve("cmd.rc"), "255");
        var run = build();

        assertThat(run.rc()).as(run.err()).isEqualTo(255);
        assertThat(count("direct cmd")).isEqualTo(1);
        assertThat(calls()).noneMatch(c -> c.startsWith("jump"));
    }

    @Test
    void forcedDirect_directDown_failsLoudly_noFallback() throws Exception {
        mode("direct", "refuse");
        var run = build("BB_PATH", "direct");

        assertThat(run.rc()).isEqualTo(69);
        assertThat(run.err()).contains("cloudbb-3 unreachable via direct").contains("BB_PATH=direct");
        assertThat(calls()).noneMatch(c -> c.startsWith("jump"));
    }

    @Test
    void forcedJump_neverProbesDirect() throws Exception {
        var run = build("BB_PATH", "jump");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(calls()).containsExactly("jump probe", "jump cmd");
    }

    @Test
    void bothDown_failsLoudly_namingBoth() throws Exception {
        mode("direct", "refuse");
        mode("jump", "refuse");
        var run = build();

        assertThat(run.rc()).isEqualTo(69);
        assertThat(run.err()).contains("direct and jump(" + TestHosts.BIGBOY + ") both failed to connect");
        assertThat(calls()).doesNotContain("direct cmd", "jump cmd");
    }

    @Test
    void directPath_pinsTheHostKeyLikeTheJumpPath() throws Exception {
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        var args = Files.readString(dir.resolve("args.log"));

        assertThat(args).contains("UserKnownHostsFile=\"" + home + "/" + TestHosts.KNOWN + "\"")
                  .contains("StrictHostKeyChecking=yes")
                  .doesNotContain("ProxyCommand")
                  .doesNotContain("StrictHostKeyChecking=no");
    }

    @Test
    void bbSubmit_getsTheProxyOnlyOnTheJumpPath_andThePinnedFileOnBoth() throws Exception {
        var submit = dir.resolve("bin/fake-submit").toString();
        var known = home + "/" + TestHosts.KNOWN;

        assertThat(build("BB_SUBMIT", submit).rc()).isZero();
        assertThat(Files.readString(dir.resolve("submit.env")).strip()).isEqualTo(TestHosts.C3 + "|unset|" + known);
        assertThat(build("BB_SUBMIT", submit, "BB_PATH", "jump").rc()).isZero();
        assertThat(Files.readString(dir.resolve("submit.env")).strip()).isEqualTo(TestHosts.C3
                                                                                 + "|" + TestHosts.BIGBOY
                                                                                 + "|" + known);
    }

    @Test
    void bigboy_hasNoPathChoice_noProbeNoLine() throws Exception {
        Files.writeString(home.resolve(".temper/bb-bind/t3-x"), "bigboy\n");
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).doesNotContain(" via ");
        assertThat(calls()).containsExactly("direct cmd");
    }

    // ---- route-specific control masters (M1) ------------------------------------------------------------------------
    @Test
    void liveJumpMaster_doesNotSatisfyTheDirectProbe() throws Exception {
        mode("direct", "refuse");
        assertThat(build("BB_PATH", "jump").rc()).isZero();
        Files.delete(dir.resolve("calls.log"));
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("via jump(");
        assertThat(calls()).containsExactly("direct probe", "jump probe", "jump cmd");
        assertThat(cached()).isEqualTo("jump");
    }

    @Test
    void aliasMaster_atTheUnprefixedControlPath_doesNotSatisfyTheDirectProbe() throws Exception {
        var alias = Files.createDirectories(home.resolve(".ssh/cm")).resolve("C-" + TestHosts.C3);

        Files.createFile(alias);
        mode("direct", "refuse");
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(calls()).containsExactly("direct probe", "jump probe", "jump cmd");
    }

    @Test
    void controlPaths_areRouteSpecific_quoted_andTheInnerProxyHopTakesNoMaster() throws Exception {
        var cm = "ControlPath=\"" + home + "/.ssh/cm/";

        assertThat(build().rc()).isZero();
        var direct = Files.readString(dir.resolve("args.log"));

        assertThat(direct).contains(cm + "bbd-%C\"", "ControlMaster=auto", "ControlPersist=10m").doesNotContain("bbj-");
        Files.delete(dir.resolve("args.log"));
        assertThat(build("BB_PATH", "jump").rc()).isZero();
        var jump = Files.readString(dir.resolve("args.log"));

        assertThat(jump).contains(cm + "bbj-%C\"", "ControlMaster=auto").doesNotContain("bbd-");
        assertThat(jump).contains("-o ControlMaster=no -o ControlPath=none -o ConnectTimeout=15 -W %h:%p");
    }

    @Test
    void controlDirectory_isCreatedPrivate() throws Exception {
        assertThat(Files.exists(home.resolve(".ssh"))).isFalse();
        assertThat(build().rc()).isZero();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve(".ssh")))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve(".ssh/cm")))).isEqualTo("rwx------");
    }

    // ---- the cache file ------------------------------------------------------------------------------------------
    @Test
    void cacheEpochTooLongForSafeArithmetic_isReprobed_notTrustedAsFresh() throws Exception {
        Files.createDirectories(home.resolve(".temper/bb-path"));
        Files.writeString(home.resolve(".temper/bb-path/" + HOST), "jump 99999999999999999999\n");
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(calls()).containsExactly("direct probe", "direct cmd");
        assertThat(cached()).isEqualTo("direct");
    }

    @Test
    void cacheEpochFromTheFuture_isReprobed() throws Exception {
        cache("jump", -3600);
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(calls()).containsExactly("direct probe", "direct cmd");
    }

    @Test
    void cachePutFailing_removesItsTmpFile_andWarnsOnce_runStillSucceeds() throws Exception {
        MacScripts.executable(dir.resolve("bin/mv"), "exit 1\n");
        var run = build();

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(cacheDirListing()).isEqualTo("[]");
        assertThat(run.err().split("cannot write the path cache", -1)).hasSize(2);
    }

    @Test
    void cachePut_sweepsTmpFilesOfKilledRuns_butNotAFreshOne() throws Exception {
        var d = Files.createDirectories(home.resolve(".temper/bb-path"));
        var stale = Files.createFile(d.resolve(HOST + ".tmp.4242"));
        var fresh = Files.createFile(d.resolve(HOST + ".tmp.4343"));

        Files.setLastModifiedTime(stale,
                                  FileTime.from(Instant.now().minusSeconds(600)));
        assertThat(build().rc()).isZero();
        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
    }

    @Test
    void unwritableTemperDirectory_cachePutWarnsOnce_withoutMkdirOrMvNoise_andTheRunSucceeds() throws Exception {
        var temper = home.resolve(".temper");

        Files.setPosixFilePermissions(temper, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(temper), "running as a user that ignores directory modes");
            var run = build();

            assertThat(run.rc()).as(run.err()).isZero();
            assertThat(run.err().split("cannot write the path cache", -1)).hasSize(2);
            assertThat(run.err()).doesNotContain("mkdir:", "mv:", "Permission denied");
        } finally {
            Files.setPosixFilePermissions(temper, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // ---- the host pick runs in $(...): a path failure there must fall back, not abort ------------------------------------
    @Test
    void pickWithBothPathsDown_printsTheFallbackLine_thenFailsOnTheRealSetup() throws Exception {
        Files.delete(home.resolve(".temper/bb-bind/t3-x"));
        Files.writeString(home.resolve(".config/bb-temper/hosts"),
                          "key ~/" + TestHosts.KEY
                         + "\nhost cloudbb-3 " + TestHosts.C3
                         + " " + TestHosts.BIGBOY
                         + " ~/" + TestHosts.KNOWN
                         + " auto\n");
        mode("direct", "refuse");
        mode("jump", "refuse");
        var run = run(List.of("checkout", "t3-y", "HEAD"));

        assertThat(run.rc()).isEqualTo(69);
        assertThat(run.err()).contains("host cloudbb-3 (fallback: cannot reach it to read the controller)")
                  .contains("both failed to connect");
    }
}
