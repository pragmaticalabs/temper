package org.pragmatica.temper.cli.config;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Functions.ThrowingFn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.parse.Network;
import org.pragmatica.lang.parse.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.alarm.AlarmCode;
import org.pragmatica.temper.cli.alarm.GithubIssueSink;

import static org.pragmatica.lang.Result.success;


/// The `[alarm]` section of `~/.config/temper/config`, read by `alarm-check` only. A missing file or section means
/// every default; a key outside the table below is refused (a typo would otherwise fall back to a default silently).
/// The file is parsed as TOML here, so the alarm host's file must be valid TOML (its `url` quoted), which the URL
/// reader of the other commands does not require.
///
/// | Key | Default | Meaning |
/// |---|---|---|
/// | `sink` | `"github-issue"` | `github-issue` or `stdout` (prints the decisions only: a dry run) |
/// | `repo` | none | `owner/name`; required by `github-issue` |
/// | `token-file` | `"~/.config/temper/alarm-token"` | the GitHub token, one line; never read from argv or the environment |
/// | `github-api` | `"https://api.github.com"` | the GitHub REST base URL |
/// | `repeat` | `"6h"` | a firing issue is commented at most once per this interval |
/// | `host` | the first label of this machine's host name | names the checked host in each issue title and marker line; a host name (a letter or digit, then up to 62 letters, digits, `.`, `_`, `-`), never `opened`, `firing` or `cleared` |
/// | `paths` | `["~/.temper", "~/.local/share/temper/evidence"]` | the paths `disk-low` measures |
/// | `min-free-gib` | `50` | `disk-low` fires below this many free GiB on any path |
/// | `stale-after` | `"26h"` | `dump-stale` and `dump-file-stale` fire when no good dump ended within this |
/// | `dump-dir` | `"~/.local/share/temper/evidence/pgdump"` | where `dump-file-stale` looks for the newest `temper-*.dump` |
/// | `skip` | `[]` | `dump-failed` and/or `dump-stale` only: not evaluated at all; needs `skip-until` |
/// | `skip-until` | none | the last day (UTC) a `skip` holds, at most 7 days after the day of the check that reads it |
///
/// A `skip` is a guarded, temporary exception: past `skip-until` its codes are evaluated again, and `check-blind` fires
/// (`skip of <codes> expired on <date>`) on every run until the config drops the skip. The 7-day bound is checked at
/// every run, so a `skip-until` can never be pushed further than a week ahead.
public record AlarmConfig(Sink sink,
                          String host,
                          List<Path> paths,
                          long minFreeBytes,
                          Duration staleAfter,
                          Path dumpDir,
                          Option<Skip> skip) {
    public static final long GIB = 1024L * 1024 * 1024;
    private static final String SECTION = "alarm";
    private static final String GITHUB_ISSUE = "github-issue";
    private static final String STDOUT = "stdout";
    /// The furthest `skip-until` may be after the day of the check.
    public static final int MAX_SKIP_DAYS = 7;

    /// The only codes a `skip` may name: the checks backed by `GET /maintenance`.
    private static final List<String> SKIPPABLE = List.of(AlarmCode.DUMP_FAILED.wire(), AlarmCode.DUMP_STALE.wire());

    private static final String DEFAULT_DUMP_DIR = "~/.local/share/temper/evidence/pgdump";

    private static final Set<String> KEYS = Set.of("sink",
                                                   "dump-dir",
                                                   "skip",
                                                   "skip-until",
                                                   "repo",
                                                   "token-file",
                                                   "github-api",
                                                   "repeat",
                                                   "host",
                                                   "paths",
                                                   "min-free-gib",
                                                   "stale-after");

    private static final String DEFAULT_TOKEN_FILE = "~/.config/temper/alarm-token";
    private static final String DEFAULT_GITHUB_API = "https://api.github.com";
    private static final String DEFAULT_REPEAT = "6h";
    private static final String DEFAULT_STALE_AFTER = "26h";
    private static final long DEFAULT_MIN_FREE_GIB = 50;
    /// The controller's workspace (jobs, mirror, dist) and its evidence root (`controller.toml.example`).
    private static final List<String> DEFAULT_PATHS = List.of("~/.temper", "~/.local/share/temper/evidence");
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern API = Pattern.compile("^https?://[^/?#\\s]+(/[^?#\\s]*)?$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$");
    private static final Pattern IPV4 = Pattern.compile("^[0-9]+(\\.[0-9]+){3}$");

    /// `skip` with its `skip-until`: `codes` are not evaluated through `until`, a UTC date.
    public record Skip(List<AlarmCode> codes, LocalDate until) {
        public static Skip skip(List<AlarmCode> codes, LocalDate until) {
            return new Skip(List.copyOf(codes), until);
        }

        /// The skip holds through the whole of `until`.
        public boolean expiredOn(LocalDate today) {
            return today.isAfter(until);
        }

        public String codesText() {
            return codes.stream()
                        .map(AlarmCode::wire)
                        .collect(Collectors.joining(", "));
        }
    }

    /// The day of `now`, in UTC: the day `skip-until` is compared with.
    public static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC);
    }

    /// The codes skipped at `now`: none without a skip, or once it expired.
    public List<AlarmCode> skippedAt(Instant now) {
        return skip.filter(active -> !active.expiredOn(today(now)))
                   .map(Skip::codes)
                   .or(List.of());
    }

    /// Where the conditions go (`alarm.sink`).
    public sealed interface Sink {
        /// Prints the decisions and delivers nothing.
        record Stdout() implements Sink {}

        /// One GitHub issue per condition code and host in `repo`; the token is read from `tokenFile` when the check
        /// runs.
        record GithubIssue(String repo, Path tokenFile, URI api, Duration repeat) implements Sink {}
    }

    public static Result<AlarmConfig> alarmConfig(CliContext context) {
        return alarmConfig(context,
                           context.clock().instant());
    }

    /// The configuration as of `now`: the instant the 7-day bound of `skip-until` is measured from.
    public static Result<AlarmConfig> alarmConfig(CliContext context, Instant now) {
        var today = today(now);

        return document(CliConfig.configDir(context.home()).resolve("config")).flatMap(doc -> checkedKeys(doc,
                                                                                                          context.home(),
                                                                                                          today));
    }

    /// The GitHub token: the file's one line, trimmed. A missing file, or one that does not hold exactly one
    /// printable-ASCII token (it becomes an HTTP header), is exit 64; a file readable by group or others draws a
    /// warning on stderr. Neither message carries the file's contents.
    public static Result<String> token(CliContext context, Path file) {
        return (Files.isRegularFile(file)
                ? success(file)
                : CliError.Usage.FACTORY.apply("Alarm token file missing: " + file
                                              + " (put the GitHub token there, mode 0600)").<Path> result()).onSuccess(present -> CliConfig.warnIfShared(context,
                                                                                                                                                         present))
                                                                                                            .flatMap(CliConfig::text)
                                                                                                            .map(String::strip)
                                                                                                            .flatMap(text -> Verify.ensure(text,
                                                                                                                                           Verify.Is::matches,
                                                                                                                                           CliConfig.TOKEN,
                                                                                                                                           CliError.Usage.FACTORY.apply("Alarm token file must hold one token of printable ASCII: " + file)));
    }

    private static Result<TomlDocument> document(Path file) {
        return Files.isRegularFile(file)
               ? CliConfig.text(file).flatMap(text -> parsed(file, text))
               : success(TomlDocument.EMPTY);
    }

    private static Result<TomlDocument> parsed(Path file, String text) {
        return TomlParser.parse(text).mapError(cause -> CliError.BadSetting.FACTORY.apply("config file",
                                                                                          file.toString(),
                                                                                          cause));
    }

    private static Result<AlarmConfig> checkedKeys(TomlDocument doc, Path home, LocalDate today) {
        return known(doc).flatMap(known -> alarmConfig(known, home, today));
    }

    private static Result<AlarmConfig> alarmConfig(TomlDocument doc, Path home, LocalDate today) {
        return Result.all(sink(doc, home),
                          host(doc),
                          paths(doc, home),
                          minFreeBytes(doc),
                          duration(doc, "stale-after", DEFAULT_STALE_AFTER),
                          success(expanded(doc.getString(SECTION, "dump-dir").or(DEFAULT_DUMP_DIR),
                                           home)),
                          skip(doc, today))
                     .map(AlarmConfig::new);
    }

    /// No `skip`, or an empty one, needs no `skip-until`; otherwise it is required and at most [#MAX_SKIP_DAYS] ahead.
    private static Result<Option<Skip>> skip(TomlDocument doc, LocalDate today) {
        return skipCodes(doc).flatMap(codes -> skipOf(doc, today, codes));
    }

    private static Result<Option<Skip>> skipOf(TomlDocument doc, LocalDate today, List<AlarmCode> codes) {
        return codes.isEmpty()
               ? success(Option.none())
               : skipUntil(doc, today).map(until -> Option.some(Skip.skip(codes, until)));
    }

    /// Only the endpoint-backed dump checks may be skipped: every other condition (`controller-down` above all) would
    /// otherwise go blind for up to a week. One failure names every code refused.
    private static Result<List<AlarmCode>> skipCodes(TomlDocument doc) {
        return rawSkip(doc).flatMap(AlarmConfig::skippable);
    }

    private static Result<List<AlarmCode>> skippable(List<String> raw) {
        var refused = raw.stream().distinct().filter(code -> !SKIPPABLE.contains(code)).toList();

        return refused.isEmpty()
               ? success(raw.stream().distinct().flatMap(code -> AlarmCode.alarmCode(code).stream()).toList())
               : CliError.Usage.FACTORY.apply("alarm.skip may name only dump-failed and dump-stale, not: " + String.join(", ",
                                                                                                                         refused)
                                             + " (controller-down, disk-low, worker-lost, dump-file-stale and check-blind"
                                             + " are never skipped)").result();
    }

    private static Result<List<String>> rawSkip(TomlDocument doc) {
        return doc.hasKey(SECTION, "skip")
               ? doc.getStringList(SECTION, "skip")
                    .toResult(CliError.Usage.FACTORY.apply("alarm.skip must be a list of condition codes"))
               : success(List.of());
    }

    private static Result<LocalDate> skipUntil(TomlDocument doc, LocalDate today) {
        var limit = today.plusDays(MAX_SKIP_DAYS);

        return doc.getString(SECTION, "skip-until")
                  .toResult(CliError.Usage.FACTORY.apply("alarm.skip-until is required with alarm.skip (a date at most " + MAX_SKIP_DAYS
                                                        + " days ahead)"))
                  .flatMap(raw -> DateTime.parseLocalDate(raw).mapError(cause -> CliError.BadSetting.FACTORY.apply("alarm.skip-until",
                                                                                                                   raw,
                                                                                                                   cause)))
                  .filter(CliError.Usage.FACTORY.apply("alarm.skip-until must be at most " + MAX_SKIP_DAYS
                                                      + " days after today (" + today
                                                      + "), at most " + limit),
                          until -> !until.isAfter(limit));
    }

    private static Result<TomlDocument> known(TomlDocument doc) {
        var unknown = doc.keys(SECTION).stream().filter(key -> !KEYS.contains(key)).sorted().toList();

        return unknown.isEmpty()
               ? success(doc)
               : CliError.Usage.FACTORY.apply("unknown [alarm] key(s): " + String.join(", ", unknown)).result();
    }

    private static Result<Sink> sink(TomlDocument doc, Path home) {
        return switch (doc.getString(SECTION, "sink")
                          .or(GITHUB_ISSUE)) {
            case GITHUB_ISSUE -> githubIssue(doc, home);
            case STDOUT -> success(new Sink.Stdout());
            case String other -> CliError.Usage.FACTORY.apply("alarm.sink must be github-issue or stdout: " + other).result();
        };
    }

    private static Result<Sink> githubIssue(TomlDocument doc, Path home) {
        var tokenFile = expanded(doc.getString(SECTION, "token-file").or(DEFAULT_TOKEN_FILE),
                                 home);

        return Result.all(repo(doc), api(doc), duration(doc, "repeat", DEFAULT_REPEAT)).map((repo, api, repeat) -> new Sink.GithubIssue(repo,
                                                                                                                                        tokenFile,
                                                                                                                                        api,
                                                                                                                                        repeat));
    }

    private static Result<String> repo(TomlDocument doc) {
        return doc.getString(SECTION, "repo")
                  .toResult(CliError.Usage.FACTORY.apply("alarm.repo is not set (owner/name, needed by the github-issue sink)"))
                  .flatMap(repo -> Verify.ensure(repo,
                                                 Verify.Is::matches,
                                                 REPO,
                                                 CliError.Usage.FACTORY.apply("alarm.repo must be owner/name: " + repo)));
    }

    /// The base URL without a trailing slash: request paths start with one.
    private static Result<URI> api(TomlDocument doc) {
        var raw = doc.getString(SECTION, "github-api").or(DEFAULT_GITHUB_API).replaceAll("/+$", "");

        return Verify.ensure(raw,
                             Verify.Is::matches,
                             API,
                             CliError.Usage.FACTORY.apply("alarm.github-api must be http[s]://host[/path]: " + raw))
                     .flatMap(url -> Network.parseURI(url).mapError(cause -> CliError.BadSetting.FACTORY.apply("alarm.github-api",
                                                                                                               url,
                                                                                                               cause)));
    }

    private static Result<Duration> duration(TomlDocument doc, String key, String fallback) {
        var raw = doc.getString(SECTION, key).or(fallback);

        return TimeSpan.timeSpan(raw)
                       .mapError(cause -> CliError.BadSetting.FACTORY.apply("alarm." + key, raw, cause))
                       .map(TimeSpan::duration)
                       .flatMap(value -> Verify.ensure(value,
                                                       Verify.Is::greaterThan,
                                                       Duration.ZERO,
                                                       CliError.Usage.FACTORY.apply("alarm." + key
                                                                                   + " must be positive: " + raw)));
    }

    private static Result<String> host(TomlDocument doc) {
        return doc.getString(SECTION, "host")
                  .map(AlarmConfig::validHost)
                  .or(AlarmConfig::localHostName);
    }

    /// Unset `alarm.host`: the first DNS label of this machine's name (`bigboy` of `bigboy.example.org`). An IP
    /// literal, or a label outside a host name, is no name to file issues under: exit 64, naming the key to set.
    static Result<String> defaultHost(String machineName) {
        return Option.some(machineName)
                     .filter(name -> !IPV4.matcher(name).matches())
                     .map(AlarmConfig::firstLabel)
                     .filter(HOST.asMatchPredicate())
                     .filter(label -> !GithubIssueSink.KINDS.contains(label))
                     .toResult(CliError.Usage.FACTORY.apply("alarm.host is unset and this machine's name (" + machineName
                                                           + ") is no host name: set alarm.host"));
    }

    private static String firstLabel(String name) {
        var dot = name.indexOf('.');

        return dot < 0
               ? name
               : name.substring(0, dot);
    }

    /// The host ends every issue's marker line, `<!-- temper-alarm:<code>:<host> -->`: the strict charset keeps it one
    /// line that cannot close the marker early, and a marker kind would make another host's `opened` line this host's
    /// identity ([GithubIssueSink#KINDS]).
    private static Result<String> validHost(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::matches,
                             HOST,
                             CliError.Usage.FACTORY.apply("alarm.host must be a host name (a letter or digit, then up to 62"
                                                         + " letters, digits, '.', '_' or '-'): " + raw))
                     .filter(CliError.Usage.FACTORY.apply("alarm.host may not be a marker kind (" + String.join(", ",
                                                                                                                GithubIssueSink.KINDS)
                                                         + "): " + raw),
                             host -> !GithubIssueSink.KINDS.contains(host));
    }

    /// May block for the resolver's timeout: `getLocalHost` resolves this machine's own name.
    private static Result<String> localHostName() {
        return machineHost(() -> InetAddress.getLocalHost().getHostName());
    }

    /// Unset `alarm.host`, from `lookup` (this machine's name): a lookup that fails (its own name does not resolve) is
    /// exit 64, naming the key to set and carrying the failure.
    static Result<String> machineHost(ThrowingFn0<String> lookup) {
        return Result.lift(thrown -> CliError.BadSetting.FACTORY.apply("alarm.host",
                                                                       "unset, and this machine's name does not resolve: set alarm.host",
                                                                       Causes.fromThrowable(thrown)),
                           lookup)
                     .flatMap(AlarmConfig::defaultHost);
    }

    private static Result<List<Path>> paths(TomlDocument doc, Path home) {
        return rawPaths(doc).filter(CliError.Usage.FACTORY.apply("alarm.paths must name at least one path"),
                                    raw -> !raw.isEmpty())
                       .map(raw -> expanded(raw, home));
    }

    private static Result<List<String>> rawPaths(TomlDocument doc) {
        return doc.hasKey(SECTION, "paths")
               ? doc.getStringList(SECTION, "paths")
                    .toResult(CliError.Usage.FACTORY.apply("alarm.paths must be a list of paths"))
               : success(DEFAULT_PATHS);
    }

    private static List<Path> expanded(List<String> raw, Path home) {
        return raw.stream()
                  .map(path -> expanded(path, home))
                  .toList();
    }

    private static Result<Long> minFreeBytes(TomlDocument doc) {
        return minFreeGib(doc).flatMap(gib -> Verify.ensure(gib,
                                                            Verify.Is::between,
                                                            1L,
                                                            Long.MAX_VALUE / GIB,
                                                            CliError.Usage.FACTORY.apply("alarm.min-free-gib out of range: " + gib)))
                         .map(gib -> gib * GIB);
    }

    private static Result<Long> minFreeGib(TomlDocument doc) {
        return doc.hasKey(SECTION, "min-free-gib")
               ? doc.getLong(SECTION, "min-free-gib")
                    .toResult(CliError.Usage.FACTORY.apply("alarm.min-free-gib must be a whole number"))
               : success(DEFAULT_MIN_FREE_GIB);
    }

    /// A leading `~` is the home directory.
    private static Path expanded(String raw, Path home) {
        return raw.equals("~")
               ? home
               : underHome(raw, home);
    }

    private static Path underHome(String raw, Path home) {
        return raw.startsWith("~/")
               ? home.resolve(raw.substring(2))
               : Path.of(raw);
    }
}
