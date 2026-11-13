package org.pragmatica.temper.dist;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// `ops/bb/bb-mutate --batch` run for real against a throwaway git checkout, with a fake `$HOME/tools/bb-build` that fabricates
/// surefire XML per module (`bb/fake-bb-build.py`). Module B depends on A, so a red A leaves B's tests unrun, the defect that
/// made a batch read SURVIVED for a mutant whose tests never ran. The installed tool before the fix (`bb/bb-mutate.prev`) is run
/// on the same scenario as the positive control.
///
/// Probes (each mutation of ops/bb/bb-mutate turns the named test red):
///  - drop the freshness filter (`st_mtime_ns > marker_ns` accepts any XML) -> [#aStaleReport_isNotRun_neverSurvived]
///  - drop `testcases > 0` in `ran` -> [#anEmptyReport_isNotRun_neverSurvived]
///  - exit 0 although a mutant is NOT-RUN -> [#everyMutantNotRun_exitsFive], [#anEmptyReport_isNotRun_neverSurvived]
///  - drop the single re-run of a NOT-RUN batch-mate -> [#aDownstreamMutant_isNotRunInTheBatch_thenCreditedSingly]
///  - drop the expected-class test in `ran` -> [#aReportOfAnotherClass_isNotRun_neverSurvived]
class BbMutateIT {
    private static final Path TOOL = BbFixtures.OPS_BB.resolve("bb-mutate");

    @TempDir
    Path dir;

    private Path home;
    private Path co;

    @BeforeEach
    void checkout() throws Exception {
        assumeTrue(has("python3") && has("git"), "bb-mutate is python3 and drives git");
        dir = dir.toRealPath();
        home = Files.createDirectories(dir.resolve("home"));
        co = dir.resolve("work/co/x");
        var fake = BbFixtures.executable(home.resolve("tools/bb-build"), resource("fake-bb-build.py"));

        assertThat(fake).exists();
        sh("""
           set -e
           mkdir -p "%1$s" && cd "%1$s"
           git init -q . && mkdir .mvn
           echo "-Dmaven.repo.local=$PWD/.m2-local" > .mvn/maven.config
           printf '.bb/\\ntarget/\\n.m2-local/\\n' > .gitignore
           for m in A B C; do mkdir -p $m/src; echo ok > $m/src/f.txt; done
           git add -A && git -c user.name=t -c user.email=t@t commit -qm init
           git init -q --bare "%2$s" && git remote add origin "%2$s" && git push -q origin HEAD:refs/heads/main && git fetch -q origin
           git checkout -q --detach
           """.formatted(co, dir.resolve("remote.git")));
    }

    /// B depends on A. The batch turns A red, so B never runs: B's mutant is NOT-RUN in the batch and credited by its single re-run.
    /// The old tool read the same batch as B SURVIVED (see [#theOldTool_readsADownstreamMutantAsSurvived]).
    @Test
    void aDownstreamMutant_isNotRunInTheBatch_thenCreditedSingly() throws Exception {
        var out = batch(modules(null), mutation("mA", "A", "ATest", "MUT"), mutation("mB", "B", "BTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "RED").containsEntry("mB", "RED");
        assertThat(out.rc()).isZero();
        assertThat(builds()).containsExactly("[] false",

        // control
        "[A,B] true",

        // the batch, with -fae
        "[B] false");  // mB singly; mA is not re-run
        assertThat(out.log()).contains("NOT-RUN in batch (re-running singly): mB");
    }

    @Test
    void theOldTool_readsADownstreamMutantAsSurvived() throws Exception {
        var out = batchWith(resource("bb-mutate.prev"),
                            modules(null),
                            mutation("mA", "A", "ATest", "MUT"),
                            mutation("mB", "B", "BTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "RED").containsEntry("mB", "SURVIVED");
    }

    /// A report of the right module that holds no testcases is no evidence: NOT-RUN in the batch, still NOT-RUN singly, tallied apart.
    @Test
    void anEmptyReport_isNotRun_neverSurvived() throws Exception {
        var out = batch(modules("zero"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "NOT-RUN").containsEntry("mC", "RED");
        assertThat(out.rc()).as("mixed: one mutant still NOT-RUN").isEqualTo(5);
        assertThat(builds()).hasSize(3);
        assertThat(out.stdout()).contains("NOT-RUN=1").contains("RED=1").doesNotContain("SURVIVED");
        assertThat(out.md()).contains("neither detected nor survived");
    }

    @Test
    void aReportOfAnotherClass_isNotRun_neverSurvived() throws Exception {
        var out = batch(modules("otherclass"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "NOT-RUN").containsEntry("mC", "RED");
        assertThat(out.rc()).isEqualTo(5);
    }

    /// Every mutant NOT-RUN (nothing detected, nothing survived): a caller that checks only rc must not read it as a pass.
    @Test
    void everyMutantNotRun_exitsFive() throws Exception {
        var out = batch(modules("zero"), mutation("mA", "A", "ATest", "MUT"));

        assertThat(out.outcomes()).containsOnlyKeys("mA").containsEntry("mA", "NOT-RUN");
        assertThat(out.rc()).isEqualTo(5);
        assertThat(out.stdout()).contains("NOT-RUN=1");
    }

    /// A module whose report was written before the build (the control's) is stale: not evidence for the mutant's build.
    @Test
    void aStaleReport_isNotRun_neverSurvived() throws Exception {
        var out = batch(modules("stale"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "NOT-RUN").containsEntry("mC", "RED");
        assertThat(out.rc()).isEqualTo(5);
    }

    /// Independent modules that all run: one batch, credited as before (RED and SURVIVED), no re-run.
    @Test
    void independentModules_areCreditedInOneBatch() throws Exception {
        var out = batch(modules(null), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "fine"));

        assertThat(out.outcomes()).containsEntry("mA", "RED").containsEntry("mC", "SURVIVED");
        assertThat(out.rc()).isZero();
        assertThat(builds()).containsExactly("[] false", "[A] true");
    }

    /// One module, one mutant: the build is a plain single run (no -fae) and credited.
    @Test
    void aLoneMutant_isRunWithoutFae() throws Exception {
        var out = batch(modules(null), mutation("mA", "A", "ATest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "RED");
        assertThat(out.rc()).isZero();
        assertThat(builds()).containsExactly("[] false", "[A] false");
    }

    /// Issue #46: every bb-build call carries BB_VIA=bb-mutate:<spec basename>:<label>, the label being `control` or the mutant id(s) of the run
    /// (a batch joins them with '+', which the ticket field cannot hold, so '.'); bb-queue shows the label as [mutant <label>].
    @Test
    void everyBuild_carriesBbVia_withTheSpecBasenameAndTheMutantLabel() throws Exception {
        batch(modules(null), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "fine"));
        assertThat(Files.readAllLines(home.resolve("via.log"))).containsExactly("bb-mutate:spec:control",
                                                                                "bb-mutate:spec:mA.mC");
    }

    /// A spec name or mutant id outside [A-Za-z0-9._-] is cut to it, and the whole value stays within the 64 bytes bb-build accepts.
    @Test
    void bbVia_isSanitizedAndBounded() throws Exception {
        specName = "my spec+v1.json";
        batch(modules(null), mutation("m+A", "A", "ATest", "MUT"), mutation("x".repeat(80), "C", "CTest", "fine"));
        var vias = Files.readAllLines(home.resolve("via.log"));

        assertThat(vias).hasSize(2);
        assertThat(vias.get(1)).startsWith("bb-mutate:my.spec.v1:m.A.xxx");
        assertThat(vias).allMatch(v -> v.matches("^[A-Za-z0-9._:-]{1,64}$"));
        assertThat(vias.get(1)).hasSize(64);
    }

    // ---- harness
    private String specName = "spec.json";

    private record Result(int rc, Map<String, String> outcomes, String stdout, String log, String md) {}

    private static String mutation(String id, String module, String test, String to) {
        return """
            {"id":"%s","file":"%s/src/f.txt","kind":"lit","old":"ok","new":"%s","expect":["%s"]}""".formatted(id,
                                                                                                              module,
                                                                                                              to,
                                                                                                              test);
    }

    /// reactor order A, B (depends on A), C; `fault` applies to module A only (null: none)
    private static String modules(String fault) {
        var fa = fault == null
                 ? "null"
                 : "\"" + fault + "\"";

        return """
            {"modules":[{"name":"A","class":"ATest","cases":["t1","t2"],"fault":%s},
                         {"name":"B","deps":["A"],"class":"BTest","cases":["t1"]},
                         {"name":"C","class":"CTest","cases":["t1"]}]}""".formatted(fa);
    }

    private Result batch(String scenario, String... mutations) throws Exception {
        return batchWith(null, scenario, mutations);
    }

    private Result batchWith(String tool, String scenario, String... mutations) throws Exception {
        var script = tool == null
                     ? TOOL
                     : Files.writeString(dir.resolve("old-bb-mutate"), tool);
        var out = dir.resolve("out");

        Files.writeString(home.resolve("scenario.json"), scenario);
        Files.writeString(dir.resolve(specName), "{\"build\":{},\"mutations\":[" + String.join(",", mutations) + "]}");
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", home.toString());
        env.put("BB_OWNER", "t");
        var run = BbFixtures.run(List.of("python3",
                                         script.toString(),
                                         co.toString(),
                                         dir.resolve(specName).toString(),
                                         out.toString(),
                                         "--batch"),
                                 env,
                                 null,
                                 null);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isIn(0, 5);
        var outcomes = new HashMap<String, String>();

        Files.readAllLines(out.resolve("results.tsv"))
             .stream()
             .skip(1)
             .map(l -> l.split("\t"))
             .forEach(c -> outcomes.put(c[0], c[1]));

        return new Result(run.rc(),
                          outcomes,
                          run.stdout(),
                          Files.readString(out.resolve("bb-mutate.log")),
                          Files.readString(out.resolve("results.md")));
    }

    /// one `[mutated modules] fae` line per build the fake bb-build ran
    private List<String> builds() throws IOException {
        return Files.readAllLines(home.resolve("builds.log"));
    }

    private void sh(String script) throws Exception {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        var run = BbFixtures.run(List.of("bash", "-c", script), env, null, null);

        assertThat(run.rc()).as(run.stderr()).isZero();
    }

    private static boolean has(String tool) {
        try {
            return BbFixtures.run(List.of(tool, "--version"),
                                  new HashMap<>(Map.of("PATH",
                                                       System.getenv("PATH"))),
                                  null,
                                  null)
                             .rc() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = BbMutateIT.class.getResourceAsStream("/bb/" + name)) {
            assertThat(in).as("test resource bb/" + name).isNotNull();

            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
