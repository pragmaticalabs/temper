package org.pragmatica.kiln.service.scan;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;

/// Fix round ruling 2: each stream of a `kiln-scan` process is capped; a stream past the cap fails the call (the process
/// killed) whatever its rc, and a stream at the cap is still read. Ruling 1: a request word holding whitespace is refused.
class ScanExecTest {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final int CAP = 16;

    /// Repeated: the kill can fail the exit or the feed too, and the reason must still be the cap.
    @RepeatedTest(10)
    void stdoutPastTheCap_failsTheCall_andTheProcessIsKilled() {
        var started = System.nanoTime();

        assertThat(failure(run("head -c 17 /dev/zero | tr '\\000' x; echo; sleep 30"))).isInstanceOf(ScanTransportError.TooLarge.class);
        assertThat((System.nanoTime() - started) / 1_000_000_000L).as("killed, not waited for").isLessThan(20);
    }

    @Test
    void stderrPastTheCap_failsTheCall() {
        assertThat(failure(run("echo ok; head -c 40 /dev/zero >&2"))).isInstanceOf(ScanTransportError.TooLarge.class);
    }

    @Test
    void streamsAtTheCap_areRead() {
        assertThat(run("printf '%015d\\n' 0; head -c 16 /dev/zero >&2").await(TIMEOUT).unwrap()).containsExactly("0".repeat(15));
    }

    @Test
    void wordsWithWhitespace_areNotCarried() {
        assertThat(ScanExec.carried("size", List.of("/w/*", "/w/a")).isSuccess()).isTrue();
        assertThat(ScanExec.carried("size", List.of("/w/*", "/w/a b")).isFailure()).isTrue();
        assertThat(ScanExec.carried("size", List.of("/w/*", "/w/a\tb")).isFailure()).isTrue();
        assertThat(ScanExec.carried("size", List.of("/w/*", "/w/a$(x);`y`")).isSuccess()).as("no shell reads the request").isTrue();
    }

    private static Promise<List<String>> run(String script) {
        var command = Command.command(List.of("bash", "-c", script), Map.of("PATH", System.getenv("PATH")), TIMEOUT).unwrap();

        return ScanExec.run(ProcessRunner.processRunner(), command, "scan", "", TIMEOUT, CAP);
    }

    private static Cause failure(Promise<List<String>> call) {
        var result = call.await(TIMEOUT);

        assertThat(result.isFailure()).as("%s", result).isTrue();
        return result.fold(cause -> cause, _ -> null);
    }
}
