package org.pragmatica.temper.domain.manifest.read;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.ConfigValue;
import org.pragmatica.temper.domain.manifest.Execution;
import org.pragmatica.temper.domain.manifest.ManifestError;
import org.pragmatica.temper.domain.manifest.PomModule;
import org.pragmatica.temper.domain.manifest.Runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// The effective-POM parser on XML fixtures (m1-design §9.2 `EffectivePomTest`).
class EffectivePomTest {
    private static byte[] fixture(String name) {
        try (var in = EffectivePomTest.class.getResourceAsStream("/manifest/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<PomModule> parse(String name) {
        return EffectivePom.parse(fixture(name), "/ws")
                           .onFailure(cause -> fail(cause.message()))
                           .unwrap();
    }

    private static Cause failure(byte[] xml) {
        return EffectivePom.parse(xml, "/ws")
                           .fold(cause -> cause, _ -> fail("expected a failure"));
    }

    private static Cause failure(String name) {
        return failure(fixture(name));
    }

    @Test
    void parse_readsAReactorWrapper_inOrder() {
        var modules = parse("reactor.xml");

        assertThat(modules).extracting(PomModule::id).containsExactly("org.acme:parent", "org.acme:core", "org.acme.it:it");
        assertThat(modules).extracting(PomModule::path).containsExactly(".", "lib/core", "it");
        assertThat(modules).extracting(PomModule::packaging).containsExactly("pom", "jar", "jar");

        var core = modules.get(1);

        assertThat(core.testOutputDirectory()).isEqualTo("lib/core/target/test-classes");
        assertThat(core.outputDirectory()).isEqualTo("lib/core/target/classes");
        assertThat(core.testSourceDirectory()).isEqualTo("lib/core/src/test/java");
        assertThat(core.properties()).containsEntry("ember.skipTests", "false").containsEntry("empty", "");
    }

    @Test
    void parse_readsOnlyBuildPluginsOfTheTestPlugins() {
        var core = parse("reactor.xml").get(1);

        // pluginManagement, profiles, the compiler plugin and a foreign-group "surefire" are not test plugins
        assertThat(core.testPlugins()).hasSize(1);
        assertThat(core.testPlugins().getFirst().runner()).isEqualTo(Runner.SUREFIRE);
        assertThat(core.testPlugins().getFirst().version()).isEqualTo(Option.some("3.5.4"));
        assertThat(core.testPlugins().getFirst().executions())
            .containsExactly(Execution.execution("default-test",
                                                 Option.some("test"),
                                                 List.of("test"),
                                                 Map.of("excludedGroups", ConfigValue.Text.text("host"))));
    }

    @Test
    void parse_mergesPluginConfigIntoEachExecution_executionWins() {
        var it = parse("reactor.xml").get(2);
        var failsafe = it.testPlugins().getFirst();

        assertThat(failsafe.runner()).isEqualTo(Runner.FAILSAFE);
        assertThat(failsafe.version()).as("no <version> element").isEqualTo(Option.none());
        assertThat(failsafe.executions()).hasSize(2);

        var main = failsafe.executions().getFirst();

        assertThat(main.id()).isEqualTo("default");
        assertThat(main.phase()).isEqualTo(Option.none());
        assertThat(main.goals()).containsExactly("integration-test", "verify");
        assertThat(main.config()).containsEntry("includes", ConfigValue.Items.items(List.of("**/*Check", "**/*IT")))
                                 .containsEntry("skipITs", ConfigValue.Text.text("${it.skip}"));

        var disabled = failsafe.executions().get(1);

        assertThat(disabled.phase()).isEqualTo(Option.some("none"));
        assertThat(disabled.config()).containsEntry("includes", ConfigValue.Items.items(List.of("**/*IT")));
    }

    @Test
    void parse_readsASingleProject() {
        var modules = parse("single.xml");

        assertThat(modules).extracting(PomModule::id).containsExactly("org.acme:solo");
        assertThat(modules.getFirst().path()).isEqualTo(".");
        assertThat(modules.getFirst().packaging()).isEqualTo("jar");
    }

    @Test
    void parse_readsTemperOwnReactor() {
        // help:effective-pom 3.5.1 output for this repository, with the checkout path replaced by /ws
        var modules = parse("temper-reactor.xml");

        assertThat(modules).extracting(PomModule::path)
                           .containsExactly(".", "temper-domain", "temper-protocol", "temper-store", "temper-controller",
                                            "temper-worker", "temper-cli", "temper-dist");

        var domain = modules.get(1);

        assertThat(domain.testOutputDirectory()).isEqualTo("temper-domain/target/test-classes");
        assertThat(domain.testPlugins()).singleElement()
                                        .satisfies(plugin -> assertThat(plugin.executions())
                                            .singleElement()
                                            .satisfies(execution -> assertThat(execution.config())
                                                .containsEntry("excludedGroups", ConfigValue.Text.text("host"))));
    }

    @Test
    void parse_refusesADirectoryOutsideTheWorkspace() {
        assertThat(failure("outside.xml")).isInstanceOfSatisfying(ManifestError.OutsideWorkspace.class,
                                                                   error -> assertThat(error.path()).isEqualTo("/elsewhere/target/test-classes"));
        assertThat(failure("dotdot.xml")).isInstanceOf(ManifestError.OutsideWorkspace.class);
        assertThat(failure("relative.xml")).isInstanceOf(ManifestError.OutsideWorkspace.class);
        Cause otherWorkspace = EffectivePom.parse(fixture("single.xml"), "/w").fold(cause -> cause, _ -> fail("expected a failure"));

        assertThat(otherWorkspace).isInstanceOf(ManifestError.OutsideWorkspace.class);
    }

    @Test
    void parse_refusesAProjectItCannotPlace() {
        assertThat(failure("custom-build-dir.xml")).isInstanceOf(ManifestError.MissingElement.class);
        assertThat(failure("no-artifact.xml")).isInstanceOfSatisfying(ManifestError.MissingElement.class,
                                                                       error -> assertThat(error.element()).isEqualTo("artifactId"));
    }

    @Test
    void parse_failsOnMalformedXml_andNeverResolvesExternalEntities() {
        assertThat(failure("<projects><project>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ManifestError.MalformedPom.class);
        assertThat(failure(new byte[0])).isInstanceOf(ManifestError.MalformedPom.class);
        assertThat(failure("xxe.xml")).isInstanceOf(ManifestError.MalformedPom.class);
    }
}
