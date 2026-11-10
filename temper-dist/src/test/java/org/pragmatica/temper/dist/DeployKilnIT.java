package org.pragmatica.temper.dist;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.dist.DeploySandbox.Outcome;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/deploy/deploy-kiln.sh` over lib.sh, rows P22-P25,
/// plus the kiln golden plan. The kiln stub answers by the sha256 of the jar live in HOME; the kiln jar is built here
/// byte-deterministically (fixed entry times). stage-local is Linux-only for this unit (DL_OS=Linux), so the rig skips
/// elsewhere.
class DeployKilnIT {
    private static final String COMMIT = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
    private static final String MAIN = "org.pragmatica.kiln.service.Main";
    private static final String PROPS = "org/pragmatica/kiln/service/kiln-version.properties";
    private static final String UNIT_FILE = "[Unit]\nDescription=kiln\n";

    @TempDir
    Path dir;
    private DeploySandbox sb;
    private HttpServer kiln;
    private final Map<String, String> behaviours = new ConcurrentHashMap<>();
    private String oldSha;
    private String newSha;

    @BeforeEach
    void rig() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"), "deploy-kiln.sh stages on Linux");
        sb = new DeploySandbox(dir);
        kiln = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        kiln.createContext("/", this::answer);
        kiln.start();
        sb.env.put("DL_KILN_URL", "http://127.0.0.1:" + kiln.getAddress().getPort());
        Files.writeString(sb.home.resolve("shim/schema"), "2\n");
        Files.createDirectories(sb.home.resolve(".kiln/dist"));
        var oldJar = jar(MAIN, "version=0\njudge=" + "0".repeat(40) + "\n", "old");
        Files.write(live(), oldJar);
        oldSha = DeploySandbox.sha256(oldJar);
        newSha = DeploySandbox.sha256(stageJar(COMMIT, jar(MAIN, "version=1\njudge=" + COMMIT + "\n", "new")));
    }

    @AfterEach
    void close() {
        if (kiln != null) {
            kiln.stop(0);
        }
        if (sb != null) {
            sb.close();
        }
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    private Path live() {
        return sb.home.resolve(".kiln/dist/kiln-service.jar");
    }

    private Path stageDir(String commit) {
        return sb.home.resolve(".deploy/stage/kiln").resolve(commit);
    }

    private Path script(String commit) {
        return stageDir(commit).resolve("deploy-kiln.sh");
    }

    /// A jar with this Main-Class (none when null) and a kiln-version.properties (none when null); `marker` varies the bytes.
    private static byte[] jar(String mainClass, String props, String marker) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        var bytes = new ByteArrayOutputStream();
        try (var out = new JarOutputStream(bytes, manifest)) {
            put(out, "marker.txt", marker);
            if (props != null) {
                put(out, PROPS, props);
            }
        }
        return bytes.toByteArray();
    }

    private static void put(JarOutputStream out, String name, String content) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTime(0);
        out.putNextEntry(entry);
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    /// Writes the build tree `stage-local` collects from and returns the jar bytes (the staged file).
    private byte[] stageJar(String commit, byte[] jar) throws IOException {
        var src = Files.createDirectories(sb.root.resolve("src-" + commit));
        Files.write(Files.createDirectories(src.resolve("kiln-service/target")).resolve("kiln-service-0.1-all.jar"), jar);
        Files.writeString(Files.createDirectories(src.resolve("ops/kiln")).resolve("kiln.service"), UNIT_FILE);
        return jar;
    }

    private Outcome stageLocal(String commit) throws Exception {
        var into = Files.createDirectories(stageDir(commit));
        for (var name : List.of("lib.sh", "deploy-kiln.sh")) {
            var copy = Files.copy(DeploySandbox.OPS_DEPLOY.resolve(name), into.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return sb.run(into.resolve("deploy-kiln.sh"), sb.root, "stage-local", "--commit", commit, "--src", sb.root.resolve("src-" + commit).toString(),
                      "--into", into.toString());
    }

    private Outcome dry() throws Exception {
        return sb.run(script(COMMIT), sb.root, "install", "--commit", COMMIT, "--expect-current", oldSha, "--expect-new", newSha, "--dry-run");
    }

    private Outcome install() throws Exception {
        var dry = dry();
        assertThat(dry.rc()).as(dry.err()).isZero();
        return sb.run(script(COMMIT), sb.root, "install", "--commit", COMMIT, "--expect-current", oldSha, "--expect-new", newSha,
                      "--expect-plan", DeploySandbox.planSha(dry));
    }

    /// Stages the good new jar.
    private void stageNew() throws Exception {
        var out = stageLocal(COMMIT);
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
    }

    // ------------------------------------------------------------------------------------------------- the stub

    /// How the kiln answers while the jar with this sha256 is live: `ok` (default), `foreign-host-200` (answers 200 to a
    /// foreign Host header: the loopback-only rule lost), `500`.
    private void answer(HttpExchange exchange) throws IOException {
        var sha = Files.isRegularFile(live()) ? DeploySandbox.sha256(Files.readAllBytes(live())) : "absent";
        var behaviour = behaviours.getOrDefault(sha, "ok");
        var path = exchange.getRequestURI().getPath();
        var host = exchange.getRequestHeaders().getFirst("Host");
        var status = 200;
        var body = "";
        if (behaviour.equals("500")) {
            status = 500;
        } else if (host != null && host.startsWith("evil.example") && !behaviour.equals("foreign-host-200")) {
            status = 421;
        } else if (path.equals("/")) {
            body = "<html><head><title>kiln: dashboard</title></head></html>";
        } else if (path.equals("/api/v1/sources")) {
            body = "{\"sources\":[]}";
        } else {
            status = 404;
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    // ---------------------------------------------------------------------------------------- P22: the judge

    private void judgeRefuses(String props, String message) throws Exception {
        stageJar(COMMIT, jar(MAIN, props, "judged"));
        var out = stageLocal(COMMIT);
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
        assertThat(out.err()).contains(message);
        assertThat(stageDir(COMMIT)).doesNotExist();
    }

    /// P22: remove the `*-dirty` case of `dl_clean_judge`.
    @Test
    void p22_dirtyJudge_refuses_andLeavesNoStageDir() throws Exception {
        judgeRefuses("version=1\njudge=" + COMMIT + "-dirty\n", "built from a dirty tree");
    }

    /// P22: remove the `unjudged` case.
    @Test
    void p22_unjudged_refuses() throws Exception {
        judgeRefuses("version=1\njudge=unjudged\n", "is judged 'unjudged'");
    }

    /// P22: remove the empty-judge case.
    @Test
    void p22_noJudgeLine_refuses() throws Exception {
        judgeRefuses("version=1\n", "has no judge= line");
    }

    /// P22: remove the judge-is-the-commit comparison.
    @Test
    void p22_judgeOfAnotherCommit_refuses() throws Exception {
        var other = "f".repeat(40);
        judgeRefuses("version=1\njudge=" + other + "\n", "judge " + other + " is not the commit " + COMMIT);
    }

    /// P22: remove the 40-length check.
    @Test
    void p22_judgeOf39Hex_refuses() throws Exception {
        judgeRefuses("version=1\njudge=" + COMMIT.substring(1) + "\n", "is not 40 lowercase hex");
    }

    // ------------------------------------------------------------------------------------ P23: the Main-Class

    /// P23: drop the Main-Class check in `unit_stage_verify`.
    @Test
    void p23_aThinJarWithoutMainClass_refuses() throws Exception {
        stageJar(COMMIT, jar(null, "version=1\njudge=" + COMMIT + "\n", "thin"));
        var out = stageLocal(COMMIT);
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
        assertThat(out.err()).contains("Main-Class is '', not " + MAIN);
        assertThat(stageDir(COMMIT)).doesNotExist();
    }

    // ------------------------------------------------------------------------------------------ P24: KH4

    /// P24: drop KH4 (the 421 probe) from `unit_health`.
    @Test
    void p24_foreignHost200_rollsBack_rc5() throws Exception {
        stageNew();
        behaviours.put(newSha, "foreign-host-200");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(5);
        assertThat(DeploySandbox.sha256(Files.readAllBytes(live()))).isEqualTo(oldSha);
        assertThat(out.out()).contains("FAIL code 200, want 421");
        assertThat(sb.deploysLog()).hasSize(2);
        assertThat(sb.deploysLog().get(0)).contains("outcome=health-failed");
        assertThat(sb.deploysLog().get(1)).contains("event=rollback ").contains("outcome=rolled-back");
    }

    // --------------------------------------------------------------------------------------- P25: happy path

    /// P25: the baseline (P5's mutation also fails it): dump in B, live = new, schema V2->V6, KH1-KH4 ok.
    @Test
    void p25_happyPath_dumpsInstallsAndProbesKh1ToKh4() throws Exception {
        stageNew();
        Files.writeString(sb.home.resolve("shim/migrates"), "6\n");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(DeploySandbox.sha256(Files.readAllBytes(live()))).isEqualTo(newSha);
        assertThat(sb.deploysLog()).hasSize(1);
        var record = sb.deploysLog().get(0);
        assertThat(record).contains("event=install unit=kiln commit=" + COMMIT).contains("sha256=" + newSha).contains("outcome=installed")
                          .contains("rollback=" + oldSha).contains("schema=V2->V6");
        var backup = sb.home.resolve(record.substring(record.indexOf("dir=") + 4));
        assertThat(backup.resolve("kiln-pg.dump")).hasContent("pg_dump bytes");
        assertThat(backup.resolve("MANIFEST.old")).exists();
        assertThat(sb.shimLog()).contains("docker exec kiln-pg pg_dump -U kiln -d kiln -Fc").contains("docker exec -i kiln-pg pg_restore --list");
        assertThat(sb.shimLog().lines().filter(l -> l.equals("systemctl --user restart kiln")).count()).isEqualTo(1);
        assertThat(sb.shimLog().lines().filter(l -> l.startsWith("curl ") && l.contains("/api/v1/sources")).count()).isGreaterThanOrEqualTo(2);
        assertThat(sb.shimLog().lines().filter(l -> l.startsWith("curl ") && l.contains("Host: evil.example")).count()).isGreaterThanOrEqualTo(2);
        assertThat(out.out()).contains("probe " + sb.env.get("DL_KILN_URL") + "/ 200 ok").contains("probe " + sb.env.get("DL_KILN_URL") + "/ 421 ok");
        assertThat(out.out()).contains("/api/v1/sources 200 ok");
        assertThat(Files.getPosixFilePermissions(live())).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
    }

    // ------------------------------------------------------------------------ F-B: drift, KH1 and the link refusal

    private Path unitFile() throws IOException {
        return Files.createDirectories(sb.home.resolve(".config/systemd/user")).resolve("kiln.service");
    }

    private String driftLine(String liveSha) {
        return "drift " + sb.home + "/.config/systemd/user/kiln.service live=" + liveSha + " repo=" + DeploySandbox.sha256(UNIT_FILE);
    }

    /// D4.1: the installed unit file against the repo's at the commit is reported, never refused; equal files report nothing.
    /// Mutation k5: `[ "$ls" = "$rs" ] || echo drift` made unconditional-true (never reports).
    @Test
    void unitDrift_isReported_byDryRunAndInstall_neverRefused_andSilentWhenEqual() throws Exception {
        stageNew();
        Files.writeString(unitFile(), "[Unit]\nDescription=edited by hand\n");
        var drifted = DeploySandbox.sha256("[Unit]\nDescription=edited by hand\n");
        var dry = dry();
        assertThat(dry.rc()).as(dry.err()).isZero();
        assertThat(dry.out()).contains(driftLine(drifted));
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(out.out()).contains(driftLine(drifted));
        Files.writeString(unitFile(), UNIT_FILE);
        assertThat(dry().out()).doesNotContain("drift ");
    }

    /// D4.1: no installed unit file is drift too (`live=absent`).
    @Test
    void unitDrift_absentInstalledUnit_isReportedAsAbsent() throws Exception {
        stageNew();
        assertThat(dry().out()).contains(driftLine("absent"));
    }

    /// KH1 inside kiln's unit_health: a restart that leaves kiln not active fails the health round although the HTTP probes
    /// answer. Mutation k8: drop the `dl_fresh_active kiln` call of unit_health.
    @Test
    void kh1_aRestartThatLeavesKilnInactive_failsHealth_andRollsBack() throws Exception {
        stageNew();
        Files.writeString(sb.home.resolve("shim/kiln.restart-dead"), "");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(5);
        assertThat(out.out()).contains("probe kiln not freshly active");
        assertThat(sb.deploysLog()).hasSize(2);
        assertThat(sb.deploysLog().get(0)).contains("outcome=health-failed");
        assertThat(sb.deploysLog().get(1)).contains("event=rollback ").contains("outcome=rolled-back");
        assertThat(DeploySandbox.sha256(Files.readAllBytes(live()))).isEqualTo(oldSha);
    }

    /// D4.1 precondition: `~/.kiln/dist` is a directory, not a link. Mutation k9: drop the `[ -L ]` half of the check.
    @Test
    void preconditions_kilnDistIsALink_refuses_rc3() throws Exception {
        stageNew();
        Files.move(sb.home.resolve(".kiln/dist"), sb.home.resolve(".kiln/real"));
        Files.createSymbolicLink(sb.home.resolve(".kiln/dist"), sb.home.resolve(".kiln/real"));
        var out = dry();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
        assertThat(out.err()).contains("is not a directory (or is a link)");
    }

    // ------------------------------------------------------------------------- F-C: one jar, the settle default

    private Outcome stageWithJars(String... names) throws Exception {
        var target = Files.createDirectories(sb.root.resolve("src-" + COMMIT).resolve("kiln-service/target"));
        var bytes = jar(MAIN, "version=1\njudge=" + COMMIT + "\n", "new");
        stageJar(COMMIT, bytes);
        for (var name : names) {
            Files.write(target.resolve(name), bytes);
        }
        return stageLocal(COMMIT);
    }

    /// D4.1: exactly one fat jar. Mutation k19: `-ne 1` -> `-lt 1` accepts two.
    @Test
    void twoFatJars_refuse_rc3_andLeaveNoStageDir() throws Exception {
        var out = stageWithJars("kiln-service-0.2-all.jar");
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
        assertThat(out.err()).contains("refused: want exactly one ").contains("kiln-service-*-all.jar");
        assertThat(stageDir(COMMIT)).doesNotExist();
    }

    @Test
    void noFatJar_refuses_rc3() throws Exception {
        stageJar(COMMIT, jar(MAIN, "version=1\njudge=" + COMMIT + "\n", "new"));
        Files.delete(sb.root.resolve("src-" + COMMIT).resolve("kiln-service/target/kiln-service-0.1-all.jar"));
        var out = stageLocal(COMMIT);
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
        assertThat(out.err()).contains("refused: want exactly one ");
    }

    /// D4.1: the settle window defaults to 15 s for kiln. Mutation k17: `${DL_SETTLE:=15}` -> `:=5`.
    @Test
    void settleDefault_is15() throws Exception {
        stageNew();
        sb.env.remove("DL_SETTLE");
        var dry = dry();
        assertThat(dry.rc()).as(dry.err()).isZero();
        assertThat(dry.out()).containsPattern("(?m) health settle kiln 15$");
    }

    // ---------------------------------------------------------------------------------------------- golden

    /// The kiln golden plan, byte for byte and stable across two dry-runs (D3.10).
    @Test
    void goldenPlan_byteForByte_andStable() throws Exception {
        stageNew();
        var first = dry();
        var second = dry();
        assertThat(first.rc()).as(first.err()).isZero();
        assertThat(second.out()).isEqualTo(first.out());
        var manifest = (newSha + "  kiln-service.jar\n");
        var liveManifest = (oldSha + "  kiln-service.jar\n");
        var golden = Files.readString(DeploySandbox.RESOURCES.resolve("plan-kiln.golden")).replace("@HOME@", sb.home.toString())
                          .replace("@COMMIT@", COMMIT).replace("@NEW@", newSha).replace("@OLD@", oldSha)
                          .replace("@MANIFEST@", DeploySandbox.sha256(manifest)).replace("@LIVEMANIFEST@", DeploySandbox.sha256(liveManifest));
        var body = golden.substring(0, golden.indexOf("plan-sha256 "));
        var drift = driftLine("absent") + "\n";  // no installed unit file in the rig: reported before the plan, outside the digest
        assertThat(first.out()).isEqualTo(drift + body + "plan-sha256 " + DeploySandbox.sha256(body) + "\n");
    }
}
