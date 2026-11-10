package org.pragmatica.kiln.service;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.scan.ScanTransport;
import org.pragmatica.kiln.service.scan.SshScanTransport;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.web.Periods;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.vo.Url;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// [Kiln#assemble]'s wiring, without I/O (WP9 open items 3, 7, 11).
///
/// - One [org.pragmatica.kiln.service.scan.ScanHost] per configured host, the same instance in its scanner and in the
///   derivation, so the host's serial is shared by every loop (the store's one-pass-per-host precondition).
/// - The deadlines and timeouts come from the config: the GitHub and temper cycle deadlines, the scan step deadline, and
///   the ssh transport's timeout ([Main#ports]).
/// - The loops: the GitHub, mirror, temper and retention loops, and per host its hot and size loops and its daily full
///   pass; the Mac's period is [Periods#MAC_PUSH].
class KilnWiringTest {
    private static final HostName BIGBOY = HostName.hostName("bigboy").unwrap();
    private static final HostName OTHER = HostName.hostName("other").unwrap();

    record NamedTransport(HostName host) implements ScanTransport {
        @Override
        public Promise<List<String>> scan(List<String> roots, List<CheckoutPath> full) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<List<String>> reach(Set<Sha> shas) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<List<String>> size(String root, CheckoutPath path) {
            return Promise.success(List.of());
        }
    }

    static KilnConfig config(List<KilnConfig.Host> hosts) {
        return KilnConfig.kilnConfig(KilnConfig.Server.server(7171),
                                     KilnConfig.Database.database("127.0.0.1",
                                                                  55442,
                                                                  "kiln",
                                                                  "kiln",
                                                                  Path.of("/p"),
                                                                  Path.of("/hwm")),
                                     KilnConfig.GitHub.gitHub("o/r",
                                                              List.of(BranchName.branchName("main").unwrap()),
                                                              none(),
                                                              Duration.ofSeconds(301)),
                                     KilnConfig.Mirror.mirror("https://github.com/o/r.git", Path.of("/m.git")),
                                     KilnConfig.Temper.temper(Url.url("http://127.0.0.1:7070").unwrap(),
                                                              Path.of("/t"),
                                                              Duration.ofSeconds(302)),
                                     KilnConfig.Scan.scan(Duration.ofSeconds(90),
                                                          Duration.ofHours(48),
                                                          LocalTime.of(4, 0),
                                                          Duration.ofSeconds(7),
                                                          Duration.ofHours(48),
                                                          Duration.ofSeconds(500),
                                                          Duration.ofSeconds(249)),
                                     KilnConfig.Mac.mac(List.of("~/x")),
                                     hosts);
    }

    static KilnConfig.Host host(HostName name) {
        return KilnConfig.Host.host(name, "127.0.0.1", "builder", Path.of("/k"), Path.of("/kh"), List.of("~/work/co/*"));
    }

    /// Every store part is null: assembling builds, it reads nothing.
    static KilnStore nothing() {
        return (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(),
                                                  new Class<?>[]{KilnStore.class},
                                                  (_, _, _) -> null);
    }

    private static Kiln assembled(KilnConfig config) {
        return assembled(config, nothing());
    }

    private static Kiln assembled(KilnConfig config, KilnStore store) {
        var ports = Kiln.Ports.ports(null, null, null, host -> new NamedTransport(host.name()), Instant::now);

        return Kiln.assemble(config, store, ports).unwrap();
    }

    @Test
    void assemble_sharesOneScanHostPerHost_betweenItsScannerAndTheDerivation() {
        var kiln = assembled(config(List.of(host(BIGBOY), host(OTHER))));

        assertThat(kiln.scanners()).hasSize(2);
        assertThat(kiln.derive().hosts()).hasSize(2);
        for (var index = 0; index < 2; index++) {
            assertThat(kiln.scanners().get(index).host()).isSameAs(kiln.derive().hosts().get(index));
        }

        assertThat(kiln.scanners()).extracting(scanner -> scanner.host()
                                                                 .transport())
                  .containsExactly(new NamedTransport(BIGBOY),
                                   new NamedTransport(OTHER));
    }

    @Test
    void assemble_takesTheDeadlinesFromTheConfig() {
        var kiln = assembled(config(List.of(host(BIGBOY))));

        assertThat(kiln.github().deadline()).isEqualTo(Duration.ofSeconds(301));
        assertThat(kiln.temper().deadline()).isEqualTo(Duration.ofSeconds(302));
        assertThat(kiln.scanners().getFirst().stepDeadline()).isEqualTo(Duration.ofSeconds(500));
        assertThat(kiln.github().bases()).extracting(BranchName::value).containsExactly("main");
    }

    @Test
    void ports_giveEachHostAnSshTransport_withTheConfiguredTimeout() {
        var config = config(List.of(host(BIGBOY)));
        var settings = new Main.Settings(config,
                                         org.pragmatica.kiln.service.github.Credential.token("tok"),
                                         "read",
                                         DbConfig.dbConfig("h", 1, "n", "u", "p", 1).unwrap());
        var transport = Main.ports(settings).unwrap().transports().apply(host(BIGBOY));

        assertThat(transport).isInstanceOf(SshScanTransport.class);
        assertThat(((SshScanTransport) transport).timeout()).isEqualTo(TimeSpan.fromDuration(Duration.ofSeconds(249)));
    }

    @Test
    void loops_coverEverySourceAndHost_atTheirPeriods() {
        var kiln = assembled(config(List.of(host(BIGBOY))));

        assertThat(kiln.loops()).extracting(loop -> loop.name() + "@" + loop.period())
                  .containsExactly("github@PT1M",
                                   "mirror@PT1M",
                                   "temper@PT1M",
                                   "hosts@PT1M",
                                   "pipeline@PT10M",
                                   "retention@PT24H",
                                   "hot:bigboy@PT1M30S",
                                   "size:bigboy@PT7S");
        assertThat(kiln.daily()).extracting(daily -> daily.name()).containsExactly("full:bigboy");
        assertThat(kiln.periods()).isEqualTo(new Periods(Duration.ofSeconds(60),
                                                         Duration.ofSeconds(60),
                                                         Duration.ofSeconds(60),
                                                         Duration.ofSeconds(90),
                                                         Duration.ofHours(2)));
        assertThat(kiln.configuredPeriods()).isEqualTo(new Periods(Duration.ofSeconds(60),
                                                                   Duration.ofSeconds(60),
                                                                   Duration.ofSeconds(60),
                                                                   Duration.ofSeconds(90),
                                                                   Duration.ofHours(2)));
        assertThat(kiln.routes().expected()).extracting(source -> source.key())
                  .containsExactly("github", "mirror", "temper", "hosts", "host:bigboy", "mac");
    }

    /// PR #108 review F2: a config without `[conflicts]` wires no conflict watch (no board, so no git process and no
    /// events.log); with it, the watch is wired. Assembling starts nothing either way: the watch steps on a mirror fetch.
    @Test
    void conflictWatch_isWiredOnlyWithItsSection() {
        var off = config(List.of());
        var on = off.withConflicts(some(KilnConfig.Conflicts.conflicts(BranchName.branchName("main").unwrap(),
                                                                       Path.of("/c/events.log"),
                                                                       Path.of("/c/scratch"))));

        assertThat(assembled(off).conflictBoard().isEmpty()).isTrue();
        assertThat(assembled(on, parts()).conflictBoard().isPresent()).isTrue();
    }

    /// Every store part a proxy that answers null: `store.prs()::all` needs a receiver, nothing is called.
    static KilnStore parts() {
        return (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(),
                                                  new Class<?>[]{KilnStore.class},
                                                  (_, method, _) -> method.getReturnType()
                                                                          .isInterface()
                                                                    ? Proxy.newProxyInstance(KilnStore.class.getClassLoader(),
                                                                                             new Class<?>[]{method.getReturnType()},
                                                                                             (_, _, _) -> null)
                                                                    : null);
    }

    @Test
    void budget_isAbsent_untilGitHubStatesIt() {
        assertThat(assembled(config(List.of())).budget().isEmpty()).isTrue();
    }
}
