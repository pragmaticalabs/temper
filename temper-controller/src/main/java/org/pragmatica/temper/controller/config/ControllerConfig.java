package org.pragmatica.temper.controller.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.store.api.HostName;

import static org.pragmatica.lang.Option.none;


/// `~/.config/temper/controller.toml` (m0-design §6.1), parsed. Paths are absolute (a leading `~` is expanded).
/// Secrets are not held here: the tokens and the database password stay in their files until [Secrets] reads them.
///
/// `version` is the controller's build version ([ControllerVersion], `<project.version>+<judge12>`, D-6), which must
/// equal the worker jar version: it is the handshake's expected worker version and the `worker-launch` version. The
/// dedup key's runner component is [#dedupRunner].
public record ControllerConfig(String version,
                               Server server,
                               Database db,
                               Repo repo,
                               Scheduling scheduler,
                               Units units,
                               PortGroup portGroup,
                               Worker worker,
                               List<Host> hosts,
                               Evidence evidence,
                               Retention retention,
                               PgDump pgDump,
                               Fleet fleet,
                               GitHubIdentity github,
                               Option<GitHubReviewer> reviewer) {
    public ControllerConfig {
        hosts = List.copyOf(hosts);
    }

    /// The same configuration with the reviewer the file named (`[github.reviewer]`).
    public ControllerConfig withReviewer(Option<GitHubReviewer> named) {
        return new ControllerConfig(version,
                                    server,
                                    db,
                                    repo,
                                    scheduler,
                                    units,
                                    portGroup,
                                    worker,
                                    hosts,
                                    evidence,
                                    retention,
                                    pgDump,
                                    fleet,
                                    github,
                                    named);
    }

    /// The dedup key's runner component (owner ruling D-6 A1): the whole build version, judge and suffix included, so a
    /// verdict of another temper build is never reused. Each build is its own dedup space: after a deploy, each
    /// re-requested (commit, recipe) runs once more. A `-dirty` or `+unjudged` build differs from the clean build of its
    /// commit in this string, so it never shares a key with it; two `+unjudged` builds of one `project.version` (dev and
    /// test builds, which no deploy installs) do. The docker tag stays the bare `project.version`; the supersede key,
    /// `(ref, recipe)`, has no runner component at all.
    public String dedupRunner() {
        return version;
    }

    public static ControllerConfig controllerConfig(String version,
                                                    Server server,
                                                    Database db,
                                                    Repo repo,
                                                    Scheduling scheduler,
                                                    Units units,
                                                    PortGroup portGroup,
                                                    Worker worker,
                                                    List<Host> hosts,
                                                    Evidence evidence,
                                                    Retention retention,
                                                    PgDump pgDump) {
        return controllerConfig(version,
                                server,
                                db,
                                repo,
                                scheduler,
                                units,
                                portGroup,
                                worker,
                                hosts,
                                evidence,
                                retention,
                                pgDump,
                                Fleet.defaults(evidence));
    }

    public static ControllerConfig controllerConfig(String version,
                                                    Server server,
                                                    Database db,
                                                    Repo repo,
                                                    Scheduling scheduler,
                                                    Units units,
                                                    PortGroup portGroup,
                                                    Worker worker,
                                                    List<Host> hosts,
                                                    Evidence evidence,
                                                    Retention retention,
                                                    PgDump pgDump,
                                                    Fleet fleet) {
        return controllerConfig(version,
                                server,
                                db,
                                repo,
                                scheduler,
                                units,
                                portGroup,
                                worker,
                                hosts,
                                evidence,
                                retention,
                                pgDump,
                                fleet,
                                GitHubIdentity.Anonymous.INSTANCE);
    }

    /// With the GitHub identity the config file named; the shorter forms are for tests that build a controller
    /// without a config file and read GitHub anonymously.
    public static ControllerConfig controllerConfig(String version,
                                                    Server server,
                                                    Database db,
                                                    Repo repo,
                                                    Scheduling scheduler,
                                                    Units units,
                                                    PortGroup portGroup,
                                                    Worker worker,
                                                    List<Host> hosts,
                                                    Evidence evidence,
                                                    Retention retention,
                                                    PgDump pgDump,
                                                    Fleet fleet,
                                                    GitHubIdentity github) {
        return new ControllerConfig(version,
                                    server,
                                    db,
                                    repo,
                                    scheduler,
                                    units,
                                    portGroup,
                                    worker,
                                    hosts,
                                    evidence,
                                    retention,
                                    pgDump,
                                    fleet,
                                    github,
                                    Option.none());
    }

    /// `[server]`. `bind` is recorded but cannot be honoured: rc4 `http-server` binds every interface (Q-1 ruling:
    /// bind all, bearer token on every route). `readTokenFile` optionally names the read token, which may call the READ
    /// routes only (D3); absent, there is no read token. `workerTokenFile` optionally names the worker token (ruling
    /// R-M3-15), which may call the READ routes and claim a task; absent, there is none. `verdictTokenFile` and
    /// `macTokenFile` optionally name the verdict and Mac tokens; absent, there is none, and
    /// every VERDICT or MAC route refuses every caller. `instance` labels this controller's unit containers; reconcile
    /// ignores containers labelled for another instance (M1 WP7 verification, F2).
    public record Server(String bind,
                         int port,
                         Path tokenFile,
                         Option<Path> readTokenFile,
                         InstanceId instance,
                         Option<Path> workerTokenFile,
                         Option<Path> verdictTokenFile,
                         Option<Path> macTokenFile) {
        /// Without a worker, verdict or Mac token.
        public static Server server(String bind,
                                    int port,
                                    Path tokenFile,
                                    Option<Path> readTokenFile,
                                    InstanceId instance) {
            return new Server(bind,
                              port,
                              tokenFile,
                              readTokenFile,
                              instance,
                              Option.none(),
                              Option.none(),
                              Option.none());
        }

        /// Without a verdict or Mac token.
        public static Server server(String bind,
                                    int port,
                                    Path tokenFile,
                                    Option<Path> readTokenFile,
                                    InstanceId instance,
                                    Option<Path> workerTokenFile) {
            return new Server(bind,
                              port,
                              tokenFile,
                              readTokenFile,
                              instance,
                              workerTokenFile,
                              Option.none(),
                              Option.none());
        }

        public static Server server(String bind,
                                    int port,
                                    Path tokenFile,
                                    Option<Path> readTokenFile,
                                    InstanceId instance,
                                    Option<Path> workerTokenFile,
                                    Option<Path> verdictTokenFile,
                                    Option<Path> macTokenFile) {
            return new Server(bind,
                              port,
                              tokenFile,
                              readTokenFile,
                              instance,
                              workerTokenFile,
                              verdictTokenFile,
                              macTokenFile);
        }
    }

    /// `[db]`; the password is read from `passwordFile`.
    public record Database(String host, int port, String name, String user, Path passwordFile) {
        public static Database database(String host, int port, String name, String user, Path passwordFile) {
            return new Database(host, port, name, user, passwordFile);
        }
    }

    /// `[repo]`: the git origin and the controller's own bare mirror of it.
    public record Repo(String origin, Path mirror) {
        public static Repo repo(String origin, Path mirror) {
            return new Repo(origin, mirror);
        }
    }

    /// `[scheduler]`: tick interval, the starvation bound (spec §6.1, 1200 s), the grant lease and `hostLost`, how long
    /// a host's link may stay down before the host is LOST (m4-design §2.1, R-M4-1: 600 s). `retireWindow` (#107,
    /// #111): the scheduler decides no new grant to a host from its `retireAt` minus this window on (by the
    /// controller's clock), and the database refuses the COMMIT of any grant to it from the same instant on (by the
    /// database clock read at COMMIT, [Host#grantFence]), however long the store call has run. So every grant to the
    /// host that lands has committed before `retireAt - retireWindow` by the database clock, and from `retireAt` on the
    /// host's active set can only shrink (the link's drained check is monotone), provided the database clock lags the
    /// controller's by less than the window (bigboy: one clock). A grant decided in time whose COMMIT comes too late
    /// fails whole: its units stay QUEUED and another host may take them. It does not bound grant-to-start: a GRANTED
    /// unit is active, so a slow start never retires the host under it. Global, not per host.
    public record Scheduling(Duration interval,
                             Duration starvation,
                             Duration lease,
                             Duration hostLost,
                             Duration retireWindow) {
        public static final Duration DEFAULT_RETIRE_WINDOW = Duration.ofMinutes(10);

        public static Scheduling scheduling(Duration interval, Duration starvation, Duration lease, Duration hostLost) {
            return scheduling(interval, starvation, lease, hostLost, DEFAULT_RETIRE_WINDOW);
        }

        public static Scheduling scheduling(Duration interval,
                                            Duration starvation,
                                            Duration lease,
                                            Duration hostLost,
                                            Duration retireWindow) {
            return new Scheduling(interval, starvation, lease, hostLost, retireWindow);
        }
    }

    /// `[units]`: per-unit container limits, the Gate unit's memory limit (`gateMemoryMb`, Q-M1-13), the optional
    /// container environment values (R-ENV allow-list) and the rerun bound's install allowance
    /// (`rerunInstallSeconds`).
    public record Units(MemoryMb memory,
                        MemoryMb gateMemory,
                        int cpuShares,
                        long diskFloorBytes,
                        Option<String> mavenOpts,
                        Option<String> tcHubPrefix,
                        Duration rerunInstallAllowance) {
        /// The rerun bound's install allowance when `[units] rerunInstallSeconds` is not set: 5 min [G] covers a cold
        /// module chain (M-1 measured ~1.5 min for `-pl aether/ember -am install` on a seeded repository).
        public static final Duration RERUN_INSTALL_DEFAULT = Duration.ofMinutes(5);

        public static Units units(MemoryMb memory,
                                  MemoryMb gateMemory,
                                  int cpuShares,
                                  long diskFloorBytes,
                                  Option<String> mavenOpts,
                                  Option<String> tcHubPrefix,
                                  Duration rerunInstallAllowance) {
            return new Units(memory,
                             gateMemory,
                             cpuShares,
                             diskFloorBytes,
                             mavenOpts,
                             tcHubPrefix,
                             rerunInstallAllowance);
        }
    }

    /// `[portGroup]` (m1-design §1.3, Q-M1-12): the modules whose tests need the port group (Q-4), the bb-hold owner
    /// tag temper holds the group as, and how many temper units may wait for or hold the group per host at once.
    public record PortGroup(List<String> modules, OwnerTag owner, int maxHolding) {
        public PortGroup {
            modules = List.copyOf(modules);
        }

        public static PortGroup portGroup(List<String> modules, OwnerTag owner, int maxHolding) {
            return new PortGroup(modules, owner, maxHolding);
        }
    }

    /// `[evidence]` (m1-design §3.1, §3.2): where pulled tarballs, `result.json` files and `pg_dump` files live, the
    /// byte caps of the `small` and `full` pulls, and the free space below which the `full` pull is skipped.
    public record Evidence(Path root, long maxSmallBytes, long maxFullBytes, long minFreeBytes) {
        public static Evidence evidence(Path root, long maxSmallBytes, long maxFullBytes, long minFreeBytes) {
            return new Evidence(root, maxSmallBytes, maxFullBytes, minFreeBytes);
        }
    }

    /// `[retention]` (m1-design §4.2): how often the retention GC (and the `result.json` sweep) runs.
    public record Retention(Duration interval) {
        public static Retention retention(Duration interval) {
            return new Retention(interval);
        }
    }

    /// `[fleet]`: `kilnUrl`, the kiln whose `GET /api/v1/changes` the
    /// controller pulls every 5 s (loopback `http://127.0.0.1:7171` on bigboy; the pull is off when the key is absent),
    /// `stateDir`, where the epoch's high-water file `fleet-hwm` lives (default: `temper-state` beside the evidence
    /// root, outside anything retention deletes), and `bbHosts` with the scan key and known_hosts that read the `=`
    /// entries: the bb hosts the controller expects besides its `[[hosts]]`. `queueWaitAlarmMin` and
    /// `portGroupStallAlarmMin` and `portGroupHeldAgeAlarmMin` (defaults 60, 30 and 360, each 1..10080) are the
    /// limits of the `queue-wait`, `portgroup-held` (no progress of a running group=1 ticket) and `portgroup-long`
    /// (its age, whatever the progress) alarms of the bb hosts collected. The former `portGroupHeldAlarmMin` is still
    /// accepted in the file, warned about and ignored.
    public record Fleet(Option<URI> kilnUrl,
                        Path stateDir,
                        List<BbHost> bbHosts,
                        Option<Path> bbScanKey,
                        Option<Path> bbKnownHosts,
                        int queueWaitAlarmMin,
                        int portGroupStallAlarmMin,
                        int portGroupHeldAgeAlarmMin,
                        int laneStaleMin) {
        public static final int DEFAULT_QUEUE_WAIT_ALARM_MIN = 60;
        /// `laneStaleMin`: a LEASED task with no heartbeat for more than this many minutes shows `stale` (lane registry).
        public static final int DEFAULT_LANE_STALE_MIN = 20;
        public static final int DEFAULT_PORTGROUP_STALL_ALARM_MIN = 30;
        public static final int DEFAULT_PORTGROUP_HELD_AGE_ALARM_MIN = 360;
        public static final int MAX_ALARM_MIN = 10080;

        public Fleet {
            bbHosts = List.copyOf(bbHosts);
        }

        /// Without bb hosts.
        public static Fleet fleet(Option<URI> kilnUrl, Path stateDir) {
            return fleet(kilnUrl, stateDir, List.of(), Option.none(), Option.none());
        }

        public static Fleet fleet(Option<URI> kilnUrl,
                                  Path stateDir,
                                  List<BbHost> bbHosts,
                                  Option<Path> bbScanKey,
                                  Option<Path> bbKnownHosts) {
            return fleet(kilnUrl,
                         stateDir,
                         bbHosts,
                         bbScanKey,
                         bbKnownHosts,
                         DEFAULT_QUEUE_WAIT_ALARM_MIN,
                         DEFAULT_PORTGROUP_STALL_ALARM_MIN,
                         DEFAULT_PORTGROUP_HELD_AGE_ALARM_MIN);
        }

        public static Fleet fleet(Option<URI> kilnUrl,
                                  Path stateDir,
                                  List<BbHost> bbHosts,
                                  Option<Path> bbScanKey,
                                  Option<Path> bbKnownHosts,
                                  int queueWaitAlarmMin,
                                  int portGroupStallAlarmMin,
                                  int portGroupHeldAgeAlarmMin) {
            return fleet(kilnUrl,
                         stateDir,
                         bbHosts,
                         bbScanKey,
                         bbKnownHosts,
                         queueWaitAlarmMin,
                         portGroupStallAlarmMin,
                         portGroupHeldAgeAlarmMin,
                         DEFAULT_LANE_STALE_MIN);
        }

        public static Fleet fleet(Option<URI> kilnUrl,
                                  Path stateDir,
                                  List<BbHost> bbHosts,
                                  Option<Path> bbScanKey,
                                  Option<Path> bbKnownHosts,
                                  int queueWaitAlarmMin,
                                  int portGroupStallAlarmMin,
                                  int portGroupHeldAgeAlarmMin,
                                  int laneStaleMin) {
            return new Fleet(kilnUrl,
                             stateDir,
                             bbHosts,
                             bbScanKey,
                             bbKnownHosts,
                             queueWaitAlarmMin,
                             portGroupStallAlarmMin,
                             portGroupHeldAgeAlarmMin,
                             laneStaleMin);
        }

        /// No kiln pull and no bb hosts; the state directory beside the evidence root.
        public static Fleet defaults(Evidence evidence) {
            return fleet(Option.none(),
                         evidence.root().resolveSibling("temper-state"));
        }
    }

    /// One `[fleet] bbHosts` entry: a bb host that is not a `[[hosts]]` host. The bare form has
    /// no link (`NOT-COLLECTED`); the `<host>=<user>@<address>` form is read over the kiln scan key.
    public record BbHost(String name, Option<BbLink> link) {
        public static BbHost bbHost(String name, Option<BbLink> link) {
            return new BbHost(name, link);
        }
    }

    /// The `user@address` of a `bbHosts` entry in the `=` form.
    public record BbLink(String user, String address) {
        public static BbLink bbLink(String user, String address) {
            return new BbLink(user, address);
        }
    }

    /// `[pgDump]` (m1-design §3.4): the local time of the daily dump and the Postgres container it runs in.
    public record PgDump(LocalTime at, String container) {
        public static PgDump pgDump(LocalTime at, String container) {
            return new PgDump(at, container);
        }
    }

    /// `[worker]`: the worker jar the controller deploys through the `install` verb (m0-design §6.4). Not in the
    /// §6.1 sketch; the deploy flow needs a local copy of the jar.
    public record Worker(Path jar) {
        public static Worker worker(Path jar) {
            return new Worker(jar);
        }
    }

    /// One `[[hosts]]` entry: how to reach the worker over ssh and the host's grant limits. `sharedCpu` (m4-design §9.1):
    /// the host never gets a `--dedicated-cpu` job's unit. `retireAt` (§10): from that instant on the scheduler grants
    /// nothing there; running units finish. Both change by editing the config and restarting (F8). `memFloorGiB`: a unit
    /// is granted only while the host's MemAvailable, less the memory of temper's units it does not reflect yet and the
    /// unit's own, stays at or above it (`Fit.MemFloor`); 0 is off.
    public record Host(HostName name,
                       String address,
                       String user,
                       Path key,
                       Path knownHosts,
                       int cap,
                       int memoryBudgetMb,
                       boolean portIsolated,
                       boolean sharedCpu,
                       Option<Instant> retireAt,
                       int memFloorGiB) {
        /// A dedicated-CPU host that is never retired.
        public static Host host(HostName name,
                                String address,
                                String user,
                                Path key,
                                Path knownHosts,
                                int cap,
                                int memoryBudgetMb,
                                boolean portIsolated) {
            return host(name, address, user, key, knownHosts, cap, memoryBudgetMb, portIsolated, false, none());
        }

        public static Host host(HostName name,
                                String address,
                                String user,
                                Path key,
                                Path knownHosts,
                                int cap,
                                int memoryBudgetMb,
                                boolean portIsolated,
                                boolean sharedCpu,
                                Option<Instant> retireAt) {
            return host(name, address, user, key, knownHosts, cap, memoryBudgetMb, portIsolated, sharedCpu, retireAt, 0);
        }

        public static Host host(HostName name,
                                String address,
                                String user,
                                Path key,
                                Path knownHosts,
                                int cap,
                                int memoryBudgetMb,
                                boolean portIsolated,
                                boolean sharedCpu,
                                Option<Instant> retireAt,
                                int memFloorGiB) {
            return new Host(name,
                            address,
                            user,
                            key,
                            knownHosts,
                            cap,
                            memoryBudgetMb,
                            portIsolated,
                            sharedCpu,
                            retireAt,
                            memFloorGiB);
        }

        /// The host's `retireAt` when it takes no new grants at `now`, that is when `now` is at or after it (m4-design
        /// §10); none while it still takes grants.
        public Option<Instant> retiredSince(Instant now) {
            return retireAt.filter(at -> !now.isBefore(at));
        }

        /// The host's `retireAt` when it takes no NEW grant at `now`: `now` is at or after `retireAt` minus `window`
        /// (#107). Work already on the host is not touched; the retired-and-drained link stop keeps [#retiredSince].
        public Option<Instant> refusesGrantsSince(Instant now, Duration window) {
            return retireAt.filter(at -> !now.isBefore(at.minus(window)));
        }

        /// The instant from which no grant to this host may COMMIT (#111), judged by the database clock at COMMIT:
        /// `retireAt` minus `window`, where [#refusesGrantsSince] starts refusing by the scheduler's clock. None
        /// without `retireAt`: the store then sets the explicit sentinel `none` ("unfenced, deliberately"); the database
        /// refuses a grant commit that sets no fence at all (fails closed).
        public Option<Instant> grantFence(Duration window) {
            return retireAt.map(at -> at.minus(window));
        }
    }
}
