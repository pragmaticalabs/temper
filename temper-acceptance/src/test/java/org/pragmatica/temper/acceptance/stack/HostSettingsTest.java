package org.pragmatica.temper.acceptance.stack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatNoException;

/// The host tier's guards against the live controller key and home (M1 WP7 verification A2, adapted from its
/// `VerifierHostSettingsTest`),. Everything here runs before, or instead of, any call to a host.
class HostSettingsTest {
    /// D-6: the host tier's controller expects this build's version, `<project.version>+<judge12>` (`+unjudged` for a
    /// plain build), the version the worker jar it installs derives for itself; the bare `project.version` would leave
    /// the installed worker `Incompatible` forever.
    @Test
    void buildVersion_isThisBuildsVersion_asTheWorkerJarDerivesIt() {
        var judge = System.getProperty("temper.judge");
        var suffix = judge.equals("unjudged")
                     ? "unjudged"
                     : judge.substring(0, 12) + (judge.endsWith("-dirty") ? "-dirty" : "");

        assertThat(HostSettings.buildVersion()).isEqualTo(System.getProperty("project.version") + "+" + suffix);
    }

    @Test
    void refuseLive_refusesTheLiveHome_alsoThroughDotDot_aSymbolicLink_orInsideTheLiveTemper(@TempDir Path home) throws Exception {
        var acceptance = home.resolve("temper-acceptance");
        var link = Files.createSymbolicLink(home.resolve("home-link"), home);

        assertThatThrownBy(() -> HostSettings.refuseLive(home, home, acceptance.resolve("k"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> HostSettings.refuseLive(home, home.resolve("x/.."), acceptance.resolve("k"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> HostSettings.refuseLive(home, link, acceptance.resolve("k"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> HostSettings.refuseLive(home, home.resolve(".temper/nested"), acceptance.resolve("k"))).isInstanceOf(AssertionError.class);
        assertThatNoException().isThrownBy(() -> HostSettings.refuseLive(home, acceptance, acceptance.resolve("controller_ed25519")));
    }

    @Test
    void refuseLive_refusesTheLiveKey_andAnyKeyUnderTheLiveTemper_alsoThroughALink(@TempDir Path home) throws Exception {
        var acceptance = Files.createDirectories(home.resolve("temper-acceptance"));
        Files.createDirectories(home.resolve(".temper"));
        var link = Files.createSymbolicLink(acceptance.resolve("keys"), home.resolve(".temper"));

        assertThatThrownBy(() -> HostSettings.refuseLive(home, acceptance, home.resolve(".temper/controller_ed25519")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("live");
        assertThatThrownBy(() -> HostSettings.refuseLive(home, acceptance, home.resolve(".temper/other_key"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> HostSettings.refuseLive(home, acceptance, link.resolve("controller_ed25519"))).isInstanceOf(AssertionError.class);
    }

    /// F5 (adapted from the M1 pre-live verifier's `VerifierHostSettingsTest`, which pinned the gap green): a literal
    /// `~` key, which `ssh -i` expands to the LIVE key, is refused like the expanded path.
    @Test
    void refuseLive_refusesALiteralTildeKey_underTheLiveTemper(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".temper"));
        var acceptance = home.resolve("temper-acceptance");

        assertThatThrownBy(() -> HostSettings.refuseLive(home, acceptance, Path.of("~/.temper/controller_ed25519"))).isInstanceOf(AssertionError.class)
                                                                                                                    .hasMessageContaining("live");
        assertThatThrownBy(() -> HostSettings.refuseLive(home, acceptance, Path.of("~/temper-acceptance/../.temper/k"))).isInstanceOf(AssertionError.class);
        assertThatNoException().isThrownBy(() -> HostSettings.refuseLive(home, acceptance, Path.of("~/temper-acceptance/controller_ed25519")));
        assertThat(HostSettings.expanded(home, Path.of("~"))).isEqualTo(home);
    }

    /// N5 (adopted from the vm1r8 verifier's `VerifierR8HostSettingsTest`, RED there): `ssh -i ~user/…` expands to
    /// that user's home, which `refuseLive` does not resolve, so any leading `~` other than `~` and `~/` is refused.
    @Test
    void refuseLive_refusesATildeUserKey_withTheReason(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".temper"));
        var acceptance = home.resolve("temper-acceptance");

        assertThatThrownBy(() -> HostSettings.refuseLive(home, acceptance, Path.of("~" + System.getProperty("user.name") + "/.temper/controller_ed25519")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("absolute path");
        assertThatThrownBy(() -> HostSettings.refuseLive(home, acceptance, Path.of("~other/temper-acceptance/k"))).isInstanceOf(AssertionError.class);
        assertThatNoException().isThrownBy(() -> HostSettings.refuseLive(home, acceptance, Path.of("~/temper-acceptance/k")));
    }

    @Test
    void fromProperties_refusesTheLiveKey_beforeAnyHostCall() {
        System.setProperty("temper.host.key", System.getProperty("user.home") + "/.temper/controller_ed25519");
        try {
            assertThatThrownBy(HostSettings::fromProperties).isInstanceOf(AssertionError.class);
        } finally {
            System.clearProperty("temper.host.key");
        }
    }

    /// F4 (adapted from the M1 pre-live verifier's `VerifierHostSettingsTest`, which pinned the gap green):
    /// `<workerHome>/.temper/jobs` linked into the live `~/.temper/jobs` passes `refuseLive`, but the routing proof
    /// refuses it before creating the sentinel, so no host call is made, nothing lands in the live jobs dir, and
    /// `install` never reaches the live home.
    @Test
    void installWorker_refusesASymlinkedJobsDir_intoTheLiveHome_beforeAnyHostCall(@TempDir Path dir) throws Exception {
        var home = Files.createDirectories(dir.resolve("home"));
        var liveJobs = Files.createDirectories(home.resolve(".temper/jobs"));
        var acceptance = home.resolve("temper-acceptance");
        Files.createDirectories(acceptance.resolve(".temper"));
        Files.createSymbolicLink(acceptance.resolve(".temper/jobs"), liveJobs);
        var key = acceptance.resolve("controller_ed25519");
        assertThatNoException().isThrownBy(() -> HostSettings.refuseLive(home, acceptance, key));
        var settings = new HostSettings("bigboy", "localhost", "builder", key, home.resolve(".temper/known_hosts"), "0.1.0", home, acceptance);
        var live = new FakeSsh(0, home);
        var jar = Files.writeString(dir.resolve("worker.jar"), "jar");

        assertThatThrownBy(() -> settings.installWorker(jar, live)).isInstanceOf(AssertionError.class).hasMessageContaining("outside");
        assertThat(live.calls).isEmpty();
        assertThat(liveJobs.resolve(HostSettings.ROUTING_JOB)).doesNotExist();
        assertThat(home.resolve(".temper/worker-0.1.0.jar.sha256")).doesNotExist();
    }

    /// N4 (adopted from the vm1r8 verifier's `VerifierR8HostSettingsTest`, RED there): a symbolic link anywhere below
    /// the acceptance `.temper` on the sentinel's path, into the live jobs, is refused before any host call; a key
    /// routing to the live home would otherwise find the sentinel there and the install would reach the live home.
    @Test
    void installWorker_refusesALinkAnywhereOnTheSentinelPath_intoTheLiveHome_beforeAnyHostCall(@TempDir Path dir) throws Exception {
        var sentinel = "jobs/" + HostSettings.ROUTING_JOB + "/units/" + HostSettings.ROUTING_UNIT + "/log.tail";
        for (var link : List.of("jobs/" + HostSettings.ROUTING_JOB + "/units",
                                "jobs/" + HostSettings.ROUTING_JOB + "/units/" + HostSettings.ROUTING_UNIT,
                                sentinel,
                                "jobs/" + HostSettings.ROUTING_JOB)) {
            var root = Files.createDirectories(dir.resolve(String.valueOf(link.length())));
            var home = Files.createDirectories(root.resolve("home"));
            var acceptance = home.resolve("temper-acceptance");
            var liveTarget = home.resolve(".temper").resolve(link);
            Files.createDirectories(liveTarget.getParent());
            if (link.equals(sentinel)) {
                Files.writeString(liveTarget, "live\n");
            } else {
                Files.createDirectories(liveTarget);
            }
            var linkPath = acceptance.resolve(".temper").resolve(link);
            Files.createDirectories(linkPath.getParent());
            Files.createSymbolicLink(linkPath, liveTarget);
            var key = acceptance.resolve("controller_ed25519");
            var settings = new HostSettings("bigboy", "localhost", "builder", key, home.resolve(".temper/known_hosts"), "0.1.0", home, acceptance);
            var live = new FakeSsh(0, home);
            var jar = Files.writeString(root.resolve("worker.jar"), "jar");

            var refused = catchThrowable(() -> settings.installWorker(jar, live));

            assertThat(live.calls).as(link).isEmpty();
            assertThat(home.resolve(".temper").resolve(sentinel)).as(link).satisfiesAnyOf(path -> assertThat(path).doesNotExist(),
                                                                                         path -> assertThat(path).hasContent("live"));
            assertThat(home.resolve(".temper/worker-0.1.0.jar.sha256")).as(link).doesNotExist();
            assertThat(refused).as(link).isInstanceOf(AssertionError.class).hasMessageContaining("symbolic link");
        }
    }

    /// A key routing to the live home answers the sentinel `evidence` with 66 (no such unit there): nothing is
    /// installed, so the live jar is never replaced.
    @Test
    void installWorker_provesTheRoutingFirst_andInstallsNothing_whenTheKeyRoutesElsewhere(@TempDir Path dir) throws Exception {
        var settings = settings(dir);
        var jar = Files.writeString(dir.resolve("worker.jar"), "jar");
        var ssh = new FakeSsh(66, dir.resolve("acc"));

        assertThatThrownBy(() -> settings.installWorker(jar, ssh)).isInstanceOf(AssertionError.class)
                                                                   .hasMessageContaining("nothing was installed");
        assertThat(ssh.calls).extracting(List::getFirst).containsExactly("evidence");
        assertThat(dir.resolve("acc/.temper/jobs/" + HostSettings.ROUTING_JOB)).doesNotExist();
    }

    @Test
    void installWorker_asksForTheSentinelUnit_thenInstalls(@TempDir Path dir) throws Exception {
        var settings = settings(dir);
        var jar = Files.writeString(dir.resolve("worker.jar"), "jar");
        var ssh = new FakeSsh(0, dir.resolve("acc"));

        settings.installWorker(jar, ssh);

        assertThat(ssh.calls).extracting(List::getFirst).containsExactly("evidence", "install");
        assertThat(ssh.calls.getFirst()).containsExactly("evidence", HostSettings.ROUTING_JOB, HostSettings.ROUTING_UNIT, "small");
        assertThat(ssh.sentinelSeen).as("the sentinel unit existed when evidence was asked").isTrue();
        assertThat(dir.resolve("acc/.temper/jobs/" + HostSettings.ROUTING_JOB)).doesNotExist();
    }

    /// A forced command answering `evidence` with `evidenceRc`, and `install` like `worker-launch` in the acceptance
    /// home (it records the sha).
    private static final class FakeSsh implements HostSettings.Ssh {
        private final int evidenceRc;
        private final Path acceptance;
        final List<List<String>> calls = new ArrayList<>();
        boolean sentinelSeen;

        FakeSsh(int evidenceRc, Path acceptance) {
            this.evidenceRc = evidenceRc;
            this.acceptance = acceptance;
        }

        @Override
        public int call(List<String> remote, Option<Path> stdin, Path stderr) throws Exception {
            calls.add(remote);
            if (remote.getFirst().equals("install")) {
                Files.writeString(acceptance.resolve(".temper/worker-" + remote.get(1) + ".jar.sha256"), remote.get(2) + "\n");
                return 0;
            }
            sentinelSeen = Files.isRegularFile(acceptance.resolve(".temper/jobs/" + HostSettings.ROUTING_JOB + "/units/" + HostSettings.ROUTING_UNIT
                                                                  + "/log.tail"));
            Files.writeString(stderr, "worker-launch: no such unit directory\n");
            return evidenceRc;
        }
    }

    private static HostSettings settings(Path dir) throws Exception {
        var home = Files.createDirectories(dir.resolve("home"));
        var acceptance = Files.createDirectories(dir.resolve("acc/.temper")).getParent();
        return new HostSettings("bigboy", "localhost", "builder", acceptance.resolve("controller_ed25519"), home.resolve(".temper/known_hosts"),
                                "0.1.0", home, acceptance);
    }
}
