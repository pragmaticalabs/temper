package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


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
        MacScripts.executable(bin.resolve("ssh"),
                              """
                path=direct; for a in "$@"; do case $a in ProxyCommand=*) path=jump ;; esac; done
                cmd=${@: -1}; kind=cmd; [ "$cmd" != true ] || kind=probe
                echo "$path $kind" >> "$STUBS/calls.log"
                echo "$path $*" >> "$STUBS/args.log"
                case $(cat "$STUBS/$path.mode" 2> /dev/null || echo ok) in
                  refuse) exit 255 ;;
                  hang) sleep 30; exit 255 ;;
                esac
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

        return MacScripts.run(List.of("bash", MacScripts.BB_TEMPER.toString(), "build", "t3-x", "-B"),
                              env,
                              dir);
    }

    private List<String> calls() throws IOException {
        var file = dir.resolve("calls.log");

        return Files.exists(file)
               ? Files.readAllLines(file)
               : List.of();
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
}
