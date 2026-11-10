package org.pragmatica.kiln.service.scan;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.service.scan.ScanTransportError.Failed;
import org.pragmatica.kiln.service.scan.ScanTransportError.NoLines;
import org.pragmatica.kiln.service.scan.ScanTransportError.NonZero;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.kiln.service.scan.ScanFixture.script;
import static org.pragmatica.kiln.service.scan.ScanFixture.sha;

/// [LocalScanTransport] against fake scripts: what it hands the script (the request, stdin, the environment) and how
/// it reads the answer (a listing only for rc 0 with a line; a non-zero rc, no line, or a timeout fail the call).
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LocalScanTransportTest {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(30).seconds();

    @TempDir
    Path dir;

    private LocalScanTransport transport(String body, Map<String, String> environment, TimeSpan timeout) throws IOException {
        return LocalScanTransport.localScanTransport(script(dir.resolve("kiln-scan"), body),
                                                     dir.resolve("home"),
                                                     environment,
                                                     timeout);
    }

    private LocalScanTransport echoing() throws IOException {
        return transport("printf '%s\\n' \"$SSH_ORIGINAL_COMMAND\" \"$HOME\" \"${EXTRA-unset}\" \"${USER-unset}\""
                         + " \"${PATH-unset}\"; cat\n",
                         Map.of("EXTRA", "extra", "HOME", "/elsewhere", "SSH_ORIGINAL_COMMAND", "size /x"),
                         TIMEOUT);
    }

    private static Cause failure(Result<List<String>> result) {
        return result.fold(cause -> cause, lines -> fail("expected a failure, got " + lines));
    }

    /// The request is `scan` and the roots, single-spaced; stdin one full-facts path per line; `HOME` and
    /// `SSH_ORIGINAL_COMMAND` are the transport's, never `environment`'s; `environment` adds to `PATH` and `USER`.
    @Test
    void scan_handsTheRequestAndTheFullListToTheScript() throws IOException {
        var lines = echoing().scan(List.of("~/work/co/*", "~/temper-wt/*"),
                                   List.of(CheckoutPath.checkoutPath("/w/a").unwrap(),
                                           CheckoutPath.checkoutPath("/w/b").unwrap()))
                             .await()
                             .unwrap();

        assertThat(lines).containsExactly("scan ~/work/co/* ~/temper-wt/*",
                                          dir.resolve("home").toString(),
                                          "extra",
                                          System.getProperty("user.name"),
                                          System.getenv("PATH"),
                                          "/w/a",
                                          "/w/b");
    }

    @Test
    void scan_withNoFullList_feedsAnEmptyStdin() throws IOException {
        var lines = echoing().scan(List.of("~/a"), List.of()).await().unwrap();

        assertThat(lines).hasSize(5).first().isEqualTo("scan ~/a");
    }

    /// The script sees no variable of this JVM's environment but `PATH` (and `USER`, set from `user.name`), as a
    /// forced command sees only what sshd sets.
    @Test
    void scan_handsTheScriptOnlyItsOwnEnvironment() throws IOException {
        var keys = Set.copyOf(transport("env | sed 's/=.*//'\n", Map.of("EXTRA", "extra"), TIMEOUT).scan(List.of("~/a"),
                                                                                                          List.of())
                                                                                                    .await()
                                                                                                    .unwrap());

        assertThat(keys).contains("PATH", "USER", "HOME", "SSH_ORIGINAL_COMMAND", "EXTRA")
                        .isSubsetOf("PATH", "USER", "HOME", "SSH_ORIGINAL_COMMAND", "EXTRA", "PWD", "SHLVL", "_", "OLDPWD");
    }

    @Test
    void environment_mayReplacePath() throws IOException {
        var lines = transport("printf '%s\\n' \"$PATH\"\n", Map.of("PATH", "/only/here:/usr/bin:/bin"), TIMEOUT).scan(List.of("~/a"),
                                                                                                                       List.of())
                                                                                                                 .await()
                                                                                                                 .unwrap();

        assertThat(lines).containsExactly("/only/here:/usr/bin:/bin");
    }

    /// The SHAs go in order, whatever the set's order, so one set is always one request.
    @Test
    void reach_sendsTheShasInOrder() throws IOException {
        var high = "f".repeat(40);
        var low = "0".repeat(40);
        var middle = "8".repeat(40);

        var lines = echoing().reach(Set.of(sha(high), sha(low), sha(middle))).await().unwrap();

        assertThat(lines.getFirst()).isEqualTo("reach " + low + " " + middle + " " + high);
    }

    @Test
    void size_sendsTheRootAndThePath() throws IOException {
        var lines = echoing().size("~/co/*", CheckoutPath.checkoutPath("/w/co/a").unwrap()).await().unwrap();

        assertThat(lines.getFirst()).isEqualTo("size ~/co/* /w/co/a");
    }

    /// A large stdin and a large stdout at once: fed while drained, so no pipe fills.
    @Test
    void scan_feedsAndDrainsAtOnce() throws IOException {
        var full = IntStream.range(0, 20_000)
                            .mapToObj(index -> CheckoutPath.checkoutPath("/w/co/checkout-" + index).unwrap())
                            .toList();

        var lines = transport("cat\n", Map.of(), TIMEOUT).scan(List.of("~/a"), full).await().unwrap();

        assertThat(lines).isEqualTo(full.stream().map(CheckoutPath::value).toList());
    }

    @Test
    void scan_fails_withTheExitCode_andTheStderrTail_whenTheScriptExitsNonZero() throws IOException {
        var stderr = IntStream.range(0, 1000).mapToObj(index -> "line " + index).collect(Collectors.joining("\n"));
        var transport = transport("echo partial; printf '%s' '" + stderr + "' >&2; exit 64\n", Map.of(), TIMEOUT);

        var cause = failure(transport.scan(List.of("~/a"), List.of()).await());

        assertThat(cause).isInstanceOf(NonZero.class);
        var nonZero = (NonZero) cause;
        assertThat(nonZero.rc()).isEqualTo(64);
        assertThat(nonZero.verb()).isEqualTo("scan");
        assertThat(nonZero.stderrTail()).hasSize(LocalScanTransport.STDERR_TAIL_CHARS)
                                        .endsWith("line 999");
        assertThat(nonZero.message()).isEqualTo("kiln-scan scan exited 64");
    }

    @Test
    void scan_keepsAShortStderrWhole() throws IOException {
        var cause = failure(transport("echo refused >&2; exit 64\n", Map.of(), TIMEOUT).reach(Set.of(sha("a".repeat(40))))
                                                                                       .await());

        assertThat(((NonZero) cause).stderrTail()).isEqualTo("refused\n");
        assertThat(((NonZero) cause).verb()).isEqualTo("reach");
    }

    /// rc 0 and not a line: no listing at all (an empty listing still has its trailer).
    @Test
    void scan_fails_whenTheScriptPrintsNothing() throws IOException {
        var cause = failure(transport("exit 0\n", Map.of(), TIMEOUT).size("/w", CheckoutPath.checkoutPath("/w").unwrap()).await());

        assertThat(cause).isEqualTo(NoLines.FACTORY.apply("size"));
    }

    /// Past the timeout the call fails, and the script is killed (it never writes its marker).
    @Test
    void scan_fails_andKillsTheScript_pastTheTimeout() throws IOException, InterruptedException {
        var marker = dir.resolve("finished");
        var transport = transport("sleep 3; touch '" + marker + "'; echo late\n",
                                  Map.of(),
                                  TimeSpan.timeSpan(500).millis());

        var cause = failure(transport.scan(List.of("~/a"), List.of()).await());

        assertThat(cause).isInstanceOf(Failed.class);
        assertThat(((Failed) cause).verb()).isEqualTo("scan");
        Thread.sleep(4000);
        assertThat(marker).doesNotExist();
    }

    @Test
    void scan_fails_whenTheScriptCannotRun() {
        var transport = LocalScanTransport.localScanTransport(dir.resolve("absent"), dir, Map.of(), TIMEOUT);

        var cause = failure(transport.scan(List.of("~/a"), List.of()).await());

        assertThat(cause).isInstanceOf(NonZero.class);
        assertThat(((NonZero) cause).rc()).isEqualTo(127);
    }

    @Test
    void scan_succeeds_onRcZeroWithALine() throws IOException {
        var lines = transport("echo one; echo two\n", Map.of(), TIMEOUT).scan(List.of("~/a"), List.of()).await().unwrap();

        assertThat(lines).containsExactly("one", "two");
    }

    @Test
    void environment_isCopied() throws IOException {
        var environment = new HashMap<String, String>();
        environment.put("EXTRA", "first");
        var transport = transport("printf '%s\\n' \"$EXTRA\"\n", environment, TIMEOUT);
        environment.put("EXTRA", "second");

        assertThat(transport.scan(List.of("~/a"), List.of()).await().unwrap()).containsExactly("first");
    }
}
