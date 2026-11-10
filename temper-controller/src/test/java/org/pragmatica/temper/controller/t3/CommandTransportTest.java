package org.pragmatica.temper.controller.t3;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// How a run of a transport's command ends: the `last_error` it makes of each way to fail.
class CommandTransportTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(20).seconds();

    private static CommandTransport bash(String script, boolean ssh, TimeSpan timeout) {
        return CommandTransport.commandTransport(ProcessRunner.processRunner(),
                                                 _ -> Command.command(List.of("bash", "-c", script),
                                                                      Map.of(),
                                                                      timeout)
                                                             .map(Command::inheritingEnvironment),
                                                 ssh);
    }

    private static String error(CommandTransport transport) {
        var result = transport.read("h").await(WAIT);

        assertThat(result.isFailure()).isTrue();

        return result.fold(cause -> cause.message(), _ -> "");
    }

    @Test
    void exitZero_isItsStdout() {
        assertThat(bash("printf 'a\\nb\\n'", false, WAIT).read("h").await(WAIT).unwrap()).isEqualTo("a\nb\n");
    }

    @Test
    void anExit_isExitN_andOnlySshsOwn255_isSshExitWithTheFirstStderrLine() {
        assertThat(error(bash("exit 69", false, WAIT))).isEqualTo("exit 69");
        assertThat(error(bash("exit 69", true, WAIT))).as("a remote exit through ssh").isEqualTo("exit 69");
        assertThat(error(bash("echo 'ssh: connect to host x port 22: Connection refused' >&2; echo more >&2; exit 255",
                              true,
                              WAIT))).isEqualTo("ssh exit 255: ssh: connect to host x port 22: Connection refused");
        assertThat(error(bash("exit 255", false, WAIT))).as("not an ssh command").isEqualTo("exit 255");
    }

    @Test
    void moreThanTheStdoutCap_isOversize() {
        assertThat(error(bash("head -c 9000000 /dev/zero | tr '\\0' 'a'", false, WAIT))).isEqualTo("oversize");
    }

    @Test
    void noAnswerWithinTheBound_isTimeout() {
        assertThat(error(bash("sleep 30",
                              false,
                              TimeSpan.timeSpan(1).seconds()))).isEqualTo("timeout");
    }

    @Test
    void aCommandThatCannotStart_isExit127() {
        var transport = CommandTransport.commandTransport(ProcessRunner.processRunner(),
                                                          _ -> Command.command(List.of("/nonexistent/ssh-x"),
                                                                               Map.of(),
                                                                               WAIT),
                                                          true);

        assertThat(error(transport)).startsWith("exit 127: ");
    }
}
