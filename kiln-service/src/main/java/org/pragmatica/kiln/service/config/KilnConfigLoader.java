package org.pragmatica.kiln.service.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.service.config.KilnConfig.Conflicts;
import org.pragmatica.kiln.service.config.KilnConfig.Database;
import org.pragmatica.kiln.service.config.KilnConfig.GitHub;
import org.pragmatica.kiln.service.config.KilnConfig.Host;
import org.pragmatica.kiln.service.config.KilnConfig.Mirror;
import org.pragmatica.kiln.service.config.KilnConfig.Repo;
import org.pragmatica.kiln.service.config.KilnConfig.Scan;
import org.pragmatica.kiln.service.config.KilnConfig.Server;
import org.pragmatica.kiln.service.config.KilnConfig.Temper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.vo.Url;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;


/// Parses `kiln.toml` (kiln-f1-design §6 row "Config"). Every failure is fatal at start: a key of the wrong type is
/// refused, never read as its default.
///
/// Fail closed: a section, a `[[hosts]]` key or a section key kiln does not read is refused, and so is every top-level
/// key, an inline table (`server = { ... }`) included, so no spelling reaches a section around its rules.
///
/// Required: `[db] host port name user passwordFile`, `[github] repo bases` (`repo` is `owner/name`, each part
/// `[A-Za-z0-9._-]+` not starting with `-` or `.`; `bases` a non-empty list of distinct branch names), `[mirror] origin`,
/// `[temper] url readTokenFile` (`url` http or https, its port, if any, in 1..65535), and per `[[hosts]]`
/// table `name address user key knownHosts roots`. Optional: `[github] tokenFile` (absent or blank: GitHub is read
/// unauthenticated, Q-7), `[github] hourlyCap` and `reserve` (positive integers, both default 1500: kiln's own requests
/// per GitHub rate window, and the remaining count it never spends at or below), and `[[repos]]` tables, each `repo bases` as in `[github]` (CI interface T1 WP2: read for
/// the change feed only; a repository listed twice is refused). Defaults [guess]: `[server] port = 7171`, `[mirror] path = "~/.local/share/kiln/mirror.git"`, `[db] highWaterFile = "~/.local/share/kiln/change-hwm"`
/// (spec §5.1), `[scan] hotSeconds = 120, hotWindowHours = 48, fullAt = "04:00", sizeGapSeconds = 5,
/// staleAfterHours = 48, stepDeadlineSeconds = 600, transportTimeoutSeconds = 240` (spec §5.3, §5.5), `[github]` and
/// `[temper] cycleDeadlineSeconds = 600`, `[mac] roots = []`. The transport timeout must stay below half the step
/// deadline ([#transportBelowHalfStep]). No `[[hosts]]` table is allowed (nothing is scanned); two hosts with one
/// name are refused. `[server] bind` (owner 2026-11-05) is optional: one literal IPv4 address served as well as the loopback
/// address, on the same port, for reads from the LAN (writes stay loopback-only, Q-1); `[server] hostNames` are the extra
/// `Host` names it answers to. A wildcard, a loopback address, anything but a literal IPv4 address, and `hostNames`
/// without `bind` are refused with exit 78 ([ConfigError.Listen]); an accepted but ignored `bind` is the defect Q-1 found
/// in temper. Every path is `/abs...` or `~/...` ([TomlValues#absolute]).
///
/// A host root (Q-3) is `/abs/...` or `~/...` built from an allowlist only: `[A-Za-z0-9._@+-]`, `/` and `*` (roots
/// travel as words of the forced command, so no other glob, shell metacharacter, quote, whitespace or non-ASCII
/// character can reach it). Split on `/`, no component is empty, `.` or `..`; `*` may appear in any component after the
/// first and matches one component; `**`, and a `*` in the first component (`/*`, `/a*`, `~/*`), are refused
/// (kiln-f1-design §3.2, §9.2). `kiln-scan` checks the same rules on the host; this refuses a bad root at start rather
/// than at the first scan.
public sealed interface KilnConfigLoader {
    Pattern REPO = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._-]*/[A-Za-z0-9_][A-Za-z0-9._-]*$");
    /// A canonical dotted quad: four 1-3 digit octets with no leading zero (`010` is octal to some parsers).
    Pattern IPV4 = Pattern.compile("^(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}$");
    Pattern HOST_NAME = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$");
    Pattern HTTP_URL = Pattern.compile("^https?://.+$");

    Pattern ROOT = Pattern.compile("^(?:/|~/)[A-Za-z0-9._@+*/-]+$");

    String HOSTS = "hosts";
    String APP = "github.app";
    String CONFLICTS = "conflicts";
    /// `[[repos]]` (CI interface T1 WP2): each further repository the change feed reads, `repo` and `bases` as in
    /// `[github]`.
    String REPOS = "repos";
    Set<String> REPO_KEYS = Set.of("repo", "bases");

    /// The sections kiln reads and their keys.
    Map<String, Set<String>> SECTIONS = Map.of("server",
                                               Set.of("port", "bind", "hostNames"),
                                               "db",
                                               Set.of("host", "port", "name", "user", "passwordFile", "highWaterFile"),
                                               "github",
                                               Set.of("repo",
                                                      "bases",
                                                      "tokenFile",
                                                      "anonymous",
                                                      "cycleDeadlineSeconds",
                                                      "hourlyCap",
                                                      "reserve"),
                                               "github.app",
                                               Set.of("clientId", "installationId", "keyFile"),
                                               "mirror",
                                               Set.of("origin", "path"),
                                               "temper",
                                               Set.of("url", "readTokenFile", "cycleDeadlineSeconds"),
                                               "scan",
                                               Set.of("hotSeconds",
                                                      "hotWindowHours",
                                                      "fullAt",
                                                      "sizeGapSeconds",
                                                      "staleAfterHours",
                                                      "stepDeadlineSeconds",
                                                      "transportTimeoutSeconds"),
                                               "mac",
                                               Set.of("roots"),
                                               "conflicts",
                                               Set.of("branch", "eventsLog", "scratch"));

    Set<String> HOST_KEYS = Set.of("name", "address", "user", "key", "knownHosts", "roots");
    int DEFAULT_PORT = 7171;
    /// The GitHub and temper cycle deadlines' default: the pollers' own (WP6).
    int CYCLE_DEADLINE_SECONDS = 600;
    /// The scan step deadline's default: `HostScanner.STEP_DEADLINE` (WP8).
    int STEP_DEADLINE_SECONDS = 600;
    /// The ssh transport timeout's default: below half the step deadline, with a minute to spare for the store writes.
    int TRANSPORT_TIMEOUT_SECONDS = 240;
    String MIRROR_PATH = "~/.local/share/kiln/mirror.git";
    /// `[db] highWaterFile` when absent: next to the mirror, in kiln's own state directory.
    String HIGH_WATER_FILE = "~/.local/share/kiln/change-hwm";
    /// `[conflicts] eventsLog` when absent (temper #100): next to the merge runner's events.log.
    String CONFLICT_EVENTS = "~/temper/conflicts/events.log";
    /// `[conflicts] scratch` when absent: in kiln's own state directory, beside the mirror.
    String CONFLICT_SCRATCH = "~/.local/share/kiln/conflicts-scratch";

    static Result<KilnConfig> load(Path file, Path home) {
        return TomlParser.parseFile(file)
                         .mapError(cause -> ConfigError.Unreadable.FACTORY.apply(file.toString(),
                                                                                 cause))
                         .flatMap(doc -> parse(doc, home));
    }

    /// Pure: `home` expands a leading `~/`.
    static Result<KilnConfig> parse(TomlDocument doc, Path home) {
        return known(doc).flatMap(read -> config(read, home));
    }

    /// Every unknown section, key and top-level key, gathered into one failure.
    private static Result<TomlDocument> known(TomlDocument doc) {
        return Result.allOf(Stream.of(topLevelKeys(doc),
                                      unknownSections(doc),
                                      unknownSectionKeys(doc),
                                      unknownHostKeys(doc),
                                      unknownRepoKeys(doc))
                                  .flatMap(Function.identity())
                                  .map(KilnConfigLoader::refusal)).map(_ -> doc);
    }

    private static Result<Unit> refusal(ConfigError error) {
        return error.result();
    }

    private static Stream<ConfigError> topLevelKeys(TomlDocument doc) {
        return TomlValues.section(doc, "")
                         .keySet()
                         .stream()
                         .map(ConfigError.TopLevelKey.FACTORY::apply);
    }

    private static Stream<ConfigError> unknownSections(TomlDocument doc) {
        return Stream.concat(doc.sections().keySet().stream().filter(KilnConfigLoader::unknownSection),
                             doc.tableArrays().keySet().stream().filter(KilnConfigLoader::unknownTableArray))
                     .map(ConfigError.UnknownSection.FACTORY::apply);
    }

    private static boolean unknownTableArray(String name) {
        return ! HOSTS.equals(name) && !REPOS.equals(name);
    }

    private static Stream<ConfigError> unknownRepoKeys(TomlDocument doc) {
        return doc.getTableArray(REPOS)
                  .or(List.of())
                  .stream()
                  .flatMap(table -> unknownKeys(table, REPOS, REPO_KEYS));
    }

    /// The root section `""` is not a section; its keys are [#topLevelKeys].
    private static boolean unknownSection(String name) {
        return ! name.isEmpty() && !SECTIONS.containsKey(name);
    }

    private static Stream<ConfigError> unknownSectionKeys(TomlDocument doc) {
        return SECTIONS.keySet()
                       .stream()
                       .flatMap(section -> unknownKeys(doc, section));
    }

    private static Stream<ConfigError> unknownKeys(TomlDocument doc, String section) {
        return unknownKeys(TomlValues.section(doc, section), section, SECTIONS.get(section));
    }

    private static Stream<ConfigError> unknownHostKeys(TomlDocument doc) {
        return doc.getTableArray(HOSTS)
                  .or(List.of())
                  .stream()
                  .flatMap(table -> unknownKeys(table, HOSTS, HOST_KEYS));
    }

    private static Stream<ConfigError> unknownKeys(Map<String, Object> table, String section, Set<String> known) {
        return table.keySet()
                    .stream()
                    .filter(Predicate.not(known::contains))
                    .map(key -> ConfigError.UnknownKey.FACTORY.apply(section, key));
    }

    /// The `[server]` table is read first and alone: a refused `bind` or `hostNames` is a [ConfigError.Listen] on its own
    /// (exit 78, `Main.exitCode`), which `Result.all` would bury in a composite.
    private static Result<KilnConfig> config(TomlDocument doc, Path home) {
        return server(TomlValues.section(doc, "server")).flatMap(server -> rest(doc, home, server));
    }

    private static Result<KilnConfig> rest(TomlDocument doc, Path home, Server server) {
        return Result.all(database(TomlValues.section(doc, "db"),
                                   home),
                          gitHub(TomlValues.section(doc, "github"),
                                 app(doc, home),
                                 others(doc),
                                 home),
                          mirror(TomlValues.section(doc, "mirror"),
                                 home),
                          temper(TomlValues.section(doc, "temper"),
                                 home),
                          scan(TomlValues.section(doc, "scan")),
                          mac(TomlValues.section(doc, "mac")),
                          hosts(doc, home))
                     .map((db, github, mirror, temper, scan, mac, hosts) -> KilnConfig.kilnConfig(server,
                                                                                                  db,
                                                                                                  github,
                                                                                                  mirror,
                                                                                                  temper,
                                                                                                  scan,
                                                                                                  mac,
                                                                                                  hosts))
                     .flatMap(config -> conflictWatch(doc,
                                                      home,
                                                      config.github().bases()).map(config::withConflicts));
    }

    /// The conflict watch is off unless the document has a `[conflicts]` header (PR #108 review F2): the watch starts git
    /// processes against the mirror and appends to a log the merge runner also reads, so an upgrade never turns it on.
    /// The header alone, with no key, turns it on with every default: writing the header is the operator's decision.
    private static Result<Option<Conflicts>> conflictWatch(TomlDocument doc, Path home, List<BranchName> bases) {
        return doc.sections()
                  .containsKey(CONFLICTS)
               ? conflicts(TomlValues.section(doc, CONFLICTS), home, bases).map(Option::some)
               : Result.success(none());
    }

    /// `[conflicts]`, every key optional; `branch` defaults to the first `[github] bases` entry.
    private static Result<Conflicts> conflicts(Map<String, Object> table, Path home, List<BranchName> bases) {
        return Result.all(TomlValues.optionalString(table, "conflicts", "branch").flatMap(raw -> raw.map(BranchName::branchName)
                                                                                                    .or(() -> Result.success(bases.getFirst()))),
                          TomlValues.path(table, "conflicts", "eventsLog", CONFLICT_EVENTS, home),
                          TomlValues.path(table, "conflicts", "scratch", CONFLICT_SCRATCH, home))
                     .map(Conflicts::conflicts);
    }

    private static Result<Server> server(Map<String, Object> table) {
        return TomlValues.integer(table,
                                  "server",
                                  "port",
                                  some(DEFAULT_PORT))
                         .flatMap(port -> port("server", port))
                         .flatMap(port -> bind(table).flatMap(bind -> hostNames(table).flatMap(names -> listening(port,
                                                                                                                  bind,
                                                                                                                  names))));
    }

    private static Result<Server> listening(int port, Option<String> bind, List<String> names) {
        return bind.isEmpty() && !names.isEmpty()
               ? ConfigError.Listen.listen("hostNames", "needs [server] bind").result()
               : Result.success(Server.server(port, bind, names));
    }

    /// `[server] bind` (owner 2026-11-05): one literal IPv4 address, never the wildcard or a loopback address (the
    /// loopback address is always served). A blank or non-string value is refused, never read as absent.
    private static Result<Option<String>> bind(Map<String, Object> table) {
        return TomlValues.optional(option(table.get("bind")), KilnConfigLoader::bindAddress);
    }

    private static Result<String> bindAddress(Object raw) {
        return raw instanceof String text
               ? literalIpv4(text.trim())
               : ConfigError.Listen.listen("bind", "must be a string").result();
    }

    private static Result<String> literalIpv4(String text) {
        return IPV4.matcher(text).matches() && octets(text)
               ? notWide(text)
               : ConfigError.Listen.listen("bind", "must be a literal IPv4 address, not '" + text + "'").result();
    }

    private static boolean octets(String text) {
        return Arrays.stream(text.split("\\.")).allMatch(octet -> Integer.parseInt(octet) <= 255);
    }

    private static Result<String> notWide(String text) {
        return text.equals("0.0.0.0")
               ? ConfigError.Listen.listen("bind", "the wildcard address is refused").result()
               : text.startsWith("127.")
                 ? ConfigError.Listen.listen("bind", "a loopback address is served already").result()
                 : Result.success(text);
    }

    /// `[server] hostNames`: extra `Host` names (letters, digits, `.` and `-`), lower-cased; absent is empty.
    private static Result<List<String>> hostNames(Map<String, Object> table) {
        return option(table.get("hostNames")).map(_ -> TomlValues.stringList(table, "server", "hostNames").flatMap(KilnConfigLoader::hostNameList))
                     .or(() -> Result.success(List.of()));
    }

    private static Result<List<String>> hostNameList(List<String> names) {
        return names.stream()
                    .map(String::trim)
                    .filter(name -> !HOST_NAME.matcher(name).matches())
                    .findFirst()
                    .<Result<List<String>>> map(bad -> ConfigError.Listen.listen("hostNames",
                                                                                 "not a host name: '" + bad + "'").result())
                    .orElseGet(() -> Result.success(names.stream()
                                                         .map(name -> name.trim()
                                                                          .toLowerCase(Locale.ROOT))
                                                         .distinct()
                                                         .toList()));
    }

    private static Result<Integer> port(String section, int port) {
        return TomlValues.between(section, "port", port, 1, 65535);
    }

    private static Result<Database> database(Map<String, Object> table, Path home) {
        return Result.all(TomlValues.string(table, "db", "host"),
                          TomlValues.integer(table,
                                             "db",
                                             "port",
                                             none()).flatMap(port -> port("db", port)),
                          TomlValues.string(table, "db", "name"),
                          TomlValues.string(table, "db", "user"),
                          TomlValues.path(table, "db", "passwordFile", home),
                          TomlValues.path(table, "db", "highWaterFile", HIGH_WATER_FILE, home))
                     .map(Database::database);
    }

    private static Result<GitHub> gitHub(Map<String, Object> table,
                                         Result<Option<KilnConfig.App>> app,
                                         Result<List<Repo>> others,
                                         Path home) {
        return Result.all(TomlValues.string(table, "github", "repo").flatMap(raw -> repo("github", raw)),
                          TomlValues.stringList(table, "github", "bases").flatMap(raw -> bases("github", raw)),
                          TomlValues.optionalPath(table, "github", "tokenFile", home),
                          duration(table, "github", "cycleDeadlineSeconds", CYCLE_DEADLINE_SECONDS, ChronoUnit.SECONDS),
                          others,
                          positive(table, "hourlyCap", GitHub.DEFAULT_HOURLY_CAP),
                          positive(table, "reserve", GitHub.DEFAULT_RESERVE),
                          app,
                          anonymous(table))
                     .map(GitHub::gitHub)
                     .flatMap(KilnConfigLoader::distinctRepos);
    }

    /// `[github.app]` when the section is present: all three keys are then required.
    private static Result<Option<KilnConfig.App>> app(TomlDocument doc, Path home) {
        return doc.sections()
                  .containsKey(APP)
               ? appSection(TomlValues.section(doc, APP), home).map(Option::some)
               : Result.success(none());
    }

    private static Result<KilnConfig.App> appSection(Map<String, Object> table, Path home) {
        return Result.all(TomlValues.string(table, APP, "clientId"),
                          TomlValues.longInteger(table, APP, "installationId"),
                          TomlValues.path(table, APP, "keyFile", home))
                     .map((clientId, installation, keyFile) -> KilnConfig.App.app(clientId, installation, keyFile));
    }

    /// `[github] anonymous`: absent is false; a value that is not a boolean is refused.
    private static Result<Boolean> anonymous(Map<String, Object> table) {
        return option(table.get("anonymous")).map(value -> value instanceof Boolean flag
                                                           ? Result.success(flag)
                                                           : ConfigError.Invalid.FACTORY.apply("github",
                                                                                               "anonymous",
                                                                                               cause("must be a boolean")).<Boolean> result())
                     .or(() -> Result.success(false));
    }

    /// The `[[repos]]` tables, in file order.
    private static Result<List<Repo>> others(TomlDocument doc) {
        return Result.allOf(doc.getTableArray(REPOS).or(List.of()).stream().map(KilnConfigLoader::otherRepo).toList());
    }

    private static Result<Repo> otherRepo(Map<String, Object> table) {
        return Result.all(TomlValues.string(table, REPOS, "repo").flatMap(raw -> repo(REPOS, raw)),
                          TomlValues.stringList(table, REPOS, "bases").flatMap(raw -> bases(REPOS, raw)))
                     .map(Repo::repo);
    }

    /// A repository listed twice (in `[github]` and `[[repos]]`, or in two `[[repos]]`) would be read twice a cycle.
    private static Result<GitHub> distinctRepos(GitHub gitHub) {
        return Verify.ensure(gitHub,
                             KilnConfigLoader::reposDistinct,
                             ConfigError.Invalid.FACTORY.apply(REPOS, "repo", cause("a repository is listed twice")));
    }

    private static boolean reposDistinct(GitHub gitHub) {
        return distinct(gitHub.repos().stream().map(Repo::name).toList());
    }

    private static Result<String> repo(String section, String raw) {
        return Verify.ensure(raw, Verify.Is::matches, REPO, TomlValues.invalid(section, "repo", raw));
    }

    private static Result<List<BranchName>> bases(String section, List<String> raw) {
        return nonEmpty(raw, section, "bases").flatMap(names -> branchNames(section, names))
                       .flatMap(names -> distinctBases(section, names));
    }

    private static Result<List<BranchName>> distinctBases(String section, List<BranchName> bases) {
        return Verify.ensure(bases,
                             KilnConfigLoader::distinct,
                             ConfigError.Invalid.FACTORY.apply(section, "bases", cause("a base is listed twice")));
    }

    private static Result<List<BranchName>> branchNames(String section, List<String> raw) {
        return Result.allOf(raw.stream().map(name -> base(section, name)).toList());
    }

    private static Result<BranchName> base(String section, String raw) {
        return BranchName.branchName(raw).mapError(cause -> ConfigError.Invalid.FACTORY.apply(section, "bases", cause));
    }

    private static Result<List<String>> nonEmpty(List<String> values, String section, String key) {
        return Verify.ensure(values,
                             Predicate.not(List::isEmpty),
                             ConfigError.Invalid.FACTORY.apply(section, key, cause("must not be empty")));
    }

    private static Result<Mirror> mirror(Map<String, Object> table, Path home) {
        return Result.all(TomlValues.string(table, "mirror", "origin").flatMap(origin -> TomlValues.notOption("mirror",
                                                                                                              "origin",
                                                                                                              origin)),
                          TomlValues.path(table, "mirror", "path", MIRROR_PATH, home))
                     .map(Mirror::mirror);
    }

    private static Result<Temper> temper(Map<String, Object> table, Path home) {
        return Result.all(TomlValues.string(table, "temper", "url").flatMap(KilnConfigLoader::temperUrl),
                          TomlValues.path(table, "temper", "readTokenFile", home),
                          duration(table, "temper", "cycleDeadlineSeconds", CYCLE_DEADLINE_SECONDS, ChronoUnit.SECONDS))
                     .map(Temper::temper);
    }

    private static Result<Url> temperUrl(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::matches,
                             HTTP_URL,
                             TomlValues.invalid("temper", "url", raw))
                     .flatMap(KilnConfigLoader::parsedUrl);
    }

    private static Result<Url> parsedUrl(String raw) {
        return Url.url(raw)
                  .mapError(cause -> ConfigError.Invalid.FACTORY.apply("temper", "url", cause))
                  .flatMap(url -> Verify.ensure(url,
                                                KilnConfigLoader::portInRange,
                                                TomlValues.invalid("temper", "url", raw)));
    }

    /// No port (`-1`) means the scheme's default; a written one is in 1..65535 (`URI` itself does not bound it).
    private static boolean portInRange(Url url) {
        var port = url.uri().getPort();

        return port == -1 || Verify.Is.between(port, 1, 65535);
    }

    private static Result<Scan> scan(Map<String, Object> table) {
        return Result.all(duration(table, "scan", "hotSeconds", 120, ChronoUnit.SECONDS),
                          duration(table, "scan", "hotWindowHours", 48, ChronoUnit.HOURS),
                          fullAt(table),
                          duration(table, "scan", "sizeGapSeconds", 5, ChronoUnit.SECONDS),
                          duration(table, "scan", "staleAfterHours", 48, ChronoUnit.HOURS),
                          duration(table, "scan", "stepDeadlineSeconds", STEP_DEADLINE_SECONDS, ChronoUnit.SECONDS),
                          duration(table,
                                   "scan",
                                   "transportTimeoutSeconds",
                                   TRANSPORT_TIMEOUT_SECONDS,
                                   ChronoUnit.SECONDS))
                     .map(Scan::scan)
                     .flatMap(KilnConfigLoader::transportBelowHalfStep);
    }

    /// The transport timeout is below half the step deadline: `HostScanner` gives a transport call half the step, so a
    /// transport that outlives it would be cut off by the step, never killed by its own timeout.
    private static Result<Scan> transportBelowHalfStep(Scan scan) {
        return Verify.ensure(scan,
                             KilnConfigLoader::belowHalfStep,
                             ConfigError.Invalid.FACTORY.apply("scan",
                                                               "transportTimeoutSeconds",
                                                               cause("must be below half of stepDeadlineSeconds")));
    }

    private static boolean belowHalfStep(Scan scan) {
        return scan.transportTimeout()
                   .multipliedBy(2)
                   .compareTo(scan.stepDeadline()) < 0;
    }

    private static Result<Integer> positive(Map<String, Object> table, String key, int fallback) {
        return TomlValues.integer(table, "github", key, some(fallback)).flatMap(value -> TomlValues.positive("github",
                                                                                                             key,
                                                                                                             value));
    }

    private static Result<Duration> duration(Map<String, Object> table,
                                             String section,
                                             String key,
                                             int fallback,
                                             ChronoUnit unit) {
        return TomlValues.integer(table,
                                  section,
                                  key,
                                  some(fallback))
                         .flatMap(value -> TomlValues.positive(section, key, value))
                         .map(value -> Duration.of(value, unit));
    }

    /// `[mac] roots`: optional (none: a push deletes nothing), each root checked as a host's.
    private static Result<KilnConfig.Mac> mac(Map<String, Object> table) {
        return TomlValues.optional(option(table.get("roots")),
                                   _ -> TomlValues.stringList(table, "mac", "roots"))
                         .flatMap(roots -> eachRoot(roots.or(List.of()),
                                                    "mac"))
                         .map(KilnConfig.Mac::mac);
    }

    private static Result<LocalTime> fullAt(Map<String, Object> table) {
        return TomlValues.string(table, "scan", "fullAt", "04:00").flatMap(KilnConfigLoader::localTime);
    }

    private static Result<LocalTime> localTime(String raw) {
        return DateTime.parseLocalTime(raw).mapError(cause -> ConfigError.Invalid.FACTORY.apply("scan", "fullAt", cause));
    }

    private static Result<List<Host>> hosts(TomlDocument doc, Path home) {
        return Result.allOf(doc.getTableArray(HOSTS).or(List.of()).stream().map(table -> host(table, home)).toList()).flatMap(KilnConfigLoader::distinctNames);
    }

    private static Result<List<Host>> distinctNames(List<Host> hosts) {
        return Verify.ensure(hosts,
                             KilnConfigLoader::namesDistinct,
                             ConfigError.Invalid.FACTORY.apply("hosts", "name", cause("two hosts share a name")));
    }

    private static boolean namesDistinct(List<Host> hosts) {
        return distinct(hosts.stream().map(Host::name).toList());
    }

    private static boolean distinct(List<?> values) {
        return values.stream()
                     .distinct()
                     .count() == values.size();
    }

    private static Result<Host> host(Map<String, Object> table, Path home) {
        return Result.all(TomlValues.string(table, "hosts", "name").flatMap(KilnConfigLoader::hostName),
                          TomlValues.string(table, "hosts", "address").flatMap(address -> TomlValues.notOption("hosts",
                                                                                                               "address",
                                                                                                               address)),
                          TomlValues.string(table, "hosts", "user").flatMap(user -> TomlValues.notOption("hosts",
                                                                                                         "user",
                                                                                                         user)),
                          TomlValues.path(table, "hosts", "key", home),
                          TomlValues.path(table, "hosts", "knownHosts", home),
                          TomlValues.stringList(table, HOSTS, "roots").flatMap(KilnConfigLoader::roots))
                     .map(Host::host);
    }

    private static Result<HostName> hostName(String raw) {
        return HostName.hostName(raw).mapError(cause -> ConfigError.Invalid.FACTORY.apply("hosts", "name", cause));
    }

    private static Result<List<String>> roots(List<String> raw) {
        return nonEmpty(raw, "hosts", "roots").flatMap(roots -> eachRoot(roots, "hosts"));
    }

    private static Result<List<String>> eachRoot(List<String> raw, String section) {
        return Result.allOf(raw.stream().map(root -> root(root, section)).toList());
    }

    private static Result<String> root(String raw, String section) {
        return Verify.ensure(raw,
                             Verify.Is::matches,
                             ROOT,
                             TomlValues.invalid(section, "roots", raw))
                     .flatMap(shaped -> Verify.ensure(shaped,
                                                      KilnConfigLoader::rootComponents,
                                                      TomlValues.invalid(section, "roots", raw)));
    }

    /// The components after the leading `/` or `~/`; the first never holds `*`.
    private static boolean rootComponents(String root) {
        var components = List.of(root.replaceFirst("^~?/", "").split("/", -1));

        return ! components.getFirst()
                           .contains("*") && components.stream()
                                                       .allMatch(KilnConfigLoader::rootComponent);
    }

    private static boolean rootComponent(String component) {
        return ! component.isEmpty()
               && !component.equals(".")
               && !component.equals("..")
               && !component.contains("**");
    }

    record unused() implements KilnConfigLoader {}
}
