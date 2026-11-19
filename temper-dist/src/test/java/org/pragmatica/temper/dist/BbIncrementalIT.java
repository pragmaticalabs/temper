package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// `ops/bb/bb-modules.py` and `bb-build --since` (design docs/bb-build-once-design.md §1, control 9) on a fixture reactor:
/// `build-parent` (pom packaging, parent of every module), `m` (the changed leaf), `u` (a leaf), `d` -> m, u, `x` -> d, `y` -> u, `z`.
/// The expected `-pl` strings are literal fixtures. Dependent D of M has a second upstream U, so S for a change in M must hold U.
///
/// Probes (2026-11-19): close upstream over C instead of C u amd(C) in select() -> [#leafChange_selectsTheLeafItsDependents_andTheirOtherUpstreams]
/// red; skip the aggregator/parent/pom-packaging FULL -> [#parentPomChange_runsTheFullReactor] red; skip the unmapped-path FULL ->
/// [#unmappedPath_runsTheFullReactor], [#unmappedPathBesideAModuleChange_runsTheFullReactor] and [#since_unmappedPath_recordsItsOwnReason] red
/// (without the guard the empty-S fallback answers `FULL empty-set`; the asserted REASON pins each fallback on its own).
/// Edge probes (inc2): drop the profile edges -> [#profileOnlyDependency_isAnEdge] red; drop the plugin-dependency edges -> [#pluginDependency_isAnEdge] red.
/// `FULL empty-set` is a defensive branch no input reaches (a changed module is always in S), so no test pins it.
class BbIncrementalIT {
    private static final Path MODULES = BbFixtures.OPS_BB.resolve("bb-modules.py");
    private static final Path BB_BUILD = BbFixtures.OPS_BB.resolve("bb-build");
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");

    @TempDir
    Path dir;

    private final List<String> tickets = new ArrayList<>();

    @AfterEach
    void killWaiters() throws Exception {
        for (var id : tickets) {
            BbFixtures.kill(BbFixtures.waiters(id));
        }
    }

    private static String pom(String artifactId,
                              String parent,
                              String packaging,
                              List<String> deps,
                              List<String> modules) {
        var sb = new StringBuilder("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n<modelVersion>4.0.0</modelVersion>\n");

        if (parent != null) {
            sb.append("<parent><groupId>g</groupId><artifactId>")
              .append(parent)
              .append("</artifactId><version>1</version>")
              .append("<relativePath>../")
              .append(parent)
              .append("/pom.xml</relativePath></parent>\n");
        } else {
            sb.append("<groupId>g</groupId>\n<version>1</version>\n");
        }

        sb.append("<artifactId>")
          .append(artifactId)
          .append("</artifactId>\n<packaging>")
          .append(packaging)
          .append("</packaging>\n");
        if (!modules.isEmpty()) {
            sb.append("<modules>");
            modules.forEach(m -> sb.append("<module>")
                                   .append(m)
                                   .append("</module>"));
            sb.append("</modules>\n");
        }

        if (!deps.isEmpty()) {
            sb.append("<dependencies>");
            deps.forEach(d -> sb.append("<dependency><groupId>g</groupId><artifactId>")
                                .append(d)
                                .append("</artifactId></dependency>"));
            sb.append("</dependencies>\n");
        }

        return sb.append("</project>\n")
                 .toString();
    }

    private static String with(String pom, String extraXml) {
        return pom.replace("</project>\n", extraXml + "\n</project>\n");
    }

    private static String depOn(String artifactId, String extra) {
        return "<dependency><groupId>g</groupId><artifactId>" + artifactId + "</artifactId>" + extra + "</dependency>";
    }

    private static void git(Path co, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", co.toString(), "-c", "user.name=t", "-c", "user.email=t@t"));

        command.addAll(List.of(args));
        var out = BbFixtures.run(command, Map.of(), null, null);

        assertThat(out.rc()).as(out.stderr()).isZero();
    }

    private static void write(Path co, String file, String content) throws IOException {
        Files.createDirectories(co.resolve(file).getParent());
        Files.writeString(co.resolve(file), content);
    }

    /// The fixture reactor, committed once and tagged `base`.
    private Path reactor(String name) throws Exception {
        var co = Files.createDirectories(dir.resolve("work").resolve("t3b-" + name));

        write(co,
              "pom.xml",
              pom("root",
                  null,
                  "pom",
                  List.of(),
                  List.of("build-parent",
                          "m",
                          "u",
                          "d",
                          "x",
                          "y",
                          "z",
                          "plib",
                          "pdep",
                          "glib",
                          "gdep",
                          "elib",
                          "edep",
                          "blib",
                          "bdep",
                          "tlib",
                          "tdep",
                          "nlib",
                          "nest")));
        write(co,
              "build-parent/pom.xml",
              pom("build-parent", null, "pom", List.of(), List.of()));
        write(co,
              "m/pom.xml",
              pom("m", "build-parent", "jar", List.of(), List.of()));
        write(co,
              "u/pom.xml",
              pom("u", "build-parent", "jar", List.of(), List.of()));
        write(co,
              "d/pom.xml",
              pom("d", "build-parent", "jar", List.of("m", "u"), List.of()));
        write(co,
              "x/pom.xml",
              pom("x", "build-parent", "jar", List.of("d"), List.of()));
        write(co,
              "y/pom.xml",
              pom("y", "build-parent", "jar", List.of("u"), List.of()));
        write(co,
              "z/pom.xml",
              pom("z", "build-parent", "jar", List.of(), List.of()));
        for (var lib : List.of("plib", "glib", "elib", "blib", "tlib", "nlib")) {
            write(co,
                  lib + "/pom.xml",
                  pom(lib, "build-parent", "jar", List.of(), List.of()));
        }
        // one dependent per edge kind maven orders the reactor by, each with exactly that edge and no top-level <dependencies>
        write(co,
              "pdep/pom.xml",
              with(pom("pdep", "build-parent", "jar", List.of(), List.of()),
                   "<profiles><profile><id>p</id><dependencies>" + depOn("plib", "")
                  + "</dependencies></profile></profiles>"));
        write(co,
              "gdep/pom.xml",
              with(pom("gdep", "build-parent", "jar", List.of(), List.of()),
                   "<build><plugins><plugin><groupId>x</groupId><artifactId>some-plugin</artifactId><dependencies>" + depOn("glib",
                                                                                                                            "")
                  + "</dependencies></plugin></plugins></build>"));
        write(co,
              "edep/pom.xml",
              with(pom("edep", "build-parent", "jar", List.of(), List.of()),
                   "<build><extensions><extension><groupId>g</groupId><artifactId>elib</artifactId><version>1</version></extension></extensions></build>"));
        write(co,
              "bdep/pom.xml",
              with(pom("bdep", "build-parent", "jar", List.of(), List.of()),
                   "<build><plugins><plugin><groupId>g</groupId><artifactId>blib</artifactId></plugin></plugins></build>"));
        write(co,
              "tdep/pom.xml",
              with(pom("tdep", "build-parent", "jar", List.of(), List.of()),
                   "<dependencies>" + depOn("tlib",
                                            "<type>test-jar</type><classifier>tests</classifier><scope>test</scope>")
                  + "</dependencies>"));
        write(co,
              "nest/pom.xml",
              pom("nest", "build-parent", "pom", List.of(), List.of("inner")));
        write(co,
              "nest/inner/pom.xml",
              pom("inner", "build-parent", "jar", List.of("nlib"), List.of()));
        for (var module : List.of("m",
                                  "u",
                                  "d",
                                  "x",
                                  "y",
                                  "z",
                                  "plib",
                                  "glib",
                                  "elib",
                                  "blib",
                                  "tlib",
                                  "nlib",
                                  "nest/inner")) {
            write(co, module + "/src/main/java/A.java", "class A {}\n");
        }

        write(co, "docs/readme.md", "docs\n");
        write(co, ".gitignore", ".bb/\ntarget/\n");
        git(co, "init", "-q");
        git(co, "add", ".");
        git(co, "commit", "-qm", "base");
        git(co, "tag", "base");

        return co;
    }

    private void commit(Path co, String file, String content) throws Exception {
        write(co, file, content);
        git(co, "add", ".");
        git(co, "commit", "-qm", "change " + file);
    }

    private static BbFixtures.Outcome select(Path co) throws Exception {
        return BbFixtures.run(List.of("python3", MODULES.toString(), co.toString(), "base"),
                              Map.of("PATH", System.getenv("PATH")),
                              null,
                              null);
    }

    private static String selected(Path co) throws Exception {
        var out = select(co);

        assertThat(out.rc()).as(out.stderr()).isZero();

        return out.stdout()
                  .strip();
    }

    @Test
    void leafChange_selectsTheLeafItsDependents_andTheirOtherUpstreams() throws Exception {
        var co = reactor("leaf");

        commit(co, "m/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,d,m,u,x");
    }

    @Test
    void changeInATopModule_selectsItsWholeUpstreamClosure_only() throws Exception {
        var co = reactor("top");

        commit(co, "x/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,d,m,u,x");
    }

    @Test
    void unrelatedLeaf_selectsOnlyItself() throws Exception {
        var co = reactor("lone");

        commit(co, "z/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,z");
    }

    @Test
    void moduleOwnPomChange_isNotFull() throws Exception {
        var co = reactor("ownpom");

        commit(co,
               "y/pom.xml",
               pom("y", "build-parent", "jar", List.of("u"), List.of()) + "<!-- edit -->\n");
        assertThat(selected(co)).isEqualTo("build-parent,u,y");
    }

    @Test
    void untrackedFileInAModule_selectsThatModule() throws Exception {
        var co = reactor("untracked");

        write(co, "y/src/main/java/New.java", "class New {}\n");
        assertThat(selected(co)).isEqualTo("build-parent,u,y");
    }

    @Test
    void dirtyTrackedFile_selectsItsModule_andAnIgnoredFileNothing() throws Exception {
        var co = reactor("dirty");

        write(co, "z/src/main/java/A.java", "class A { int dirty; }\n");
        write(co, "x/target/out.class", "ignored\n");
        assertThat(selected(co)).isEqualTo("build-parent,z");
    }

    @Test
    void rootPomChange_runsTheFullReactor() throws Exception {
        var co = reactor("root");

        commit(co,
               "pom.xml",
               Files.readString(co.resolve("pom.xml")) + "<!-- edit -->\n");
        assertThat(selected(co)).isEqualTo("FULL root-pom");
    }

    @Test
    void parentPomChange_runsTheFullReactor() throws Exception {
        var co = reactor("parent");

        commit(co,
               "build-parent/pom.xml",
               pom("build-parent", null, "pom", List.of(), List.of()) + "<!-- edit -->\n");
        assertThat(selected(co)).isEqualTo("FULL parent:build-parent/pom.xml");
    }

    @Test
    void mvnConfigChange_runsTheFullReactor() throws Exception {
        var co = reactor("mvnconf");

        commit(co, ".mvn/maven.config", "-T1\n");
        assertThat(selected(co)).isEqualTo("FULL mvn-config:.mvn/maven.config");
    }

    @Test
    void unmappedPath_runsTheFullReactor() throws Exception {
        var co = reactor("unmapped");

        commit(co, "docs/readme.md", "docs edited\n");
        assertThat(selected(co)).isEqualTo("FULL unmapped:docs/readme.md");
    }

    @Test
    void unmappedPathBesideAModuleChange_runsTheFullReactor() throws Exception {
        var co = reactor("mixed");

        commit(co, "docs/readme.md", "docs edited\n");
        commit(co, "z/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("FULL unmapped:docs/readme.md");
    }

    @Test
    void unparsablePom_runsTheFullReactor() throws Exception {
        var co = reactor("broken");

        commit(co, "z/pom.xml", "<project>not closed\n");
        assertThat(selected(co)).isEqualTo("FULL unreadable-reactor:ParseError");
    }

    @Test
    void reactorParent_isAnUpstreamOfItsChildren() throws Exception {
        var co = reactor("parentedge");

        commit(co, "z/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,z");
    }

    @Test
    void profileOnlyDependency_isAnEdge() throws Exception {
        var co = reactor("profiledep");

        commit(co, "plib/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,pdep,plib");
    }

    @Test
    void pluginDependency_isAnEdge() throws Exception {
        var co = reactor("plugindep");

        commit(co, "glib/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,gdep,glib");
    }

    @Test
    void buildExtension_isAnEdge() throws Exception {
        var co = reactor("extension");

        commit(co, "elib/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,edep,elib");
    }

    @Test
    void buildPlugin_isAnEdge() throws Exception {
        var co = reactor("plugin");

        commit(co, "blib/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("bdep,blib,build-parent");
    }

    @Test
    void testJarDependent_isAnEdge() throws Exception {
        var co = reactor("testjar");

        commit(co, "tlib/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,tdep,tlib");
    }

    @Test
    void nestedModulesTree_isWalked_inBothDirections() throws Exception {
        var co = reactor("nested");

        commit(co, "nlib/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,nest/inner,nlib");
        commit(co, "nest/inner/src/main/java/A.java", "class A { int changed; }\n");
        assertThat(selected(co)).isEqualTo("build-parent,nest/inner,nlib");
    }

    @Test
    void noChange_isNone() throws Exception {
        var co = reactor("none");

        assertThat(selected(co)).isEqualTo("NONE");
    }

    @Test
    void unknownBase_isRefused_withExit2() throws Exception {
        var co = reactor("badbase");
        var out = BbFixtures.run(List.of("python3", MODULES.toString(), co.toString(), "no-such-ref"),
                                 Map.of("PATH", System.getenv("PATH")),
                                 null,
                                 null);

        assertThat(out.rc()).isEqualTo(2);
        assertThat(out.stdout()).isBlank();
    }

    // ---- bb-build --since: the ticket and the queue
    private Map<String, String> env(Path queue) throws IOException {
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho stubbed \"$@\"\n");
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME",
                Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "t3b");
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_MAX_REACTORS", "0");

        return env;
    }

    private BbFixtures.Outcome bb(Path queue, String... args) throws Exception {
        var command = new ArrayList<String>();

        command.add(BB_BUILD.toString());
        command.addAll(List.of(args));
        var outcome = BbFixtures.run(command, env(queue), null, null);
        var matcher = QUEUED.matcher(outcome.stdout());

        if (matcher.find()) {
            tickets.add(matcher.group(1));
        }

        return outcome;
    }

    private static List<String> ticket(Path queue, BbFixtures.Outcome outcome) throws IOException {
        var matcher = QUEUED.matcher(outcome.stdout());

        assertThat(matcher.find()).as(outcome.stdout() + outcome.stderr()).isTrue();

        return Files.readAllLines(queue.resolve("waiting").resolve(matcher.group(1)))
                    .stream()
                    .filter(l -> !l.startsWith("blocked_since="))
                    .toList();
    }

    private static String value(List<String> lines, String key) {
        return lines.stream()
                    .filter(l -> l.startsWith(key + "="))
                    .map(l -> l.substring(key.length() + 1))
                    .reduce((a, b) -> b)
                    .orElse(null);
    }

    @Test
    void since_expandsToAnExplicitPl_printsIt_andWritesTheTicketLines() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var co = reactor("tkt");

        commit(co, "m/src/main/java/A.java", "class A { int changed; }\n");
        var queue = dir.resolve("q");
        var out = bb(queue, "--since", "base", co.toString(), "-B", "test");
        var lines = ticket(queue, out);

        assertThat(out.rc()).as(out.stderr()).isZero();
        assertThat(out.stdout()).contains("--since base -> -pl build-parent,d,m,u,x (5 modules)");
        assertThat(value(lines, "args")).isEqualTo("-pl build-parent\\,d\\,m\\,u\\,x -B test ");  // the ticket holds printf %q words
        assertThat(value(lines, "since")).isEqualTo("base");
        assertThat(value(lines, "modules")).isEqualTo("5");
        assertThat(value(lines, "since_reason")).isNull();
        var keys = lines.stream().map(l -> l.substring(0, l.indexOf('='))).toList();

        assertThat(keys.indexOf("since")).isEqualTo(keys.indexOf("group_reason") + 1);
        assertThat(keys.indexOf("modules")).isLessThan(keys.indexOf("args"));
    }

    @Test
    void since_full_keepsTheWholeReactor_andRecordsFull() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var co = reactor("full");

        commit(co,
               "pom.xml",
               Files.readString(co.resolve("pom.xml")) + "<!-- edit -->\n");
        var queue = dir.resolve("q");
        var out = bb(queue, "--since", "base", co.toString(), "-B", "test");
        var lines = ticket(queue, out);

        assertThat(value(lines, "args")).isEqualTo("-B test ");
        assertThat(value(lines, "modules")).isEqualTo("FULL");
        assertThat(value(lines, "since")).isEqualTo("base");
        assertThat(value(lines, "since_reason")).isEqualTo("root-pom");
        assertThat(out.stdout()).contains("--since base -> FULL reactor (root-pom)");
    }

    @Test
    void since_unmappedPath_recordsItsOwnReason() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var co = reactor("unmappedtkt");

        commit(co, "docs/readme.md", "docs edited\n");
        var queue = dir.resolve("q");
        var out = bb(queue, "--since", "base", co.toString(), "-B", "test");
        var lines = ticket(queue, out);

        assertThat(value(lines, "modules")).isEqualTo("FULL");
        assertThat(value(lines, "since_reason")).isEqualTo("unmapped:docs/readme.md");
    }

    @Test
    void since_none_exitsZero_saysSo_andQueuesNothing() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var co = reactor("nothing");
        var queue = dir.resolve("q");
        var out = bb(queue, "--since", "base", co.toString(), "-B", "test");

        assertThat(out.rc()).as(out.stderr()).isZero();
        assertThat(out.stdout()).contains("nothing changed since base");
        assertThat(Files.exists(queue.resolve("waiting"))
                   ? Files.list(queue.resolve("waiting")).count()
                   : 0).isZero();
        assertThat(co.resolve(".bb")).doesNotExist();
    }

    @Test
    void since_dedupKey_isTheExpandedArgs() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var co = reactor("dedup1");

        commit(co, "m/src/main/java/A.java", "class A { int changed; }\n");
        var twin = dir.resolve("work").resolve("t3b-dedup2");

        git(co, "clone", "-q", co.toString(), twin.toString());
        var queue = dir.resolve("q");
        var first = bb(queue, "--since", "base", co.toString(), "-B", "test");
        var second = bb(queue, twin.toString(), "-pl", "build-parent,d,m,u,x", "-B", "test");

        assertThat(first.rc()).as(first.stderr()).isZero();
        assertThat(second.rc()).as(second.stdout() + second.stderr()).isEqualTo(7);
        assertThat(second.stderr()).contains("dedup: identical build is waiting");
    }

    @Test
    void since_isRefused_withCallerSelection_aBadBase_andAMissingBase() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var co = reactor("refuse");

        commit(co, "m/src/main/java/A.java", "class A { int changed; }\n");
        var queue = dir.resolve("q");
        var selected = bb(queue, "--since", "base", co.toString(), "-pl", "z", "-B", "test");
        var unknown = bb(queue, "--since", "no-such-ref", co.toString(), "-B", "test");
        var dashed = bb(queue, "--since", "--oops", co.toString());
        var bare = bb(queue, "--since");

        assertThat(List.of(selected.rc(), unknown.rc(), dashed.rc(), bare.rc())).containsOnly(2);
        assertThat(selected.stderr()).contains("expands to its own -pl");
        assertThat(Files.exists(queue.resolve("waiting"))
                   ? Files.list(queue.resolve("waiting")).count()
                   : 0).isZero();
    }
}
