package org.pragmatica.temper.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The per-module poison test of issue #121, written once. A module applies it with a one-line subclass, so its own build runs
/// it: a poisoned host variable (BB_HOST=cloudbb-d1, SSH_AUTH_SOCK ...) put into the builder's inherited environment before
/// the helper runs does not reach the child process, while a variable the test passes explicitly does.
@InheritsHostEnvironment
public abstract class HermeticChildEnvironmentContract {
    /// Mutation: drop a prefix from `HermeticProcess.HOST_PREFIXES`, or stop stripping in `prepared`.
    @Test
    public void poisonedHostVariables_doNotReachTheChild() throws Exception {
        var builder = new ProcessBuilder("env");

        builder.environment().putAll(HermeticProcess.POISON);
        var child = childEnvironment(HermeticProcess.prepared(builder, Map.of("OWN_VARIABLE", "own")));

        assertThat(child).containsEntry("OWN_VARIABLE", "own");
        HermeticProcess.POISON.keySet().forEach(name -> assertThat(child).doesNotContainKey(name));
    }

    /// Mutation: strip every variable (`hostVariable` answers true for all). PATH, HOME and the locale stay inherited.
    @Test
    public void variablesOutsideTheFamilies_stayInherited() throws Exception {
        var builder = new ProcessBuilder("env");

        builder.environment().put("UNRELATED_VARIABLE", "kept");
        var child = childEnvironment(HermeticProcess.prepared(builder, Map.of()));

        assertThat(child).containsEntry("UNRELATED_VARIABLE", "kept").containsKey("PATH");
    }

    /// Mutation: match HOST_NAMES by prefix (`startsWith`) instead of exactly: ENVIRONMENT_NAME and TMPDIR_X would be lost.
    @Test
    public void namesSharingAnExactNameStart_stayInherited() throws Exception {
        var builder = new ProcessBuilder("env");

        builder.environment()
               .putAll(Map.of("ENVIRONMENT_NAME",
                              "kept",
                              "TMPDIR_X",
                              "kept",
                              "KUBECONFIG_NOTE",
                              "kept",
                              "HTTP_PROXY_NOTE",
                              "kept",
                              "MAVENISH",
                              "kept",
                              "AWSOME",
                              "kept"));
        var child = childEnvironment(HermeticProcess.prepared(builder, Map.of()));

        assertThat(child).containsEntry("ENVIRONMENT_NAME", "kept")
                  .containsEntry("TMPDIR_X", "kept")
                  .containsEntry("KUBECONFIG_NOTE", "kept")
                  .containsEntry("HTTP_PROXY_NOTE", "kept")
                  .containsEntry("MAVENISH", "kept")
                  .containsEntry("AWSOME", "kept");
    }

    /// Mutation: strip after the overlay in `prepared` (an explicit variable would be lost).
    @Test
    public void explicitVariableOfAStrippedFamily_survivesTheOverlay() throws Exception {
        var builder = new ProcessBuilder("env");

        builder.environment().put("BB_HOST", "cloudbb-d1");
        var child = childEnvironment(HermeticProcess.prepared(builder, Map.of("BB_HOST", "scenario")));

        assertThat(child).containsEntry("BB_HOST", "scenario");
    }

    /// Mutation: `builder` stops going through `prepared`. Bites where the build JVM itself inherited a host variable
    /// (a bb-submit build exports BB_*); on a bare shell it can only check that nothing of the families appears.
    @Test
    public void builder_startsFromAHermeticEnvironment() throws Exception {
        var child = childEnvironment(HermeticProcess.builder("env"));

        child.keySet().forEach(name -> assertThat(HermeticProcess.hostVariable(name)).as(name)
                                                 .isFalse());
    }

    private static Map<String, String> childEnvironment(ProcessBuilder builder) throws IOException, InterruptedException {
        var process = builder.redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8);

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        var variables = new HashMap<String, String>();

        output.lines()
              .filter(line -> line.indexOf('=') > 0)
              .forEach(line -> variables.put(line.substring(0,
                                                            line.indexOf('=')),
                                             line.substring(line.indexOf('=') + 1)));

        return variables;
    }
}
