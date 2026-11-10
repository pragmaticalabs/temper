package org.pragmatica.temper.domain.mvn.read;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.mvn.ReactorError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// m1-design §1.6 (Q-M1-4): the reactor from the root pom, through aggregators and activated profiles.
class ReactorPomsTest {
    private static String pom(String body) {
        return "<?xml version=\"1.0\"?>\n<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
               + body + "</project>";
    }

    private static String modules(String... entries) {
        return "<modules>" + Stream.of(entries)
                                   .map(entry -> "<module>" + entry + "</module>")
                                   .collect(Collectors.joining()) + "</modules>";
    }

    private static Fn1<Option<byte[]>, String> tree(Map<String, String> poms) {
        return module -> Option.option(poms.get(module))
                               .map(text -> text.getBytes(StandardCharsets.UTF_8));
    }

    private static Option<byte[]> counted(Map<String, Integer> reads, Map<String, String> poms, String module) {
        reads.merge(module, 1, Integer::sum);

        return tree(poms).apply(module);
    }

    /// pragmatica's shape (F5): forge-tests only in a profile activated by `-P`, which has no `<activation>`.
    @Test
    void parse_followsAggregatorsAndActivatedProfiles_only() {
        var poms = Map.of("", pom(modules("core", "aether")
                                  + "<profiles>"
                                  + "<profile><id>with-e2e</id>" + modules("aether/forge/forge-tests") + "</profile>"
                                  + "<profile><id>jdk</id><activation><jdk>25</jdk></activation>" + modules("tools") + "</profile>"
                                  + "</profiles>"),
                          "core", pom(""),
                          "tools", pom(""),
                          "aether", pom(modules("ember", " forge ", "../docs")),
                          "aether/ember", pom(""),
                          "aether/forge", pom(""),
                          "docs", pom(""));

        assertThat(ok(ReactorPoms.parse(tree(poms))).paths()).containsExactlyInAnyOrder("",
                                                                                        "core",
                                                                                        "tools",
                                                                                        "aether",
                                                                                        "aether/ember",
                                                                                        "aether/forge",
                                                                                        "docs");
    }

    @Test
    void parse_activationAfterModules_stillActivates() {
        var poms = Map.of("", pom("<profiles><profile>" + modules("late") + "<activation><property><name>x</name></property></activation>"
                                  + "</profile></profiles>"),
                          "late", pom(""));

        assertThat(ok(ReactorPoms.parse(tree(poms))).contains("late")).isTrue();
    }

    @Test
    void parse_readsEachModuleOnce_evenWhenEntriesPointBack() {
        var reads = new HashMap<String, Integer>();
        var poms = Map.of("", pom(modules("a", "b")), "a", pom(modules("..", "../b")), "b", pom(modules("../a")));
        Fn1<Option<byte[]>, String> counting = module -> counted(reads, poms, module);

        assertThat(ok(ReactorPoms.parse(counting)).paths()).containsExactlyInAnyOrder("", "a", "b");
        assertThat(reads).containsOnly(Map.entry("", 1), Map.entry("a", 1), Map.entry("b", 1));
    }

    @Test
    void parse_fails_forAMissingPom() {
        assertThat(failure(ReactorPoms.parse(tree(Map.of("", pom(modules("gone"))))))).isInstanceOf(ReactorError.MissingPom.class);
        assertThat(failure(ReactorPoms.parse(tree(Map.of())))).isInstanceOf(ReactorError.MissingPom.class);
    }

    @Test
    void parse_fails_forMalformedXmlAndDoctype() {
        assertThat(failure(ReactorPoms.parse(tree(Map.of("", "<project><modules>"))))).isInstanceOfSatisfying(ReactorError.MalformedPom.class,
                                                                                                      malformed -> assertThat(malformed.source()
                                                                                                                                       .isPresent()).as("the parser's failure is the origin")
                                                                                                                                                    .isTrue());
        assertThat(failure(ReactorPoms.parse(tree(Map.of("", "<?xml version=\"1.0\"?><!DOCTYPE p [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                                                              + "<project><modules><module>&x;</module></modules></project>")))))
            .isInstanceOf(ReactorError.MalformedPom.class);
    }

    @Test
    void parse_fails_forEntriesOutsideTheRepositoryOrNamingFiles() {
        assertThat(failure(ReactorPoms.parse(tree(Map.of("", pom(modules("../outside"))))))).isInstanceOf(ReactorError.UnsupportedModule.class);
        assertThat(failure(ReactorPoms.parse(tree(Map.of("", pom(modules("/abs"))))))).isInstanceOf(ReactorError.UnsupportedModule.class);
        assertThat(failure(ReactorPoms.parse(tree(Map.of("", pom(modules("sub/pom-alt.xml"))))))).isInstanceOf(ReactorError.UnsupportedModule.class);
        assertThat(failure(ReactorPoms.parse(tree(Map.of("", pom(modules(" "))))))).isInstanceOf(ReactorError.UnsupportedModule.class);
    }

    @Test
    void parse_fails_beyondTheModuleBound() {
        var many = IntStream.rangeClosed(1, ReactorPoms.MAX_MODULES)
                            .mapToObj(index -> "m" + index)
                            .toArray(String[]::new);
        Fn1<Option<byte[]>, String> poms = module -> Option.some((module.isEmpty()
                                                                  ? pom(modules(many))
                                                                  : pom("")).getBytes(StandardCharsets.UTF_8));

        assertThat(failure(ReactorPoms.parse(poms))).isInstanceOf(ReactorError.TooManyModules.class);
    }
}
