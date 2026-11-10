package org.pragmatica.temper.worker.runner;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.refusal.ContainerEnv;

import static org.assertj.core.api.Assertions.assertThat;

/// The in-image step launcher (`runner/RunSteps.java`, m0-design §7.4), run here with the JDK source launcher
/// exactly as shipped: no shell, count-prefixed steps, the R-ENV allow-list re-applied, first non-zero rc wins.
class RunStepsTest {
    record Run(int rc, String output) {}

    private static Run run(Map<String, String> environment, String... args) throws IOException, InterruptedException, URISyntaxException {
        var source = Path.of(RunStepsTest.class.getResource("/runner/RunSteps.java")
                                               .toURI());
        var argv = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                                                     .toString(),
                                                 source.toString()));

        argv.addAll(Arrays.asList(args));

        var builder = HermeticProcess.builder(argv).redirectErrorStream(true);

        builder.environment()
               .clear();
        builder.environment()
               .putAll(environment);

        var process = builder.start();
        var output = new String(process.getInputStream()
                                       .readAllBytes(), StandardCharsets.UTF_8);

        return new Run(process.waitFor(), output);
    }

    private static Map<String, String> hostileEnvironment() {
        var environment = new HashMap<String, String>();

        ContainerEnv.ALLOWED.forEach(key -> environment.put(key, "allowed-" + key));
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("HCLOUD_TOKEN", "secret");
        environment.put("GITHUB_TOKEN", "secret");
        environment.put("LANG", "en_US.UTF-8");
        environment.put("JAVA_VERSION", "jdk-25");
        return environment;
    }

    @Test
    void runSteps_passesExactlyTheAllowList() throws Exception {
        var result = run(hostileEnvironment(), "1", "/usr/bin/env");
        Set<String> keys = result.output()
                                 .lines()
                                 .filter(line -> line.contains("=") && !line.startsWith("[temper]"))
                                 .map(line -> line.substring(0, line.indexOf('=')))
                                 .collect(Collectors.toSet());

        assertThat(result.rc()).isZero();
        assertThat(keys).isEqualTo(ContainerEnv.ALLOWED);
        assertThat(result.output()).doesNotContain("secret", "HCLOUD_TOKEN", "LANG=", "JAVA_VERSION");
    }

    @Test
    void runSteps_passesEachArgumentVerbatim_withoutAShell() throws Exception {
        var result = run(hostileEnvironment(), "4", "/bin/echo", "a b", "c;d", "$(touch /tmp/temper-pwned)");

        assertThat(result.rc()).isZero();
        assertThat(result.output()).contains("a b c;d $(touch /tmp/temper-pwned)");
    }

    @Test
    void runSteps_runsStepsInOrder_andExitsWithTheFirstNonZeroRc() throws Exception {
        var result = run(hostileEnvironment(), "2", "/bin/echo", "first", "1", "/usr/bin/false", "2", "/bin/echo", "never");

        assertThat(result.rc()).isEqualTo(1);
        assertThat(result.output()).contains("first")
                                   .doesNotContain("never");
    }

    @Test
    void runSteps_mergesStepStderrIntoItsOutput() throws Exception {
        var result = run(hostileEnvironment(), "2", "/bin/ls", "/temper-does-not-exist");

        assertThat(result.rc()).isNotZero();
        assertThat(result.output()).contains("temper-does-not-exist");
    }

    @Test
    void runSteps_exitsUsage_forMalformedArguments() throws Exception {
        assertThat(run(hostileEnvironment()).rc()).isEqualTo(2);
        assertThat(run(hostileEnvironment(), "x", "/bin/echo").rc()).isEqualTo(2);
        assertThat(run(hostileEnvironment(), "5", "/bin/echo").rc()).isEqualTo(2);
        assertThat(run(hostileEnvironment(), "0").rc()).isEqualTo(2);
    }

    @Test
    void runSteps_listsTheOtherJvms_inJcmdShape() throws Exception {
        var result = run(hostileEnvironment(), "--jvms");
        var testJvm = Long.toString(ProcessHandle.current()
                                                 .pid());

        assertThat(result.rc()).isZero();
        assertThat(result.output()
                         .lines()).anySatisfy(line -> assertThat(line).startsWith(testJvm + " "));
        assertThat(result.output()
                         .lines()).allSatisfy(line -> assertThat(line).matches("^[0-9]+ .*java.*"));
    }
}
