package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// `ops/mac/bb-submit` and `ops/bb/bb-temper` (M.walk, M.label) against an `ssh` stub first
/// on `PATH` (it logs each argument on a line, ends a call with `--`, and exits with the code in `ssh.rc`). The session
/// ancestor is a copy of `/bin/bash` named `claude` under real `ps`; the no-ancestor cases use a `TEMPER_MAC_PS`
/// fixture whose every process is a plain `bash` under pid 1.
///
/// Probes: refuse when there is no ancestor (exit 3 not tolerated) → [#submit_withNoClaudeAncestor_stillQueuesTheBuild_withoutSessionWords] red;
/// `BB_SESSION` ignored → [#submit_bbSession_isTheLabel] red; a retry loop on ssh → [#submit_isNeverRetried_andReturnsSshsExitCode] red;
/// a malformed value let through → [#submit_aMalformedValue_isRefused_beforeAnyConnection] red.
class BbSubmitIT {
    private record Case(Map<String, String> env, List<String> args) {}

    private static final String TARGET = TestHosts.BIGBOY;

    @TempDir
    Path dir;

    private Path home;
    private Path bin;

    @BeforeEach
    void stubs() throws IOException {
        home = Files.createDirectories(dir.resolve("home"));
        bin = Files.createDirectories(dir.resolve("bin"));
        TestHosts.write(home);
        // ssh.err (optional) goes to stderr; with ssh.exec present the remote command (the last argument) runs locally,
        // with this test's HOME as the remote home, so a row can pin what the remote shell code does.
        MacScripts.executable(bin.resolve("ssh"), "for a in \"$@\"; do printf '%s\\n' \"$a\" >> \"$STUBS/ssh.args\"; done\necho -- >> \"$STUBS/ssh.args\"\n"
                                                   + "cat \"$STUBS/ssh.err\" >&2 2> /dev/null\n"
                                                   + "if [ -f \"$STUBS/ssh.exec\" ]; then for last; do :; done; exec bash -c \"$last\"; fi\n"
                                                   + "exit \"$(cat \"$STUBS/ssh.rc\" 2> /dev/null || echo 0)\"\n");
        Files.writeString(dir.resolve("procs"), "900|1|Tue Nov  3 01:06:38 2026|/bin/bash|bash\n");
        MacScripts.executable(bin.resolve("ps-plain"), "case \"$*\" in\n  *-A*) ;;\n  *ppid=,comm=*) echo '1 bash' ;;\nesac\n");
    }

    private Map<String, String> env(String... more) {
        var env = new LinkedHashMap<>(MacScripts.env(Map.of("HOME", home.toString(), "PATH", bin + ":/usr/bin:/bin", "STUBS", dir.toString(), "BB_OWNER", "t3")));
        for (var i = 0; i < more.length; i += 2) {
            env.put(more[i], more[i + 1]);
        }
        return env;
    }

    private Map<String, String> noAncestor(String... more) {
        var env = env(more);
        env.put("TEMPER_MAC_PS", bin.resolve("ps-plain").toString());
        return env;
    }

    private MacScripts.Result submit(Map<String, String> env, String... args) throws Exception {
        var command = new ArrayList<>(List.of("bash", MacScripts.SUBMIT.toString()));
        command.addAll(List.of(args));
        return MacScripts.run(command, env, dir);
    }

    /// The stub's calls: one list of arguments per call.
    private List<List<String>> sshCalls() throws IOException {
        var file = dir.resolve("ssh.args");
        var calls = new ArrayList<List<String>>();
        var current = new ArrayList<String>();
        for (var line : Files.exists(file) ? Files.readAllLines(file) : List.<String>of()) {
            if (line.equals("--")) {
                calls.add(current);
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
        return calls;
    }

    private String remoteCommand() throws IOException {
        assertThat(sshCalls()).hasSize(1);
        var call = sshCalls().getFirst();
        assertThat(call.subList(0, 7)).containsExactly("-i", home + "/" + TestHosts.KEY, "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", TARGET);
        return call.get(7);
    }

    private MacScripts.Result underClaude(String script, String name, Map<String, String> env) throws Exception {
        return MacScripts.run(List.of(MacScripts.claude(dir.resolve("standin")).toString(), "-c", script, "--name", name), env, dir);
    }

    // ---- bb-submit ---------------------------------------------------------------------------------------------
    /// The session words are the ancestor's (pid, start within a second of the JVM's reading, `--name` label), then the
    /// optional ticket fields in the contract's order, then `BB_OWNER`, `bb-build` and the checkout unquoted (`~`
    /// expands on the host).
    @Test
    void submit_underAClaude_carriesTheSessionWords_andTheOptionalFields() throws Exception {
        var script = "bash " + MacScripts.SUBMIT + " " + TARGET + " '~/work/co/x' -B clean; echo rc=$?";

        var run = underClaude(script, "CTO", env("BB_AGENT", "t3wpm", "BB_LANE", "wp-m", "BB_PR", "70"));

        assertThat(run.out()).as(run.err()).isEqualTo("rc=0\n");
        var command = remoteCommand();
        var matcher = java.util.regex.Pattern.compile("^BB_SESSION_PID=(\\d+) BB_SESSION_START=(\\d+) BB_SESSION_LABEL=CTO BB_AGENT=t3wpm BB_LANE=wp-m BB_PR=70 BB_OWNER=t3 ~/tools/bb-build ~/work/co/x -B clean$")
                                      .matcher(command);
        assertThat(matcher.matches()).as(command).isTrue();
        assertThat(Long.parseLong(matcher.group(1))).isEqualTo(run.pid());
        assertThat(Long.parseLong(matcher.group(2))).isCloseTo(run.started().getEpochSecond(), within(1L));
    }

    /// M.label: `BB_SESSION` is the ticket's label.
    @Test
    void submit_bbSession_isTheLabel() throws Exception {
        var script = "bash " + MacScripts.SUBMIT + " " + TARGET + " work/co/x; echo rc=$?";

        var run = underClaude(script, "CTO", env("BB_SESSION", "lead1"));

        assertThat(run.out()).as(run.err()).isEqualTo("rc=0\n");
        assertThat(remoteCommand()).matches("^BB_SESSION_PID=\\d+ BB_SESSION_START=\\d+ BB_SESSION_LABEL=lead1 BB_OWNER=t3 ~/tools/bb-build work/co/x$");
    }

    /// M.walk: with no `claude` ancestor `temper-mac session` exits 3 and the build is queued anyway, with no session
    /// words (the owner's own terminal builds keep working).
    @Test
    void submit_withNoClaudeAncestor_stillQueuesTheBuild_withoutSessionWords() throws Exception {
        var run = submit(noAncestor(), TARGET, "~/work/co/x", "-B", "clean");

        assertThat(run.rc()).isZero();
        assertThat(run.err()).contains("temper-mac: no claude ancestor");
        assertThat(remoteCommand()).isEqualTo("BB_OWNER=t3 ~/tools/bb-build ~/work/co/x -B clean");
    }

    /// The ssh is never retried (a drop after the enqueue would enqueue twice) and its exit code is the result.
    @Test
    void submit_isNeverRetried_andReturnsSshsExitCode() throws Exception {
        Files.writeString(dir.resolve("ssh.rc"), "255\n");

        var run = submit(noAncestor(), TARGET, "work/co/x");

        assertThat(run.rc()).isEqualTo(255);
        assertThat(sshCalls()).hasSize(1);
    }

    /// Every value is checked before it reaches the remote command line: a malformed one is exit 2 with no connection.
    @Test
    void submit_aMalformedValue_isRefused_beforeAnyConnection() throws Exception {
        var cases = List.<Case>of(new Case(Map.of("BB_AGENT", "a b"), List.of(TARGET, "work/co/x")),
                            new Case(Map.of("BB_AGENT", "a;b"), List.of(TARGET, "work/co/x")),
                            new Case(Map.of("BB_LANE", "x$(id)"), List.of(TARGET, "work/co/x")),
                            new Case(Map.of("BB_PR", "0"), List.of(TARGET, "work/co/x")),
                            new Case(Map.of("BB_PR", "12a"), List.of(TARGET, "work/co/x")),
                            new Case(Map.of("BB_OWNER", "a b"), List.of(TARGET, "work/co/x")),
                            new Case(Map.of("BB_OWNER", ""), List.of(TARGET, "work/co/x")),
                            new Case(Map.<String, String>of(), List.of("host", "work/co/x")),
                            new Case(Map.<String, String>of(), List.of("-x@host", "work/co/x")),
                            new Case(Map.<String, String>of(), List.of(TARGET, "x;touch pwned")),
                            new Case(Map.<String, String>of(), List.of(TARGET, "$(id)")),
                            new Case(Map.<String, String>of(), List.of(TARGET, "a b")),
                            new Case(Map.<String, String>of(), List.of(TARGET)),
                            new Case(Map.<String, String>of(), List.<String>of()));

        for (var testCase : cases) {
            var env = noAncestor();
            env.putAll(testCase.env());
            var run = submit(env, testCase.args().toArray(String[]::new));

            assertThat(run.rc()).as(testCase.toString()).isEqualTo(2);
            assertThat(run.err()).as(testCase.toString()).startsWith("bb-submit: ");
        }
        assertThat(sshCalls()).isEmpty();
        assertThat(dir.resolve("pwned")).doesNotExist();
    }

    /// The session line from `temper-mac session` is checked against `SESSION_RE` before it reaches the remote command:
    /// a malformed one is exit 2 with no connection. Mutation: drop the check → red.
    @Test
    void submit_aMalformedSessionLine_isRefused_beforeAnyConnection() throws Exception {
        var scripts = Files.createDirectories(dir.resolve("copy"));
        Files.copy(MacScripts.SUBMIT, scripts.resolve("bb-submit"));
        MacScripts.executable(scripts.resolve("temper-mac"), "echo 'BB_SESSION_PID=1 BB_SESSION_START=1793667998 BB_SESSION_LABEL=x; touch pwned'\n");

        var run = MacScripts.run(List.of("bash", scripts.resolve("bb-submit").toString(), TARGET, "work/co/x"), env(), dir);

        assertThat(run.rc()).isEqualTo(2);
        assertThat(run.err()).contains("bb-submit: temper-mac session printed an unexpected line");
        assertThat(sshCalls()).isEmpty();
        assertThat(dir.resolve("pwned")).doesNotExist();
    }

    @Test
    void submit_withoutBbOwner_isRefused() throws Exception {
        var env = noAncestor();
        env.remove("BB_OWNER");

        var run = submit(env, TARGET, "work/co/x");

        assertThat(run.rc()).isEqualTo(2);
        assertThat(run.err()).contains("set BB_OWNER=");
        assertThat(sshCalls()).isEmpty();
    }

    /// Nothing shell-active reaches the remote shell: every mvn argument is `printf %q`-quoted, and the local shell runs none.
    @Test
    void submit_mvnArguments_areQuoted() throws Exception {
        var run = submit(noAncestor(), TARGET, "work/co/x", "-B", "clean install", "-pl", "a;b", "$(touch " + dir.resolve("pwned") + ")", "it's");

        assertThat(run.rc()).isZero();
        assertThat(remoteCommand()).isEqualTo("BB_OWNER=t3 ~/tools/bb-build work/co/x -B clean\\ install -pl a\\;b \\$\\(touch\\ " + dir.resolve("pwned")
                                              + "\\) it\\'s");
        assertThat(dir.resolve("pwned")).doesNotExist();
    }

    /// `--detached --reader <label>` rides before the checkout, only as the first three arguments.
    @Test
    void submit_detachedReader_isPlacedBeforeTheCheckout() throws Exception {
        var run = submit(noAncestor(), "--detached", "--reader", "lead1", TARGET, "~/work/co/x", "-B");

        assertThat(run.rc()).isZero();
        assertThat(remoteCommand()).isEqualTo("BB_OWNER=t3 ~/tools/bb-build --detached --reader lead1 ~/work/co/x -B");
    }

    @Test
    void submit_aBrokenDetachedForm_isRefused() throws Exception {
        for (var args : List.of(List.of("--detached", TARGET, "work/co/x"),
                                List.of("--detached", "--reader"),
                                List.of("--detached", "--reader", "a b", TARGET, "work/co/x"),
                                List.of("--detached", "--reader", "x;y", TARGET, "work/co/x"))) {
            var run = submit(noAncestor(), args.toArray(String[]::new));

            assertThat(run.rc()).as(args.toString()).isEqualTo(2);
        }
        assertThat(sshCalls()).isEmpty();
    }

    // ---- bb-temper ---------------------------------------------------------------------------------------------
    private MacScripts.Result bbTemper(Map<String, String> env, String... args) throws Exception {
        var command = new ArrayList<>(List.of("bash", MacScripts.BB_TEMPER.toString()));
        command.addAll(List.of(args));
        var withHost = new LinkedHashMap<>(env);
        withHost.putIfAbsent("BB_HOST", "bigboy");  // an explicit host: these rows pin the commands, not the automatic pick (BbTemperHostIT)
        return MacScripts.run(command, withHost, dir);
    }

    /// `bb-temper build` hands the host, the checkout and the maven arguments to bb-submit.
    @Test
    void bbTemper_build_goesThroughBbSubmit() throws Exception {
        var fake = MacScripts.executable(bin.resolve("fake-submit"), "printf '%s\\n' \"$@\" > \"$STUBS/submit.args\"\n");

        var run = bbTemper(env("BB_SUBMIT", fake.toString()), "build", "t3-x", "-B", "clean", "install");
        var defaults = bbTemper(env("BB_SUBMIT", fake.toString(), "BB_HOST", "cloudbb-3"), "build", "t3-x");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(defaults.rc()).as(defaults.err()).isZero();
        assertThat(Files.readAllLines(dir.resolve("submit.args"))).containsExactly(TestHosts.C3, "~/work/co/t3-x", "-B", "clean", "install");
        assertThat(sshCalls()).isEmpty();
    }

    /// Before the install: a warning, and the old line unchanged (the ticket has no session fields).
    @Test
    void bbTemper_withoutBbSubmit_warns_andRunsTheOldLine() throws Exception {
        var run = bbTemper(env("BB_SUBMIT", dir.resolve("absent").toString()), "build", "t3-x", "-B", "clean install");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("bb-temper: bb-submit not installed; ticket without session fields");
        assertThat(sshCalls()).singleElement().satisfies(call -> {
            assertThat(call.subList(0, 7)).containsExactly("-i", home + "/" + TestHosts.KEY, "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", TARGET);
            assertThat(call.get(7)).isEqualTo("BB_OWNER=t3 ~/tools/bb-build ~/work/co/t3-x -B clean\\ install ");
        });
    }

    /// The whole Mac chain: a session runs bb-temper, which hands over to bb-submit, which asks temper-mac for the
    /// session: the remote command carries that session and the ticket fields from the environment.
    @Test
    void bbTemper_underAClaude_putsTheSessionIntoTheBuildCommand() throws Exception {
        var script = "bash " + MacScripts.BB_TEMPER + " build t3-x -B; echo rc=$?";

        var run = underClaude(script, "CTO", env("BB_SUBMIT", MacScripts.SUBMIT.toString(), "BB_AGENT", "t3wpm", "BB_HOST", "bigboy"));

        assertThat(run.out()).as(run.err()).isEqualTo("rc=0\n");
        assertThat(remoteCommand()).matches("^BB_SESSION_PID=" + run.pid() + " BB_SESSION_START=\\d+ BB_SESSION_LABEL=CTO BB_AGENT=t3wpm BB_OWNER=t3 ~/tools/bb-build ~/work/co/t3-x -B$");
    }

    @Test
    void bbTemper_withAMalformedField_isRefusedByBbSubmit() throws Exception {
        var run = bbTemper(env("BB_SUBMIT", MacScripts.SUBMIT.toString(), "BB_AGENT", "a b"), "build", "t3-x", "-B");

        assertThat(run.rc()).isEqualTo(2);
        assertThat(sshCalls()).isEmpty();
    }

    // ---- cloud hosts: through the jump host, host keys pinned ---------------------------------------------------
    private static final String PROXY = TestHosts.BIGBOY;

    private Path knownHosts() throws IOException {
        return Files.writeString(dir.resolve("cloud_known_hosts"), "x ssh-ed25519 AAAA\n");
    }

    private String fakeSubmitEnvLine(Map<String, String> env, String... args) throws Exception {
        var fake = MacScripts.executable(bin.resolve("fake-submit"), "printf '%s|%s|%s\\n' \"$1\" \"${BB_SSH_PROXY-unset}\" \"${BB_KNOWN_HOSTS-unset}\" > \"$STUBS/submit.env\"\n");
        var all = new LinkedHashMap<>(env);
        all.put("BB_SUBMIT", fake.toString());
        var run = bbTemper(all, args);
        assertThat(run.rc()).as(run.err()).isZero();
        return Files.readString(dir.resolve("submit.env")).strip();
    }

    @Test
    void bbTemper_cloudHost_givesBbSubmitTheNewAddress_theProxy_andTheKnownHosts() throws Exception {
        var line = fakeSubmitEnvLine(env("BB_HOST", "cloudbb-3"), "build", "t3-x");

        assertThat(line).isEqualTo(TestHosts.C3 + "|" + PROXY + "|" + home + "/" + TestHosts.KNOWN);
        assertThat(fakeSubmitEnvLine(env("BB_HOST", "cloudbb-d1"), "build", "t3-x")).startsWith(TestHosts.D1 + "|" + PROXY + "|");
    }

    @Test
    void bbTemper_bigboy_hasNoProxy_evenWhenTheEnvironmentCarriesOne() throws Exception {
        var line = fakeSubmitEnvLine(env("BB_SSH_PROXY", PROXY, "BB_KNOWN_HOSTS", "/etc/passwd"), "build", "t3-x");

        assertThat(line).isEqualTo(TARGET + "|unset|unset");
    }

    @Test
    void submit_withProxyAndKnownHosts_sshJumpsAndPinsTheHostKey() throws Exception {
        var known = knownHosts();

        var run = submit(noAncestor("BB_SSH_PROXY", PROXY, "BB_KNOWN_HOSTS", known.toString()), "builder@203.0.113.7", "work/co/x");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(sshCalls()).singleElement().satisfies(call -> {
            assertThat(call).containsSubsequence("-i", home + "/" + TestHosts.KEY, "-o", "BatchMode=yes");
            assertThat(call).contains("UserKnownHostsFile=" + known, "StrictHostKeyChecking=yes",
                                      "ProxyCommand=ssh -i " + home + "/" + TestHosts.KEY + " -o BatchMode=yes -o ConnectTimeout=15 -W %h:%p " + PROXY,
                                      "ConnectTimeout=15", "builder@203.0.113.7");
            assertThat(call.get(call.size() - 2)).isEqualTo("builder@203.0.113.7");
        });
    }

    @Test
    void submit_withoutProxy_hasNoProxyAndNoStrictHostKeyOption() throws Exception {
        var run = submit(noAncestor(), TARGET, "work/co/x");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(String.join("\n", sshCalls().getFirst())).doesNotContain("ProxyCommand").doesNotContain("StrictHostKeyChecking").doesNotContain("UserKnownHostsFile");
    }

    @Test
    void submit_aMalformedProxyOrKnownHosts_isRefused_beforeAnyConnection() throws Exception {
        var known = knownHosts();
        var cases = List.of(List.of("BB_SSH_PROXY", TestHosts.BIGBOY + ";id", "BB_KNOWN_HOSTS", known.toString()),
                            List.of("BB_SSH_PROXY", "192.0.2.10", "BB_KNOWN_HOSTS", known.toString()),
                            List.of("BB_SSH_PROXY", "-oProxyCommand=x", "BB_KNOWN_HOSTS", known.toString()),
                            List.of("BB_SSH_PROXY", PROXY, "BB_KNOWN_HOSTS", dir.resolve("absent").toString()),
                            List.of("BB_SSH_PROXY", PROXY, "BB_KNOWN_HOSTS", dir.toString()),
                            List.of("BB_SSH_PROXY", PROXY),
                            List.of("BB_KNOWN_HOSTS", known.toString()));
        for (var c : cases) {
            var run = submit(noAncestor(c.toArray(String[]::new)), TARGET, "work/co/x");

            assertThat(run.rc()).as(c.toString()).isEqualTo(2);
        }
        assertThat(sshCalls()).isEmpty();
    }

    /// bb-build's own switches reach it. Before 2026-11-06 bb-temper forwarded only BB_OWNER, so BB_DEDUP=0 was dropped
    /// and a repeat build was deduped onto the earlier result while reading as a fresh run (WP-4a evidence).
    @Test
    void bbTemper_withoutBbSubmit_forwardsBbDedupAndBbQueue() throws Exception {
        var run = bbTemper(env("BB_SUBMIT", dir.resolve("absent").toString(), "BB_DEDUP", "0", "BB_QUEUE", "1"), "build", "t3-x", "-B");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(sshCalls()).singleElement().satisfies(call -> assertThat(call.get(7)).isEqualTo("BB_OWNER=t3 BB_DEDUP=0 BB_QUEUE=1 ~/tools/bb-build ~/work/co/t3-x -B "));
    }

    @Test
    void submit_forwardsBbDedupAndBbQueue() throws Exception {
        var run = submit(noAncestor("BB_DEDUP", "0", "BB_QUEUE", "0"), TARGET, "~/work/co/x", "-B");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(remoteCommand()).isEqualTo("BB_OWNER=t3 BB_DEDUP=0 BB_QUEUE=0 ~/tools/bb-build ~/work/co/x -B");
    }

    @Test
    void aMalformedBbDedupOrBbQueue_isRefused_beforeAnyConnection() throws Exception {
        for (var c : List.of(List.of("BB_DEDUP", "no"), List.of("BB_QUEUE", "0;id"), List.of("BB_DEDUP", "00"))) {
            var viaTemper = bbTemper(env(c.get(0), c.get(1), "BB_SUBMIT", dir.resolve("absent").toString()), "build", "t3-x");
            var viaSubmit = submit(noAncestor(c.get(0), c.get(1)), TARGET, "work/co/x");

            assertThat(viaTemper.rc()).as(c.toString()).isEqualTo(2);
            assertThat(viaSubmit.rc()).as(c.toString()).isEqualTo(2);
        }
        assertThat(sshCalls()).isEmpty();
    }

    /// BB_PG_ISOLATE=0 reaches bb-build on every path (#27: both tools dropped it, so a group ticket kept iso=1 and nothing said so).
    /// Mutation: drop the forward in bb-submit, or in either bb-temper path, and the matching row goes red.
    @Test
    void submit_forwardsBbPgIsolate() throws Exception {
        var run = submit(noAncestor("BB_PG_ISOLATE", "0"), TARGET, "~/work/co/x", "-B");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(remoteCommand()).isEqualTo("BB_OWNER=t3 BB_PG_ISOLATE=0 ~/tools/bb-build ~/work/co/x -B");
    }

    @Test
    void bbTemper_withoutBbSubmit_forwardsBbPgIsolate() throws Exception {
        var run = bbTemper(env("BB_SUBMIT",
                               dir.resolve("absent").toString(),
                               "BB_PG_ISOLATE",
                               "0"),
                           "build",
                           "t3-x",
                           "-B");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(sshCalls()).singleElement()
                  .satisfies(call -> assertThat(call.get(7)).isEqualTo("BB_OWNER=t3 BB_PG_ISOLATE=0 ~/tools/bb-build ~/work/co/t3-x -B "));
    }

    @Test
    void bbTemper_throughBbSubmit_forwardsBbPgIsolate() throws Exception {
        var run = bbTemper(noAncestor("BB_SUBMIT", MacScripts.SUBMIT.toString(), "BB_PG_ISOLATE", "0"),
                           "build",
                           "t3-x",
                           "-B");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(sshCalls()).singleElement()
                  .satisfies(call -> assertThat(call.getLast()).isEqualTo("BB_OWNER=t3 BB_PG_ISOLATE=0 ~/tools/bb-build ~/work/co/t3-x -B"));
    }

    @Test
    void aMalformedBbPgIsolate_isRefused_beforeAnyConnection() throws Exception {
        for (var value : List.of("no", "2", "00", "0;id")) {
            var viaTemper = bbTemper(env("BB_PG_ISOLATE",
                                         value,
                                         "BB_SUBMIT",
                                         dir.resolve("absent").toString()),
                                     "build",
                                     "t3-x");
            var viaSubmit = submit(noAncestor("BB_PG_ISOLATE", value), TARGET, "work/co/x");

            assertThat(viaTemper.rc()).as(value).isEqualTo(2);
            assertThat(viaSubmit.rc()).as(value).isEqualTo(2);
        }

        assertThat(sshCalls()).isEmpty();
    }

    /// A BB_* variable that neither tool forwards or reads is named on stderr (one line each) and the build is queued all the
    /// same; through bb-temper and bb-submit together it is named once. Mutation: drop the warning in either tool.
    @Test
    void anUnforwardedBbVariable_isNamed_andNeverBlocksTheBuild() throws Exception {
        var viaSubmit = submit(noAncestor("BB_FOO", "1"), TARGET, "~/work/co/x");
        var fallback = bbTemper(env("BB_SUBMIT",
                                    dir.resolve("absent").toString(),
                                    "BB_FOO",
                                    "1"),
                                "build",
                                "t3-x");
        var together = bbTemper(noAncestor("BB_SUBMIT", MacScripts.SUBMIT.toString(), "BB_FOO", "1"), "build", "t3-x");

        assertThat(viaSubmit.rc()).as(viaSubmit.err()).isZero();
        assertThat(viaSubmit.err()).contains("bb-submit: warning: BB_FOO is set but is not forwarded");
        assertThat(fallback.rc()).as(fallback.err()).isZero();
        assertThat(fallback.err()).contains("bb-temper: warning: BB_FOO is set but is not forwarded");
        assertThat(together.rc()).as(together.err()).isZero();
        assertThat(together.err()).containsOnlyOnce("BB_FOO is set but is not forwarded");
        assertThat(sshCalls()).hasSize(3);
    }

    /// Everything either tool forwards or reads is quiet: no false positive in normal use.
    @Test
    void theVariablesTheToolsKnow_areNeverWarnedAbout() throws Exception {
        var known = new LinkedHashMap<String, String>();
        known.put("BB_AGENT", "a");
        known.put("BB_LANE", "l");
        known.put("BB_PR", "7");
        known.put("BB_DEDUP", "0");
        known.put("BB_QUEUE", "1");
        known.put("BB_PG_ISOLATE", "0");
        known.put("BB_SESSION", "lead1");
        known.put("BB_SESSION_PID", "5");
        known.put("BB_TEMPER_HOSTS", home.resolve(".config/bb-temper/hosts").toString());
        known.put("BB_SSH_KEY", home + "/" + TestHosts.KEY);

        var viaSubmit = submit(withAll(noAncestor(), known), TARGET, "~/work/co/x");
        var viaTemper = bbTemper(withAll(noAncestor(), known, Map.of("BB_SUBMIT", MacScripts.SUBMIT.toString())), "build", "t3-x");
        var fallback = bbTemper(withAll(env(), known, Map.of("BB_SUBMIT", dir.resolve("absent").toString(), "BB_HOSTS_CMD", "exit 1")), "build", "t3-x");

        assertThat(viaSubmit.rc()).as(viaSubmit.err()).isZero();
        assertThat(viaTemper.rc()).as(viaTemper.err()).isZero();
        assertThat(fallback.rc()).as(fallback.err()).isZero();
        assertThat(viaSubmit.err()).doesNotContain("warning");
        assertThat(viaTemper.err()).doesNotContain("warning");
        assertThat(fallback.err()).doesNotContain("warning");
    }

    @SafeVarargs
    private static Map<String, String> withAll(Map<String, String> base, Map<String, String>... more) {
        var all = new LinkedHashMap<>(base);
        for (var m : more) {
            all.putAll(m);
        }
        return all;
    }

    /// bb-build exit 7: an identical build is running elsewhere and nothing was queued here. The caller is told on
    /// stdout which ticket it was deduped onto, the exit code stays 7, and the checkout is marked. The remote side runs
    /// for real (ssh.exec) against a stand-in `~/tools/bb-build`, so the mark is written and read back.
    @Test
    void bbTemper_build_dedupedOntoARunningTicket_saysSo_andMarksTheCheckout() throws Exception {
        Files.createDirectories(home.resolve("work/co/t3-x"));
        MacScripts.executable(Files.createDirectories(home.resolve("tools")).resolve("bb-build"),
                              "echo 'dedup: identical build is lead-x: ticket=123-45 owner=t3' >&2\n"
                              + "echo 'dedup: read its result at /home/builder/work/co/lead-x/.bb (poll for rc); BB_DEDUP=0 to run anyway' >&2\n"
                              + "exit 7\n");
        Files.writeString(dir.resolve("ssh.exec"), "");

        var run = bbTemper(env("BB_SUBMIT", dir.resolve("absent").toString()), "build", "t3-x");

        assertThat(run.rc()).isEqualTo(7);
        assertThat(run.out()).contains("bb-temper: deduped onto ticket 123-45: NO new run in t3-x");
        assertThat(run.err()).doesNotContain("could NOT mark");
        assertThat(Files.readString(home.resolve("work/co/t3-x/.bb/deduped"))).isEqualTo("deduped onto ticket 123-45\n");
    }

    /// The mark cannot be written (the ssh fails): the caller is told so, and the exit code is still bb-build's 7,
    /// never 0. Mutation: drop the unmarked warning.
    @Test
    void bbTemper_build_dedupedButUnmarked_saysSo_andKeepsTheExitCode() throws Exception {
        Files.writeString(dir.resolve("ssh.rc"), "7");
        Files.writeString(dir.resolve("ssh.err"), "dedup: identical build is lead-x: ticket=123-45 owner=t3\n");

        var run = bbTemper(env("BB_SUBMIT", dir.resolve("absent").toString()), "build", "t3-x");

        assertThat(run.rc()).isEqualTo(7);
        assertThat(run.out()).contains("bb-temper: deduped onto ticket 123-45: NO new run in t3-x");
        assertThat(run.err()).contains("bb-temper: could NOT mark t3-x as deduped");
        assertThat(sshCalls()).hasSize(2);
        assertThat(sshCalls().get(1).getLast()).contains("> ~/work/co/t3-x/.bb/deduped").contains("deduped\\ onto\\ ticket\\ 123-45");
    }

    /// build buffers bb-build's stderr in a temp file and removes it on every exit path (the trap), here a dedup
    /// (exit 7) and a normal enqueue (exit 0). Mutation: drop the trap.
    @Test
    void bbTemper_build_leavesNoTempFile() throws Exception {
        var tmp = Files.createDirectories(dir.resolve("tmp"));
        Files.writeString(dir.resolve("ssh.rc"), "7");
        Files.writeString(dir.resolve("ssh.err"), "dedup: identical build is lead-x: ticket=123-45 owner=t3\n");

        var deduped = bbTemper(env("BB_SUBMIT", dir.resolve("absent").toString(), "TMPDIR", tmp.toString()), "build", "t3-x");
        Files.delete(dir.resolve("ssh.rc"));
        Files.delete(dir.resolve("ssh.err"));
        var queued = bbTemper(env("BB_SUBMIT", dir.resolve("absent").toString(), "TMPDIR", tmp.toString()), "build", "t3-x");

        assertThat(deduped.rc()).isEqualTo(7);
        assertThat(queued.rc()).as(queued.err()).isZero();
        try (var left = Files.list(tmp)) {
            assertThat(left.toList()).isEmpty();
        }
    }

    /// wait cannot read the mark (no checkout there): it refuses instead of polling an unknown state.
    @Test
    void wait_whenTheMarkCannotBeRead_refusesInsteadOfPolling() throws Exception {
        Files.writeString(dir.resolve("ssh.exec"), "");

        var waited = bbTemper(env(), "wait", "t3-x");

        assertThat(waited.rc()).isEqualTo(1);
        assertThat(waited.err()).contains("cannot read the dedup mark of t3-x");
    }

    /// report on a checkout still queued (no .bb/start yet) prints `rc=running`, not a shell error.
    @Test
    void report_onAQueuedCheckout_saysRunning() throws Exception {
        var bb = Files.createDirectories(home.resolve("work/co/t3-x/.bb"));
        Files.writeString(bb.resolve("build.log"), "bb-build: queued ticket 1-2\n");
        Files.writeString(bb.resolve("queued"), "1-2\n");
        Files.writeString(dir.resolve("ssh.exec"), "");

        var report = bbTemper(env(), "report", "t3-x");

        assertThat(report.rc()).as(report.err()).isZero();
        assertThat(report.out()).startsWith("rc=running wall=");
        assertThat(report.err()).doesNotContain("syntax error");
    }

    /// bb-build exit 8 through bb-submit: the finished build's checkout is named (bb-build prints no ticket for it).
    @Test
    void bbTemper_build_dedupedOntoAFinishedBuild_throughBbSubmit_namesItsCheckout() throws Exception {
        var fake = MacScripts.executable(bin.resolve("fake-submit"),
                                         "echo 'dedup: identical build already finished: rc=0 ended=x owner=lead' >&2\n"
                                         + "echo 'dedup: read its result at /home/builder/work/co/lead-y/.bb (bb-report /home/builder/work/co/lead-y); BB_DEDUP=0 to run anyway' >&2\n"
                                         + "exit 8\n");

        var run = bbTemper(env("BB_SUBMIT", fake.toString()), "build", "t3-x");

        assertThat(run.rc()).isEqualTo(8);
        assertThat(run.out()).contains("bb-temper: deduped onto checkout /home/builder/work/co/lead-y: NO new run in t3-x");
        assertThat(sshCalls()).singleElement().satisfies(call -> assertThat(call.getLast()).contains(".bb/deduped"));
    }

    /// A dedup mark newer than the build log: report and wait show it and exit 7, never an earlier rc as this run's.
    @Test
    void report_andWait_afterADedup_refuseToShowAResult() throws Exception {
        var bb = checkoutWithRc();
        Files.writeString(bb.resolve("deduped"), "deduped onto ticket 123-45\n");
        Files.setLastModifiedTime(bb.resolve("build.log"), java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
        Files.writeString(dir.resolve("ssh.exec"), "");

        var report = bbTemper(env(), "report", "t3-x");
        var waited = bbTemper(env(), "wait", "t3-x");

        assertThat(report.rc()).as(report.err()).isEqualTo(7);
        assertThat(report.out()).startsWith("rc=deduped onto ticket 123-45\n").doesNotContain("rc=0");
        assertThat(waited.rc()).as(waited.err()).isEqualTo(7);
        assertThat(waited.out()).isEqualTo("rc=deduped onto ticket 123-45\n");
    }

    /// A later enqueue rewrites the build log, which makes the mark older: report shows the run's own rc again.
    @Test
    void report_afterALaterEnqueue_showsTheRunAgain() throws Exception {
        var bb = checkoutWithRc();
        Files.writeString(bb.resolve("deduped"), "deduped onto ticket 123-45\n");
        Files.setLastModifiedTime(bb.resolve("deduped"), java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
        Files.writeString(dir.resolve("ssh.exec"), "");

        var report = bbTemper(env(), "report", "t3-x");

        assertThat(report.rc()).as(report.err()).isZero();
        assertThat(report.out()).startsWith("rc=0 ").doesNotContain("deduped");
    }

    /// `home/work/co/t3-x/.bb` with a finished run (rc 0) and its build log.
    private Path checkoutWithRc() throws IOException {
        var bb = Files.createDirectories(home.resolve("work/co/t3-x/.bb"));
        Files.writeString(bb.resolve("rc"), "0\n");
        Files.writeString(bb.resolve("start"), "1000\n");
        Files.writeString(bb.resolve("end"), "1100\n");
        Files.writeString(bb.resolve("run.marker"), "");
        Files.writeString(bb.resolve("build.log"), "BUILD SUCCESS\n");
        return bb;
    }
}
