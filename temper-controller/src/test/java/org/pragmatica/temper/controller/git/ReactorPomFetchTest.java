package org.pragmatica.temper.controller.git;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.mvn.ReactorModules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The reactor at submit (m1-design §1.6) through an asynchronous file reader: level by level, only the poms the
/// reactor names, and an unknown reactor whenever it cannot be judged.
class ReactorPomFetchTest {
    private final Map<String, String> tree = new ConcurrentHashMap<>();
    private final List<String> reads = new CopyOnWriteArrayList<>();

    private static String pom(String... modules) {
        return "<project><modules>" + String.join("", java.util.Arrays.stream(modules).map(module -> "<module>" + module + "</module>").toList())
               + "</modules></project>";
    }

    private Promise<Option<byte[]>> file(String path) {
        reads.add(path);
        return Promise.success(Option.option(tree.get(path)).map(text -> text.getBytes(StandardCharsets.UTF_8)));
    }

    private Result<Option<ReactorModules>> reactor() {
        return ReactorPomFetch.reactor(this::file, 4).await();
    }

    @Test
    void reactor_followsModulesLevelByLevel() {
        tree.put("pom.xml", pom("core", "aether"));
        tree.put("core/pom.xml", pom());
        tree.put("aether/pom.xml", pom("ember", "forge"));
        tree.put("aether/ember/pom.xml", pom());
        tree.put("aether/forge/pom.xml", pom("forge-tests"));
        tree.put("aether/forge/forge-tests/pom.xml", pom());
        tree.put("unrelated/pom.xml", pom());

        var reactor = reactor().unwrap().unwrap();

        assertThat(reactor.paths()).containsExactlyInAnyOrder("", "core", "aether", "aether/ember", "aether/forge",
                                                              "aether/forge/forge-tests");
        assertThat(reads).as("only the poms the reactor names").doesNotContain("unrelated/pom.xml");
        assertThat(reads.stream().filter("pom.xml"::equals)).as("the root is read once").hasSize(1);
    }

    @Test
    void reactor_isUnknown_whenAModulePomIsMissing() {
        tree.put("pom.xml", pom("core", "gone"));
        tree.put("core/pom.xml", pom());

        assertThat(reactor().isFailure()).isTrue();
    }

    @Test
    void reactor_isUnknown_whenMavenConfigSelectsAnotherReactor() {
        tree.put("pom.xml", pom("core"));
        tree.put("core/pom.xml", pom());
        for (var config : List.of("-Pwith-e2e", "-P with-e2e", "-f other/pom.xml", "-pl core", "--projects core", "-T 1C\n-Pci")) {
            tree.put(ReactorPomFetch.MAVEN_CONFIG, config);

            assertThat(reactor().isFailure()).as(config).isTrue();
        }
    }

    @Test
    void reactor_isRead_whenMavenConfigAddsNoSelection() {
        tree.put("pom.xml", pom("core"));
        tree.put("core/pom.xml", pom());
        tree.put(ReactorPomFetch.MAVEN_CONFIG, "-T 1C\n--no-transfer-progress\n-Dmaven.repo.local=.m2-local/repository\n");

        assertThat(reactor().unwrap()).isEqualTo(some(ReactorModules.reactorModules(Set.of("", "core"))));
    }

    @Test
    void reactor_isUnknown_withoutARootPom() {
        assertThat(reactor().isFailure()).isTrue();
        assertThat(ReactorRead.buildsDefaultReactor(none())).isTrue();
    }
}
