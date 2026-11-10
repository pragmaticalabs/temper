package org.pragmatica.temper.controller.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.config.ControllerConfig.Database;
import org.pragmatica.temper.controller.config.ControllerConfig.Evidence;
import org.pragmatica.temper.controller.config.ControllerConfig.Host;
import org.pragmatica.temper.controller.config.ControllerConfig.Fleet;
import org.pragmatica.temper.controller.config.ControllerConfig.PgDump;
import org.pragmatica.temper.controller.config.ControllerConfig.PortGroup;
import org.pragmatica.temper.controller.config.ControllerConfig.Repo;
import org.pragmatica.temper.controller.config.ControllerConfig.Retention;
import org.pragmatica.temper.controller.config.ControllerConfig.Scheduling;
import org.pragmatica.temper.controller.config.ControllerConfig.Server;
import org.pragmatica.temper.controller.config.ControllerConfig.Units;
import org.pragmatica.temper.controller.config.ControllerConfig.Worker;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.store.api.HostName;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// Parses `controller.toml` (m0-design §6.1). Every failure is fatal at start.
///
/// The version is not configured: it comes from the build ([ControllerVersion]), and a `version` key is refused.
///
/// Required: `[server] port tokenFile`, `[db] host port name user passwordFile`, `[repo] origin mirror`,
/// `[portGroup] modules` (an explicit list; empty disables the port-group refusal, Q-4), `[worker] jar` and at least
/// one `[[hosts]]` with `name address user key knownHosts memoryBudgetMb`. Optional: `[server] readTokenFile` (the
/// read-only token, D3; absent or blank: none; not a string: refused), `[server] workerTokenFile` (the worker token,
/// ruling R-M3-15), `[server] verdictTokenFile` and `[server] macTokenFile` (the verdict and Mac tokens), each under the same rules. Defaults: `[server] bind = "0.0.0.0",
/// instance = "temper"` (F2: a second controller on the same hosts, such as the acceptance one, sets its own),
/// `[scheduler] intervalSeconds = 2, starvationSeconds = 1200, leaseSeconds = 30, hostLostSeconds = 600,
/// retireWindowSeconds = 600` (#107: no new grant from a host's `retireAt` minus the window), `[units] memoryMb = 6144,
/// gateMemoryMb = 16384, cpuShares = 1024, diskFloorGiB = 50, rerunInstallSeconds = 300`, `[portGroup] owner = "temper", maxHolding = 1`
/// (Q-M1-12), `[evidence] root = "~/.local/share/temper/evidence", maxSmallMiB = 256, maxFullMiB = 1024,
/// minFreeGiB = 20` (the byte caps and the free-space floor are [G]: the design names the keys, not the values),
/// `[retention] intervalMinutes = 60`, `[pgDump] at = "03:30"` [G] (local time), `container = "temper-pg"`
/// (`ops/pg-up.sh`), host `cap = 5`, `portIsolated = false`, `sharedCpu = false` (m4-design §9.1) and no `retireAt`
/// (§10: an ISO-8601 instant string, `"2026-11-13T00:00:00Z"`; anything else is refused; `sharedCpu` must be a
/// boolean) and `memFloorGiB = 0` (the memory floor off; a whole number from 0 to [#MAX_MEM_FLOOR_GIB], anything else
/// is refused with the reason). `[github.app] clientId installationId keyFile` is the controller's GitHub App; without it
/// `[github] anonymous = true` must be given, else the start is refused (exit 78, [GitHubIdentity]).
public sealed interface ConfigLoader {
    /// The worker-launch version alphabet (m0-design §6.4): the version travels as an ssh command argument.
    Pattern VERSION = Handshake.VERSION_ALPHABET;
    long GIB = 1024L * 1024L * 1024L;
    long MIB = 1024L * 1024L;
    String EVIDENCE_ROOT = "~/.local/share/temper/evidence";
    /// `[[hosts]] memFloorGiB` above this is refused as a typo rather than read as "never grant" [G]: 1 TiB is well
    /// above the RAM of any build host temper drives.
    int MAX_MEM_FLOOR_GIB = 1024;
    /// `[scheduler] retireWindowSeconds` default: the one value lives in [Scheduling#DEFAULT_RETIRE_WINDOW].
    int RETIRE_WINDOW_SECONDS = (int) Scheduling.DEFAULT_RETIRE_WINDOW.toSeconds();

    /// Every key a `[[hosts]]` entry is read for; any other is warned about at start and ignored.
    Set<String> HOST_KEYS = Set.of("name",
                                   "address",
                                   "user",
                                   "key",
                                   "knownHosts",
                                   "cap",
                                   "memoryBudgetMb",
                                   "portIsolated",
                                   "sharedCpu",
                                   "retireAt",
                                   "memFloorGiB");

    /// Every key the `[fleet]` section is read for; any other is warned about at start and ignored (a misspelt `bbHosts`
    /// would otherwise be an empty set of bb hosts without a word).
    Set<String> FLEET_KEYS = Set.of("kilnUrl",
                                    "stateDir",
                                    "bbHosts",
                                    "bbScanKey",
                                    "bbKnownHosts",
                                    "queueWaitAlarmMin",
                                    "portGroupHeldAlarmMin");

    /// Reads the build's version ([ControllerVersion#bundled]), then parses the file.
    static Result<ControllerConfig> load(Path file, Path home) {
        return ControllerVersion.bundled().flatMap(version -> loaded(file, home, version));
    }

    private static Result<ControllerConfig> loaded(Path file, Path home, String version) {
        return TomlParser.parseFile(file)
                         .mapError(cause -> ConfigError.Unreadable.FACTORY.apply(file.toString(),
                                                                                 cause))
                         .flatMap(doc -> parse(doc, home, version));
    }

    /// Pure: `buildVersion` is the version this controller was built as.
    static Result<ControllerConfig> parse(TomlDocument doc, Path home, String buildVersion) {
        var hosts = hosts(doc, home);
        var evidence = evidence(doc, home);

        return Result.all(version(doc, buildVersion),
                          server(doc, home),
                          database(doc, home),
                          repo(doc, home),
                          scheduling(doc),
                          units(doc),
                          portGroup(doc),
                          worker(doc, home),
                          hosts,
                          evidence,
                          retention(doc),
                          pgDump(doc),
                          Result.all(evidence, hosts).flatMap((root, linked) -> fleet(doc, home, root, linked)),
                          GitHubIdentity.parse(doc, home))
                     .map(ControllerConfig::controllerConfig)
                     .flatMap(config -> GitHubReviewer.parse(doc, home).map(config::withReviewer));
    }

    /// The build's version; a `version` key in the file is refused.
    private static Result<String> version(TomlDocument doc, String buildVersion) {
        return doc.getString("", "version")
                  .map(ControllerVersion::handTyped)
                  .or(() -> success(buildVersion));
    }

    private static Result<Server> server(TomlDocument doc, Path home) {
        return Result.all(success(doc.getString("server", "bind").or("0.0.0.0")),
                          TomlValues.integer(doc, "server", "port").flatMap(port -> TomlValues.between("server",
                                                                                                       "port",
                                                                                                       port,
                                                                                                       1,
                                                                                                       65535)),
                          TomlValues.path(doc, "server", "tokenFile", home),
                          optionalFile(doc, "readTokenFile", home),
                          instance(doc),
                          optionalFile(doc, "workerTokenFile", home),
                          optionalFile(doc, "verdictTokenFile", home),
                          optionalFile(doc, "macTokenFile", home))
                     .map(Server::server);
    }

    /// D3, R-M3-15: optional, like the token it names; blank counts as absent, a non-string value is refused.
    private static Result<Option<Path>> optionalFile(TomlDocument doc, String key, Path home) {
        return TomlValues.optionalText(doc, "server", key).map(raw -> expanded(raw, home));
    }

    private static Option<Path> expanded(Option<String> raw, Path home) {
        return raw.map(text -> TomlValues.expand(text, home));
    }

    private static Result<InstanceId> instance(TomlDocument doc) {
        return doc.getString("server", "instance")
                  .map(ConfigLoader::parsedInstance)
                  .or(() -> success(InstanceId.DEFAULT));
    }

    private static Result<InstanceId> parsedInstance(String raw) {
        return InstanceId.instanceId(raw).mapError(cause -> ConfigError.Invalid.FACTORY.apply("server",
                                                                                              "instance",
                                                                                              cause));
    }

    private static Result<Database> database(TomlDocument doc, Path home) {
        return Result.all(TomlValues.string(doc, "db", "host"),
                          TomlValues.integer(doc, "db", "port").flatMap(port -> TomlValues.between("db",
                                                                                                   "port",
                                                                                                   port,
                                                                                                   1,
                                                                                                   65535)),
                          TomlValues.string(doc, "db", "name"),
                          TomlValues.string(doc, "db", "user"),
                          TomlValues.path(doc, "db", "passwordFile", home))
                     .map(Database::database);
    }

    private static Result<Repo> repo(TomlDocument doc, Path home) {
        return Result.all(TomlValues.string(doc, "repo", "origin").flatMap(origin -> TomlValues.notOption("repo",
                                                                                                          "origin",
                                                                                                          origin)),
                          TomlValues.path(doc, "repo", "mirror", home))
                     .map(Repo::repo);
    }

    private static Result<Scheduling> scheduling(TomlDocument doc) {
        return Result.all(seconds(doc, "intervalSeconds", 2),
                          seconds(doc, "starvationSeconds", 1200),
                          seconds(doc, "leaseSeconds", 30),
                          seconds(doc, "hostLostSeconds", 600),
                          seconds(doc, "retireWindowSeconds", RETIRE_WINDOW_SECONDS))
                     .map(Scheduling::scheduling)
                     .flatMap(ConfigLoader::lostAfterStableUp);
    }

    /// `hostLostSeconds` must exceed the link's `stableUp` (m4-design §2.1, maintainer-approved): the LOST clock starts at
    /// controller start and clears only after an UP of `stableUp`, so with `hostLost ≤ stableUp` a host past `hostLost`
    /// is deferred and its first drop after a start (or after a short reconnect) is LOST at once, spending its units' one
    /// requeue on a flap. The bound is [LinkTiming#DEFAULT]'s `stableUp` itself, never a copy of its value.
    ///
    /// **Necessary, not sufficient:** the clock clears at start + connect time + `stableUp`, so a first drop is still LOST
    /// at once whenever that sum exceeds `hostLost`. A config that passes this check is not thereby safe; `hostLost`
    /// must exceed `stableUp` plus the expected connect time (no margin is added: none was measured).
    private static Result<Scheduling> lostAfterStableUp(Scheduling scheduling) {
        var stableUp = LinkTiming.DEFAULT.stableUp();

        return scheduling.hostLost()
                         .compareTo(stableUp) > 0
               ? success(scheduling)
               : notAboveStableUp(scheduling.hostLost(), stableUp).result();
    }

    /// The refusal states the rule in its own message: `Invalid`'s formatted message names only the section and key,
    /// and the controller logs only the message at a failed start.
    private static ConfigError notAboveStableUp(Duration hostLost, Duration stableUp) {
        var detail = "hostLostSeconds (" + hostLost.toSeconds()
                   + ") must exceed stableUp (" + stableUp.toSeconds()
                   + " s) plus the expected connect time, or a host's first drop after start is LOST at once";

        return new ConfigError.Invalid("scheduler",
                                       "hostLostSeconds",
                                       cause(detail),
                                       "Invalid [scheduler] hostLostSeconds: " + detail);
    }

    private static Result<Duration> seconds(TomlDocument doc, String key, int fallback) {
        return TomlValues.positive("scheduler",
                                   key,
                                   TomlValues.integer(doc, "scheduler", key, fallback))
                         .map(Duration::ofSeconds);
    }

    private static Result<Units> units(TomlDocument doc) {
        return Result.all(memory(doc, "memoryMb", 6144),
                          memory(doc,
                                 "gateMemoryMb",
                                 MemoryMb.GATE_DEFAULT.value()),
                          TomlValues.positive("units",
                                              "cpuShares",
                                              TomlValues.integer(doc, "units", "cpuShares", 1024)),
                          TomlValues.positive("units",
                                              "diskFloorGiB",
                                              TomlValues.integer(doc, "units", "diskFloorGiB", 50))
                                    .map(gib -> gib * GIB),
                          TomlValues.positive("units",
                                              "rerunInstallSeconds",
                                              TomlValues.integer(doc,
                                                                 "units",
                                                                 "rerunInstallSeconds",
                                                                 Math.toIntExact(Units.RERUN_INSTALL_DEFAULT.toSeconds())))
                                    .map(Duration::ofSeconds))
                     .map((memory, gateMemory, cpuShares, floor, install) -> Units.units(memory,
                                                                                         gateMemory,
                                                                                         cpuShares,
                                                                                         floor,
                                                                                         TomlValues.optionalString(doc,
                                                                                                                   "units",
                                                                                                                   "mavenOpts"),
                                                                                         TomlValues.optionalString(doc,
                                                                                                                   "units",
                                                                                                                   "tcHubPrefix"),
                                                                                         install));
    }

    private static Result<MemoryMb> memory(TomlDocument doc, String key, int fallback) {
        return MemoryMb.memoryMb(TomlValues.integer(doc, "units", key, fallback)).mapError(cause -> ConfigError.Invalid.FACTORY.apply("units",
                                                                                                                                      key,
                                                                                                                                      cause));
    }

    private static Result<PortGroup> portGroup(TomlDocument doc) {
        return Result.all(TomlValues.stringList(doc, "portGroup", "modules"),
                          OwnerTag.ownerTag(doc.getString("portGroup", "owner").or("temper")).mapError(cause -> ConfigError.Invalid.FACTORY.apply("portGroup",
                                                                                                                                                  "owner",
                                                                                                                                                  cause)),
                          TomlValues.positive("portGroup",
                                              "maxHolding",
                                              TomlValues.integer(doc, "portGroup", "maxHolding", 1)))
                     .map(PortGroup::portGroup);
    }

    private static Result<Evidence> evidence(TomlDocument doc, Path home) {
        return Result.all(TomlValues.positive("evidence",
                                              "maxSmallMiB",
                                              TomlValues.integer(doc, "evidence", "maxSmallMiB", 256)),
                          TomlValues.positive("evidence",
                                              "maxFullMiB",
                                              TomlValues.integer(doc, "evidence", "maxFullMiB", 1024)),
                          TomlValues.positive("evidence",
                                              "minFreeGiB",
                                              TomlValues.integer(doc, "evidence", "minFreeGiB", 20)))
                     .map((small, full, free) -> Evidence.evidence(TomlValues.expand(doc.getString("evidence", "root")
                                                                                        .or(EVIDENCE_ROOT),
                                                                                     home),
                                                                   small * MIB,
                                                                   full * MIB,
                                                                   free * GIB));
    }

    private static Result<Retention> retention(TomlDocument doc) {
        return TomlValues.positive("retention",
                                   "intervalMinutes",
                                   TomlValues.integer(doc, "retention", "intervalMinutes", 60))
                         .map(minutes -> Retention.retention(Duration.ofMinutes(minutes)));
    }

    /// `[fleet] kilnUrl` (absent: no kiln pull), `stateDir` (default beside the evidence root) and the bb hosts
    /// ([BbHosts]); `linked` = the `[[hosts]]` names, which a bb host may not repeat.
    private static Result<Fleet> fleet(TomlDocument doc, Path home, Evidence evidence, List<Host> linked) {
        warnedFleetKeys(doc);

        return BbHosts.entries(doc,
                               linked.stream().map(host -> host.name()
                                                               .value()).collect(Collectors.toSet()))
                      .flatMap(bbHosts -> Result.all(TomlValues.optionalText(doc, "fleet", "kilnUrl").flatMap(ConfigLoader::kilnUrl),
                                                     TomlValues.optionalText(doc, "fleet", "stateDir").map(raw -> raw.map(text -> TomlValues.expand(text,
                                                                                                                                                    home))
                                                                                                                     .or(Fleet.defaults(evidence).stateDir())),
                                                     success(bbHosts),
                                                     BbHosts.path(doc, "bbScanKey", bbHosts, home),
                                                     BbHosts.path(doc, "bbKnownHosts", bbHosts, home),
                                                     alarmMinutes(doc,
                                                                  "queueWaitAlarmMin",
                                                                  Fleet.DEFAULT_QUEUE_WAIT_ALARM_MIN),
                                                     alarmMinutes(doc,
                                                                  "portGroupHeldAlarmMin",
                                                                  Fleet.DEFAULT_PORTGROUP_HELD_ALARM_MIN))
                                                .map(Fleet::fleet));
    }

    /// `[fleet] queueWaitAlarmMin`, `portGroupHeldAlarmMin`: a whole number from 1 to
    /// [Fleet#MAX_ALARM_MIN]; anything else refuses the start. There is no off switch.
    private static Result<Integer> alarmMinutes(TomlDocument doc, String key, int fallback) {
        return TomlValues.optionalWholeBetween(option(doc.sections().get("fleet")).or(Map.of()),
                                               "fleet",
                                               key,
                                               fallback,
                                               1,
                                               Fleet.MAX_ALARM_MIN);
    }

    private static Unit warnedFleetKeys(TomlDocument doc) {
        option(doc.sections().get("fleet")).onPresent(table -> TomlValues.warnedUnknownKeys(table, "fleet", FLEET_KEYS));

        return Unit.unit();
    }

    private static Result<Option<URI>> kilnUrl(Option<String> raw) {
        return raw.map(text -> Result.lift1(cause -> ConfigError.Invalid.FACTORY.apply("fleet",
                                                                                       "kilnUrl",
                                                                                       Causes.fromThrowable(cause)),
                                            URI::create,
                                            text)
                                     .map(Option::some))
                  .or(() -> Result.success(Option.none()));
    }

    private static Result<PgDump> pgDump(TomlDocument doc) {
        return Result.all(dumpTime(doc.getString("pgDump", "at").or("03:30")),
                          TomlValues.notOption("pgDump",
                                               "container",
                                               doc.getString("pgDump", "container").or("temper-pg")))
                     .map(PgDump::pgDump);
    }

    private static Result<LocalTime> dumpTime(String raw) {
        return DateTime.parseLocalTime(raw).mapError(cause -> ConfigError.Invalid.FACTORY.apply("pgDump", "at", cause));
    }

    private static Result<Worker> worker(TomlDocument doc, Path home) {
        return TomlValues.path(doc, "worker", "jar", home).map(Worker::worker);
    }

    private static Result<List<Host>> hosts(TomlDocument doc, Path home) {
        return doc.getTableArray("hosts")
                  .filter(tables -> !tables.isEmpty())
                  .toResult(ConfigError.Missing.FACTORY.apply("hosts", "name"))
                  .flatMap(tables -> hostList(tables, home));
    }

    private static Result<List<Host>> hostList(List<Map<String, Object>> tables, Path home) {
        return Result.allOf(tables.stream().map(table -> checkedHost(table, home)).toList());
    }

    /// Warns about the entry's unknown keys ([#HOST_KEYS]), then reads it.
    private static Result<Host> checkedHost(Map<String, Object> table, Path home) {
        return host(TomlValues.warnedUnknownKeys(table, "hosts", HOST_KEYS), home);
    }

    private static Result<HostName> hostName(String raw) {
        return HostName.hostName(raw).mapError(cause -> ConfigError.Invalid.FACTORY.apply("hosts", "name", cause));
    }

    private static Result<Host> host(Map<String, Object> table, Path home) {
        return Result.all(TomlValues.string(table, "hosts", "name").flatMap(ConfigLoader::hostName),
                          TomlValues.string(table, "hosts", "address").flatMap(address -> TomlValues.notOption("hosts",
                                                                                                               "address",
                                                                                                               address)),
                          TomlValues.string(table, "hosts", "user").flatMap(user -> TomlValues.notOption("hosts",
                                                                                                         "user",
                                                                                                         user)),
                          TomlValues.path(table, "hosts", "key", home),
                          TomlValues.path(table, "hosts", "knownHosts", home),
                          TomlValues.integer(table,
                                             "hosts",
                                             "cap",
                                             some(5)).flatMap(cap -> TomlValues.positive("hosts", "cap", cap)),
                          TomlValues.integer(table,
                                             "hosts",
                                             "memoryBudgetMb",
                                             none()).flatMap(budget -> TomlValues.positive("hosts",
                                                                                           "memoryBudgetMb",
                                                                                           budget)),
                          TomlValues.strictFlag(table, "hosts", "sharedCpu"),
                          TomlValues.optionalInstant(table, "hosts", "retireAt"),
                          TomlValues.optionalWhole(table, "hosts", "memFloorGiB", 0, MAX_MEM_FLOOR_GIB))
                     .map((name, address, user, key, knownHosts, cap, budget, sharedCpu, retireAt, memFloor) -> Host.host(name,
                                                                                                                          address,
                                                                                                                          user,
                                                                                                                          key,
                                                                                                                          knownHosts,
                                                                                                                          cap,
                                                                                                                          budget,
                                                                                                                          TomlValues.flag(table,
                                                                                                                                          "portIsolated"),
                                                                                                                          sharedCpu,
                                                                                                                          retireAt,
                                                                                                                          memFloor));
    }

    record unused() implements ConfigLoader {}
}
