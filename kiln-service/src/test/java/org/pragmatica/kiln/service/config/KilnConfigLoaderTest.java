package org.pragmatica.kiln.service.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;


/// kiln-f1-design §6 row "Config", WP1 "config loader refusals": every key read, every default, and every refusal
/// naming its section and key. Each refusal is one edit of [#FULL], so the same file parses without it.
class KilnConfigLoaderTest {
    private static final Path HOME = Path.of("/home/builder");

    private static final Pattern ISSUE = Pattern.compile("(Invalid|Missing) \\[|Unknown (section|key|top-level key) ");

    static final String FULL = """
        [server]
        port = 7272
        [db]
        host = "127.0.0.1"
        port = 55442
        name = "kiln"
        user = "kiln"
        passwordFile = "~/.config/kiln/pg-password"
        [github]
        repo = "pragmaticalabs/pragmatica"
        bases = ["release-1.0.0-rc5", "main"]
        tokenFile = "~/.config/kiln/github-token"
        cycleDeadlineSeconds = 300
        [mirror]
        origin = "https://github.com/pragmaticalabs/pragmatica.git"
        path = "/srv/kiln/mirror.git"
        [temper]
        url = "http://127.0.0.1:7070"
        readTokenFile = "~/.config/temper/read-token"
        cycleDeadlineSeconds = 120
        [scan]
        hotSeconds = 90
        hotWindowHours = 24
        fullAt = "03:15"
        sizeGapSeconds = 7
        staleAfterHours = 72
        stepDeadlineSeconds = 900
        transportTimeoutSeconds = 449
        [mac]
        roots = ["~/src/pragmatica*", "/private/tmp/scratch-501/*/*/work/*"]
        [[hosts]]
        name = "bigboy"
        address = "127.0.0.1"
        user = "builder"
        key = "~/.ssh/kiln_scan"
        knownHosts = "~/.ssh/known_hosts"
        roots = ["~/work/co/*", "/home/builder/work/*"]
        [[hosts]]
        name = "cloudbb-3"
        address = "203.0.113.164"
        user = "builder"
        key = "~/.ssh/kiln_scan"
        knownHosts = "~/.ssh/known_hosts"
        roots = ["~/work/co/*"]
        """;

    /// The required keys only.
    static final String REQUIRED = """
        [db]
        host = "127.0.0.1"
        port = 55442
        name = "kiln"
        user = "kiln"
        passwordFile = "/p"
        [github]
        repo = "o/r"
        bases = ["main"]
        [mirror]
        origin = "https://github.com/o/r.git"
        [temper]
        url = "https://temper.local"
        readTokenFile = "/t"
        """;

    private static Result<KilnConfig> parse(String toml) {
        return TomlParser.parse(toml).flatMap(doc -> KilnConfigLoader.parse(doc, HOME));
    }

    private static KilnConfig parsed(String toml) {
        return parse(toml).onFailure(cause -> Assertions.fail("expected a success, got " + cause.message()))
                    .unwrap();
    }

    private static Cause refused(String toml) {
        return parse(toml).fold(cause -> cause,
                                value -> Assertions.fail("expected a failure, got " + value));
    }

    private static String edit(String from, String to) {
        assertThat(FULL).contains(from);

        return FULL.replace(from, to);
    }

    @Test
    void parse_readsEverySection_fullConfig() {
        var config = parsed(FULL);

        assertThat(config.server().port()).isEqualTo(7272);
        assertThat(config.db().host()).isEqualTo("127.0.0.1");
        assertThat(config.db().port()).isEqualTo(55442);
        assertThat(config.db().name()).isEqualTo("kiln");
        assertThat(config.db().user()).isEqualTo("kiln");
        assertThat(config.db().passwordFile()).isEqualTo(HOME.resolve(".config/kiln/pg-password"));
        assertThat(config.db().highWaterFile()).isEqualTo(HOME.resolve(".local/share/kiln/change-hwm"));
        assertThat(config.github().repo()).isEqualTo("pragmaticalabs/pragmatica");
        assertThat(config.github().bases()).extracting(Object::toString).containsExactly("release-1.0.0-rc5", "main");
        assertThat(config.github().tokenFile().unwrap()).isEqualTo(HOME.resolve(".config/kiln/github-token"));
        assertThat(config.mirror().origin()).isEqualTo("https://github.com/pragmaticalabs/pragmatica.git");
        assertThat(config.mirror().path()).isEqualTo(Path.of("/srv/kiln/mirror.git"));
        assertThat(config.temper().url().uri().toString()).isEqualTo("http://127.0.0.1:7070");
        assertThat(config.temper().readTokenFile()).isEqualTo(HOME.resolve(".config/temper/read-token"));
        assertThat(config.scan().hot()).isEqualTo(Duration.ofSeconds(90));
        assertThat(config.scan().hotWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(config.scan().fullAt()).isEqualTo(LocalTime.of(3, 15));
        assertThat(config.scan().sizeGap()).isEqualTo(Duration.ofSeconds(7));
        assertThat(config.scan().staleAfter()).isEqualTo(Duration.ofHours(72));
        assertThat(config.scan().stepDeadline()).isEqualTo(Duration.ofSeconds(900));
        assertThat(config.scan().transportTimeout()).isEqualTo(Duration.ofSeconds(449));
        assertThat(config.github().cycleDeadline()).isEqualTo(Duration.ofSeconds(300));
        assertThat(config.temper().cycleDeadline()).isEqualTo(Duration.ofSeconds(120));
        assertThat(config.mac().roots()).containsExactly("~/src/pragmatica*",
                                                         "/private/tmp/scratch-501/*/*/work/*");
        assertThat(config.hosts()).hasSize(2);
        var bigboy = config.hosts().getFirst();

        assertThat(bigboy.name().value()).isEqualTo("bigboy");
        assertThat(bigboy.address()).isEqualTo("127.0.0.1");
        assertThat(bigboy.user()).isEqualTo("builder");
        assertThat(bigboy.key()).isEqualTo(HOME.resolve(".ssh/kiln_scan"));
        assertThat(bigboy.knownHosts()).isEqualTo(HOME.resolve(".ssh/known_hosts"));
        assertThat(bigboy.roots()).containsExactly("~/work/co/*", "/home/builder/work/*");
        assertThat(config.hosts().get(1).name().value()).isEqualTo("cloudbb-3");
    }

    /// Only the required keys: every default applies, no host is scanned and GitHub is read unauthenticated.
    @Test
    void parse_appliesDefaults_requiredKeysOnly() {
        var config = parsed(REQUIRED);

        assertThat(config.server().port()).isEqualTo(7171);
        assertThat(config.github().tokenFile().isEmpty()).isTrue();
        assertThat(config.mirror().path()).isEqualTo(HOME.resolve(".local/share/kiln/mirror.git"));
        assertThat(config.scan().hot()).isEqualTo(Duration.ofSeconds(120));
        assertThat(config.scan().hotWindow()).isEqualTo(Duration.ofHours(48));
        assertThat(config.scan().fullAt()).isEqualTo(LocalTime.of(4, 0));
        assertThat(config.scan().sizeGap()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.scan().staleAfter()).isEqualTo(Duration.ofHours(48));
        assertThat(config.scan().stepDeadline()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.scan().transportTimeout()).isEqualTo(Duration.ofMinutes(4));
        assertThat(config.github().cycleDeadline()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.github().hourlyCap()).isEqualTo(1572);
        assertThat(config.github().reserve()).isEqualTo(1500);
        assertThat(config.temper().cycleDeadline()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.mac().roots()).isEmpty();
        assertThat(config.hosts()).isEmpty();
    }

    /// PR #108 review F2: without a `[conflicts]` header the watch is off, so an upgraded kiln starts no git process
    /// against the mirror and appends to no events.log.
    @Test
    void parse_conflicts_absentSection_isOff() {
        assertThat(parsed(REQUIRED).conflicts().isEmpty()).isTrue();
    }

    /// temper #100: the header alone turns the watch on with every default (the first base, the log beside the merge
    /// runner's, kiln's own scratch); each key overrides its default.
    @Test
    void parse_conflicts_emptySection_isOnWithDefaults_andReadsItWhenSet() {
        var defaults = parsed(REQUIRED + "\n[conflicts]\n").conflicts().unwrap();

        assertThat(defaults.branch()).isEqualTo(parsed(REQUIRED).github().bases().getFirst());
        assertThat(defaults.eventsLog()).isEqualTo(HOME.resolve("temper/conflicts/events.log"));
        assertThat(defaults.scratch()).isEqualTo(HOME.resolve(".local/share/kiln/conflicts-scratch"));
        var set = parsed(REQUIRED
                        + "\n[conflicts]\nbranch = \"release-x\"\neventsLog = \"/var/c.log\"\nscratch = \"~/s\"\n").conflicts()
                        .unwrap();

        assertThat(set.branch().value()).isEqualTo("release-x");
        assertThat(set.eventsLog()).isEqualTo(Path.of("/var/c.log"));
        assertThat(set.scratch()).isEqualTo(HOME.resolve("s"));
        assertThat(refused(REQUIRED + "\n[conflicts]\nbogus = 1\n").message()).contains("bogus");
        assertThat(refused(REQUIRED + "\n[conflicts]\nbranch = \"-x\"\n").message()).isNotEmpty();
    }

    /// The budget keys are read when set.
    @Test
    void parse_readsTheBudgetKeys() {
        var github = parsed(edit("cycleDeadlineSeconds = 300",
                                 "cycleDeadlineSeconds = 300\nhourlyCap = 700\nreserve = 2000")).github();

        assertThat(github.hourlyCap()).isEqualTo(700);
        assertThat(github.reserve()).isEqualTo(2000);
    }

    private static final String APP = """

        [github.app]
        clientId = "Iv23abc"
        installationId = 12345678
        keyFile = "~/.config/kiln/app-key.pem"
        """;

    /// `[github.app]` is read whole; without the table there is no App, and `anonymous` defaults to false.
    @Test
    void parse_readsTheAppTable_andTheAnonymousFlag() {
        var github = parsed(FULL + APP).github();

        assertThat(github.app().unwrap()).isEqualTo(KilnConfig.App.app("Iv23abc",
                                                                       12345678L,
                                                                       HOME.resolve(".config/kiln/app-key.pem")));
        assertThat(parsed(FULL).github().app().isEmpty()).isTrue();
        assertThat(parsed(FULL).github().anonymous()).isFalse();
        assertThat(parsed(edit("tokenFile = \"~/.config/kiln/github-token\"", "anonymous = true")).github().anonymous()).isTrue();
    }

    /// GitHub installation ids outgrow `int`. Mutation: read `installationId` through `TomlValues.integer`.
    @Test
    void parse_readsAnInstallationIdAbove2Pow31() {
        var github = parsed(FULL + APP.replace("installationId = 12345678", "installationId = 5000000000")).github();

        assertThat(github.app().unwrap().installationId()).isEqualTo(5_000_000_000L);
    }

    @Test
    void parse_refusesABrokenAppTable_andANonBooleanAnonymous() {
        assertThat(refused(FULL + APP.replace("clientId = \"Iv23abc\"\n", "")).message()).contains("Missing [github.app] clientId");
        assertThat(refused(FULL + APP.replace("installationId = 12345678", "installationId = \"x\"")).message()).contains("Invalid [github.app] installationId");
        assertThat(refused(FULL + APP + "token = \"x\"\n").message()).contains("Unknown key [github.app] token");
        assertThat(refused(edit("tokenFile = \"~/.config/kiln/github-token\"", "anonymous = \"yes\"")).message()).contains("Invalid [github] anonymous: must be a boolean");
    }

    /// A blank `tokenFile` counts as absent: unauthenticated, not a refusal.
    @Test
    void parse_blankTokenFile_readsUnauthenticated() {
        assertThat(parsed(edit("tokenFile = \"~/.config/kiln/github-token\"", "tokenFile = \"\"")).github()
                         .tokenFile()
                         .isEmpty()).isTrue();
    }

    /// Owner 2026-11-05: `bind` is one IPv4 address, `hostNames` its extra Host names (lower-cased); neither changes the port.
    @Test
    void parse_bindAndHostNames_areRead() {
        var server = parsed(edit("port = 7272",
                                 "port = 7272\nbind = \"192.0.2.71\"\nhostNames = [\"BigBoy\", \"kiln.lan\", \"bigboy\"]")).server();

        assertThat(server.port()).isEqualTo(7272);
        assertThat(server.bind().or("none")).isEqualTo("192.0.2.71");
        assertThat(server.hostNames()).containsExactly("bigboy", "kiln.lan");
        assertThat(parsed(edit("port = 7272", "port = 7272\nbind = \"198.51.100.5\"\nhostNames = []")).server().hostNames()).isEmpty();
        assertThat(parsed(FULL).server().bind().isEmpty()).as("no bind: loopback only, as before").isTrue();
        assertThat(parsed(FULL).server().hostNames()).isEmpty();
    }

    /// The refusals are [ConfigError.Listen], which `Main.exitCode` maps to 78 (MainTest).
    @Test
    void parse_refusedBind_isAListenRefusal() {
        assertThat(refused(edit("port = 7272", "port = 7272\nbind = \"0.0.0.0\""))).isInstanceOf(ConfigError.Listen.class);
        assertThat(refused(edit("port = 7272", "port = 7272\nhostNames = [\"a\"]"))).isInstanceOf(ConfigError.Listen.class);
    }

    @Test
    void parse_bounds_areInclusive() {
        assertThat(parsed(edit("port = 7272", "port = 1")).server().port()).isEqualTo(1);
        assertThat(parsed(edit("port = 7272", "port = 65535")).server().port()).isEqualTo(65535);
        assertThat(parsed(edit("hotSeconds = 90", "hotSeconds = 1")).scan().hot()).isEqualTo(Duration.ofSeconds(1));
        assertThat(parsed(edit("port = 55442", "port = 65535")).db().port()).isEqualTo(65535);
        assertThat(parsed(edit("url = \"http://127.0.0.1:7070\"", "url = \"http://127.0.0.1:65535\"")).temper()
                         .url()
                         .uri()
                         .getPort()).isEqualTo(65535);
        assertThat(parsed(edit("url = \"http://127.0.0.1:7070\"", "url = \"http://127.0.0.1:1\"")).temper()
                         .url()
                         .uri()
                         .getPort()).isEqualTo(1);
    }

    /// `owner/name`, each part `[A-Za-z0-9._-]+` not starting with `-` or `.`; dots and dashes inside are kept.
    /// CI interface T1 WP2: `[[repos]]` adds repositories after `[github] repo`, which alone is the one-element list.
    @Test
    void parse_readsReposTables_afterTheGithubRepo() {
        var single = parsed(REQUIRED);
        var two = parsed(REQUIRED
                        + """
            [[repos]]
            repo = "pragmaticalabs/temper-control"
            bases = ["main", "release-control"]
            """);

        assertThat(single.github().repos()).extracting(KilnConfig.Repo::name).containsExactly("o/r");
        assertThat(two.github().repos()).extracting(KilnConfig.Repo::name)
                  .containsExactly("o/r", "pragmaticalabs/temper-control");
        assertThat(two.github().others().getFirst().bases()).extracting(BranchName::value)
                  .containsExactly("main", "release-control");
    }

    @Test
    void parse_refusesABadReposTable() {
        assertThat(refused(REQUIRED + "[[repos]]\nrepo = \"o/r\"\nbases = [\"main\"]\n").message()).contains("listed twice");
        assertThat(refused(REQUIRED + "[[repos]]\nrepo = \"bad\"\nbases = [\"main\"]\n").message()).contains("repos");
        assertThat(refused(REQUIRED + "[[repos]]\nrepo = \"a/b\"\nbases = []\n").message()).contains("repos");
        assertThat(refused(REQUIRED + "[[repos]]\nrepo = \"a/b\"\nbases = [\"main\"]\ntoken = \"x\"\n").message()).contains("token");
        assertThat(refused(REQUIRED + "[[other]]\nx = 1\n").message()).contains("other");
    }

    @Test
    void parse_acceptsRepo_withDotsAndDashesInside() {
        assertThat(parsed(edit("repo = \"pragmaticalabs/pragmatica\"", "repo = \"a_b-c.d/x.y-z_\"")).github().repo()).isEqualTo("a_b-c.d/x.y-z_");
    }

    private static final String ROOTS = "roots = [\"~/work/co/*\", \"/home/builder/work/*\"]";
    private static final String PASSWORD = "passwordFile = \"~/.config/kiln/pg-password\"";

    static Stream<Arguments> refusals() {
        return Stream.of(arguments(ROOTS, "roots = ['/w/a?']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/[ab]']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/{a,b}']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/a;b']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/$(id)']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/`id`']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = [\"/w/a'b\"]", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/a\"b']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/a|b']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/a&b']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/a\\b']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = [\"/w/a\\u200Bb\"]", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/é']", "Invalid [hosts] roots"),
                         arguments(ROOTS, "roots = ['/w/a=b']", "Invalid [hosts] roots"),
                         arguments(PASSWORD, "passwordFile = \"/etc/../x\"", "Invalid [db] passwordFile"),
                         arguments(PASSWORD, "passwordFile = \"~/../../etc/x\"", "Invalid [db] passwordFile"),
                         arguments(PASSWORD, "passwordFile = \"/a/./b\"", "Invalid [db] passwordFile"),
                         arguments(PASSWORD, "passwordFile = \"/a//b\"", "Invalid [db] passwordFile"),
                         arguments(PASSWORD, "passwordFile = \"/a/b/\"", "Invalid [db] passwordFile"),
                         arguments(PASSWORD, "passwordFile = \"~/\"", "Invalid [db] passwordFile"),
                         arguments(PASSWORD, "passwordFile = \"/\"", "Invalid [db] passwordFile"),
                         arguments(PASSWORD, "passwordFile = \"~/.\"", "Invalid [db] passwordFile"),
                         arguments("path = \"/srv/kiln/mirror.git\"",
                                   "path = \"/srv/../mirror.git\"",
                                   "Invalid [mirror] path"),
                         arguments("tokenFile = \"~/.config/kiln/github-token\"",
                                   "tokenFile = \"~/x/..\"",
                                   "Invalid [github] tokenFile"),
                         arguments("port = 7272", "port = 0", "Invalid [server] port"),
                         arguments("port = 7272", "port = 65536", "Invalid [server] port"),
                         arguments("port = 7272", "port = \"7272\"", "Invalid [server] port: must be an integer"),
                         arguments("port = 7272", "port = 99999999999", "Invalid [server] port"),
                         arguments("port = 7272", "port = 7272.0", "Invalid [server] port: must be an integer"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"127.0.0.1\"",
                                   "Invalid [server] bind: a loopback address is served already"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"127.0.0.2\"",
                                   "Invalid [server] bind: a loopback address is served already"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"0.0.0.0\"",
                                   "Invalid [server] bind: the wildcard address is refused"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"::\"",
                                   "Invalid [server] bind: must be a literal IPv4 address"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"::1\"",
                                   "Invalid [server] bind: must be a literal IPv4 address"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"bigboy\"",
                                   "Invalid [server] bind: must be a literal IPv4 address"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"192.0.2.256\"",
                                   "Invalid [server] bind: must be a literal IPv4 address"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"192.0.2\"",
                                   "Invalid [server] bind: must be a literal IPv4 address"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"192.000.002.071\"",
                                   "Invalid [server] bind: must be a literal IPv4 address"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"\"",
                                   "Invalid [server] bind: must be a literal IPv4 address"),
                         arguments("port = 7272", "port = 7272\nbind = 1", "Invalid [server] bind: must be a string"),
                         arguments("port = 7272",
                                   "port = 7272\nhostNames = [\"bigboy\"]",
                                   "Invalid [server] hostNames: needs [server] bind"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"192.0.2.71\"\nhostNames = [\"big boy\"]",
                                   "Invalid [server] hostNames: not a host name"),
                         arguments("port = 7272",
                                   "port = 7272\nbind = \"192.0.2.71\"\nhostNames = \"bigboy\"",
                                   "Invalid [server] hostNames: must be a list of strings"),
                         arguments("host = \"127.0.0.1\"\nport = 55442", "port = 55442", "Missing [db] host"),
                         arguments("port = 55442", "port = 0", "Invalid [db] port"),
                         arguments("port = 55442\n", "", "Missing [db] port"),
                         arguments("name = \"kiln\"\n", "name = \"  \"\n", "Invalid [db] name"),
                         arguments("user = \"kiln\"\n", "user = 1\n", "Invalid [db] user: must be a string"),
                         arguments("passwordFile = \"~/.config/kiln/pg-password\"\n", "", "Missing [db] passwordFile"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"",
                                   "repo = \"pragmatica\"",
                                   "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \"a/b/c\"", "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"\n", "", "Missing [github] repo"),
                         arguments("bases = [\"release-1.0.0-rc5\", \"main\"]",
                                   "bases = []",
                                   "Invalid [github] bases: must not be empty"),
                         arguments("bases = [\"release-1.0.0-rc5\", \"main\"]",
                                   "bases = [\"main\", \"-x\"]",
                                   "Invalid [github] bases: Invalid branch: '-x'"),
                         arguments("bases = [\"release-1.0.0-rc5\", \"main\"]",
                                   "bases = \"main\"",
                                   "Invalid [github] bases: must be a list of strings"),
                         arguments("bases = [\"release-1.0.0-rc5\", \"main\"]",
                                   "bases = [1]",
                                   "Invalid [github] bases: must be a list of strings"),
                         arguments("bases = [\"release-1.0.0-rc5\", \"main\"]\n", "", "Missing [github] bases"),
                         arguments("tokenFile = \"~/.config/kiln/github-token\"",
                                   "tokenFile = 1",
                                   "Invalid [github] tokenFile: must be a string"),
                         arguments("origin = \"https://github.com/pragmaticalabs/pragmatica.git\"",
                                   "origin = \"-oX\"",
                                   "Invalid [mirror] origin"),
                         arguments("origin = \"https://github.com/pragmaticalabs/pragmatica.git\"\n",
                                   "",
                                   "Missing [mirror] origin"),
                         arguments("path = \"/srv/kiln/mirror.git\"",
                                   "path = 1",
                                   "Invalid [mirror] path: must be a string"),
                         arguments("url = \"http://127.0.0.1:7070\"",
                                   "url = \"ftp://127.0.0.1\"",
                                   "Invalid [temper] url"),
                         arguments("url = \"http://127.0.0.1:7070\"", "url = \"127.0.0.1:7070\"", "Invalid [temper] url"),
                         arguments("url = \"http://127.0.0.1:7070\"", "url = \"http://\"", "Invalid [temper] url"),
                         arguments("url = \"http://127.0.0.1:7070\"", "url = \"http://a b\"", "Invalid [temper] url"),
                         arguments("url = \"http://127.0.0.1:7070\"", "url = \"http:///api\"", "Invalid [temper] url"),
                         arguments("url = \"http://127.0.0.1:7070\"\n", "", "Missing [temper] url"),
                         arguments("readTokenFile = \"~/.config/temper/read-token\"\n",
                                   "",
                                   "Missing [temper] readTokenFile"),
                         arguments("hotSeconds = 90", "hotSeconds = 0", "Invalid [scan] hotSeconds"),
                         arguments("hotWindowHours = 24", "hotWindowHours = -1", "Invalid [scan] hotWindowHours"),
                         arguments("sizeGapSeconds = 7",
                                   "sizeGapSeconds = \"7\"",
                                   "Invalid [scan] sizeGapSeconds: must be an integer"),
                         arguments("staleAfterHours = 72", "staleAfterHours = 0", "Invalid [scan] staleAfterHours"),
                         arguments("fullAt = \"03:15\"", "fullAt = \"25:00\"", "Invalid [scan] fullAt"),
                         arguments("fullAt = \"03:15\"", "fullAt = 4", "Invalid [scan] fullAt: must be a string"),
                         arguments("name = \"bigboy\"",
                                   "name = \"mac\"",
                                   "Invalid [hosts] name: Invalid host name: 'mac'"),
                         arguments("name = \"bigboy\"", "name = \"Bigboy\"", "Invalid [hosts] name"),
                         arguments("name = \"bigboy\"",
                                   "name = \"cloudbb-3\"",
                                   "Invalid [hosts] name: two hosts share a name"),
                         arguments("name = \"bigboy\"\n", "", "Missing [hosts] name"),
                         arguments("address = \"127.0.0.1\"",
                                   "address = \"-oProxyCommand=x\"",
                                   "Invalid [hosts] address"),
                         arguments("address = \"127.0.0.1\"\n", "", "Missing [hosts] address"),
                         arguments("user = \"builder\"\nkey = \"~/.ssh/kiln_scan\"\nknownHosts = \"~/.ssh/known_hosts\"\nroots = [\"~/work/co/*\", ",
                                   "user = \"-oX\"\nkey = \"~/.ssh/kiln_scan\"\nknownHosts = \"~/.ssh/known_hosts\"\nroots = [\"~/work/co/*\", ",
                                   "Invalid [hosts] user"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = []",
                                   "Invalid [hosts] roots: must not be empty"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]\n", "", "Missing [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"work/co\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~/../x\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~/work/..\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~/a b\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~user/x\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/w/\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/*\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/a*\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/*/b\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~/*\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~/a*/b\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/w/**\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/w/a**\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~/**/x\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/a/./b\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/a/.\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/a//b\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~/\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~//a\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"~/x\\u2028/../../etc\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/a/../b\"]",
                                   "Invalid [hosts] roots"),
                         arguments("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                   "roots = [\"/a\\u0085b\"]",
                                   "Invalid [hosts] roots"),
                         arguments("bases = [\"release-1.0.0-rc5\", \"main\"]",
                                   "bases = [\"main\", \"main\"]",
                                   "Invalid [github] bases: a base is listed twice"),
                         arguments("bases = [\"release-1.0.0-rc5\", \"main\"]",
                                   "bases = [\"main:refs/heads/evil\"]",
                                   "Invalid [github] bases: Invalid branch"),
                         arguments("bases = [\"release-1.0.0-rc5\", \"main\"]",
                                   "bases = [\"+main\"]",
                                   "Invalid [github] bases: Invalid branch"),
                         arguments("port = 55442", "port = 65536", "Invalid [db] port"),
                         arguments("url = \"http://127.0.0.1:7070\"",
                                   "url = \"http://127.0.0.1:99999\"",
                                   "Invalid [temper] url"),
                         arguments("url = \"http://127.0.0.1:7070\"",
                                   "url = \"http://127.0.0.1:65536\"",
                                   "Invalid [temper] url"),
                         arguments("url = \"http://127.0.0.1:7070\"",
                                   "url = \"http://127.0.0.1:0\"",
                                   "Invalid [temper] url"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \"../x\"", "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \"./.\"", "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \"-o/x\"", "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \"a/..\"", "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \".a/b\"", "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \"a/-b\"", "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \"a/.b\"", "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \"a/\"", "Invalid [github] repo"),
                         arguments("repo = \"pragmaticalabs/pragmatica\"", "repo = \"/b\"", "Invalid [github] repo"),
                         arguments("passwordFile = \"~/.config/kiln/pg-password\"",
                                   "passwordFile = \"pg-password\"",
                                   "Invalid [db] passwordFile"),
                         arguments("passwordFile = \"~/.config/kiln/pg-password\"",
                                   "passwordFile = \"./pg\"",
                                   "Invalid [db] passwordFile"),
                         arguments("passwordFile = \"~/.config/kiln/pg-password\"",
                                   "passwordFile = \"~user/pg\"",
                                   "Invalid [db] passwordFile"),
                         arguments("passwordFile = \"~/.config/kiln/pg-password\"",
                                   "passwordFile = \"../pg\"",
                                   "Invalid [db] passwordFile"),
                         arguments("passwordFile = \"~/.config/kiln/pg-password\"",
                                   "passwordFile = \"~\"",
                                   "Invalid [db] passwordFile"),
                         arguments("passwordFile = \"~/.config/kiln/pg-password\"",
                                   "passwordFile = \"/a\\u0000b\"",
                                   "Invalid [db] passwordFile"),
                         arguments("tokenFile = \"~/.config/kiln/github-token\"",
                                   "tokenFile = \"github-token\"",
                                   "Invalid [github] tokenFile"),
                         arguments("path = \"/srv/kiln/mirror.git\"", "path = \"mirror.git\"", "Invalid [mirror] path"),
                         arguments("readTokenFile = \"~/.config/temper/read-token\"",
                                   "readTokenFile = \"~temper/read-token\"",
                                   "Invalid [temper] readTokenFile"),
                         arguments("user = \"builder\"\nkey = \"~/.ssh/kiln_scan\"\nknownHosts = \"~/.ssh/known_hosts\"\nroots = [\"~/work/co/*\", ",
                                   "user = \"builder\"\nkey = \"kiln_scan\"\nknownHosts = \"~/.ssh/known_hosts\"\nroots = [\"~/work/co/*\", ",
                                   "Invalid [hosts] key"),
                         arguments("user = \"builder\"\nkey = \"~/.ssh/kiln_scan\"\nknownHosts = \"~/.ssh/known_hosts\"\nroots = [\"~/work/co/*\", ",
                                   "user = \"builder\"\nkey = \"~/.ssh/kiln_scan\"\nknownHosts = \".ssh/known_hosts\"\nroots = [\"~/work/co/*\", ",
                                   "Invalid [hosts] knownHosts"),
                         arguments("tokenFile = \"~/.config/kiln/github-token\"",
                                   "tokenfile = \"~/.config/kiln/github-token\"",
                                   "Unknown key [github] tokenfile"),
                         arguments("[github]", "[githb]", "Unknown section [githb]"),
                         arguments("port = 7272",
                                   "port = 7272\nopts = { bind = \"0.0.0.0\" }",
                                   "Unknown key [server] opts"),
                         arguments("port = 7272", "port = 7272\nx.bind = \"0.0.0.0\"", "Unknown section [server.x]"),
                         arguments("staleAfterHours = 72",
                                   "staleAfterHours = 72\nstaleAfterDays = 2",
                                   "Unknown key [scan] staleAfterDays"),
                         arguments("user = \"builder\"\nkey = \"~/.ssh/kiln_scan\"\nknownHosts = \"~/.ssh/known_hosts\"\nroots = [\"~/work/co/*\", ",
                                   "user = \"builder\"\nkey = \"~/.ssh/kiln_scan\"\nknownhosts = \"~/.ssh/known_hosts\"\nroots = [\"~/work/co/*\", ",
                                   "Unknown key [hosts] knownhosts"),
                         arguments("[server]\nport = 7272",
                                   "server = { port = 7272, bind = \"0.0.0.0\" }",
                                   "Unknown top-level key server"),
                         arguments("[server]\nport = 7272", "server = { port = 7272 }", "Unknown top-level key server"),
                         arguments("[server]\nport = 7272", "port = 7272", "Unknown top-level key port"),
                         arguments("[[hosts]]\nname = \"cloudbb-3\"",
                                   "[[host]]\nname = \"cloudbb-3\"",
                                   "Unknown section [host]"),

        // WP9 open item 7: the transport timeout below half the step deadline, the boundary refused
        arguments("transportTimeoutSeconds = 449",
                  "transportTimeoutSeconds = 450",
                  "Invalid [scan] transportTimeoutSeconds: must be below half of stepDeadlineSeconds"),
                         arguments("transportTimeoutSeconds = 449",
                                   "transportTimeoutSeconds = 900",
                                   "Invalid [scan] transportTimeoutSeconds: must be below half of stepDeadlineSeconds"),
                         arguments("stepDeadlineSeconds = 900",
                                   "stepDeadlineSeconds = 898",
                                   "Invalid [scan] transportTimeoutSeconds: must be below half of stepDeadlineSeconds"),
                         arguments("stepDeadlineSeconds = 900",
                                   "stepDeadlineSeconds = 0",
                                   "Invalid [scan] stepDeadlineSeconds"),
                         arguments("transportTimeoutSeconds = 449",
                                   "transportTimeoutSeconds = 0",
                                   "Invalid [scan] transportTimeoutSeconds"),
                         arguments("cycleDeadlineSeconds = 300",
                                   "cycleDeadlineSeconds = 0",
                                   "Invalid [github] cycleDeadlineSeconds"),
                         arguments("cycleDeadlineSeconds = 300",
                                   "cycleDeadlineSeconds = 300\nhourlyCap = 0",
                                   "Invalid [github] hourlyCap"),
                         arguments("cycleDeadlineSeconds = 300",
                                   "cycleDeadlineSeconds = 300\nhourlyCap = -5",
                                   "Invalid [github] hourlyCap"),
                         arguments("cycleDeadlineSeconds = 300",
                                   "cycleDeadlineSeconds = 300\nhourlyCap = \"many\"",
                                   "Invalid [github] hourlyCap: must be an integer"),
                         arguments("cycleDeadlineSeconds = 300",
                                   "cycleDeadlineSeconds = 300\nreserve = 0",
                                   "Invalid [github] reserve"),
                         arguments("cycleDeadlineSeconds = 300",
                                   "cycleDeadlineSeconds = 300\nreserve = -1",
                                   "Invalid [github] reserve"),
                         arguments("cycleDeadlineSeconds = 120",
                                   "cycleDeadlineSeconds = \"2m\"",
                                   "Invalid [temper] cycleDeadlineSeconds: must be an integer"),

        // WP9 open item 8: the Mac's roots are checked as a host's
        arguments("roots = [\"~/src/pragmatica*\", ", "roots = [\"/*\", ", "Invalid [mac] roots"),
                         arguments("roots = [\"~/src/pragmatica*\", ",
                                   "roots = [\"rel/x\", ",
                                   "Invalid [mac] roots"),
                         arguments("[mac]\nroots", "[mac]\nroot", "Unknown key [mac] root"),
                         arguments("[mac]\nroots = [\"~/src/pragmatica*\", \"/private/tmp/scratch-501/*/*/work/*\"]",
                                   "[mac]\nroots = \"~/x\"",
                                   "Invalid [mac] roots: must be a list of strings"));
    }

    /// `Result.all` gathers failures into a composite whose message lists each one; exactly one is expected per edit.
    @ParameterizedTest
    @MethodSource("refusals")
    void parse_refuses_andNamesSectionAndKey(String from, String to, String message) {
        var text = refused(edit(from, to)).message();

        assertThat(text).contains(message);
        assertThat(ISSUE.matcher(text).results().count()).isEqualTo(1);
    }

    /// The roots kiln accepts (§9.2): absolute or `~/`, a `*` in any component after the first, the Mac push root, a
    /// name merely holding two dots.
    @Test
    void parse_acceptsRoots() {
        var roots = parsed(edit("roots = [\"~/work/co/*\", \"/home/builder/work/*\"]",
                                "roots = [\"~/src/pragmatica*\", \"/srv/a..b\", \"~/x\", \"/w/*.git\", \"~/a/*/b\", "
                               + "\"/home/*/work\", \"/private/tmp/scratch-501/*/*/work/*\", \"/w/a*b*\", \"/w/a@b+c_d-e.f\"]")).hosts()
                          .getFirst()
                          .roots();

        assertThat(roots).containsExactly("~/src/pragmatica*",
                                          "/srv/a..b",
                                          "~/x",
                                          "/w/*.git",
                                          "~/a/*/b",
                                          "/home/*/work",
                                          "/private/tmp/scratch-501/*/*/work/*",
                                          "/w/a*b*",
                                          "/w/a@b+c_d-e.f");
    }

    /// Verification F-5: an inline `[server]` table is refused, never read with its `bind` dropped; the same holds for
    /// every section written inline, and for `[[hosts]]` written as an inline array.
    @ParameterizedTest
    @MethodSource("inlineTables")
    void parse_refusesInlineTables(String toml, String message) {
        assertThat(refused(toml).message()).contains(message);
    }

    static Stream<Arguments> inlineTables() {
        return Stream.of(arguments(REQUIRED + "server = { port = 7272, bind = \"0.0.0.0\" }\n",
                                   "Unknown key [temper] server"),
                         arguments("server = { port = 7272, bind = \"0.0.0.0\" }\n" + REQUIRED,
                                   "Unknown top-level key server"),
                         arguments("db = { port = 1 }\n" + REQUIRED, "Unknown top-level key db"),
                         arguments("scan = { hotSeconds = 0 }\n" + REQUIRED, "Unknown top-level key scan"),
                         arguments("hosts = [{ name = \"x\" }]\n" + REQUIRED, "Unknown top-level key hosts"),
                         arguments(REQUIRED.replace("[github]\nrepo = \"o/r\"\nbases = [\"main\"]\n", "")
                                  + "[mirror2]\n",
                                   "Unknown section [mirror2]"));
    }

    /// Verification F-6: `[hosts]` as one table would read zero hosts; it is an unknown section, not an empty list.
    @Test
    void parse_refusesHostsAsASingleTable() {
        var toml = REQUIRED
                 + """
            [hosts]
            name = "bigboy"
            address = "127.0.0.1"
            """;

        assertThat(refused(toml).message()).contains("Unknown section [hosts]");
    }

    /// Every unknown is reported at once, not only the first.
    @Test
    void parse_reportsEveryUnknown() {
        var text = refused(edit("[github]", "[githb]").replace("hotSeconds", "hotsecond")).message();

        assertThat(text).contains("Unknown section [githb]", "Unknown key [scan] hotsecond");
    }

    @Test
    void load_refusesAnUnreadableFile(@TempDir Path dir) {
        var missing = dir.resolve("kiln.toml");
        Cause cause = KilnConfigLoader.load(missing, HOME).fold(failure -> failure,
                                                                value -> Assertions.fail("expected a failure, got " + value));

        assertThat(cause).isInstanceOf(ConfigError.Unreadable.class);
    }

    @Test
    void load_readsTheFile(@TempDir Path dir) throws Exception {
        var file = Files.writeString(dir.resolve("kiln.toml"), FULL);

        assertThat(KilnConfigLoader.load(file, HOME).unwrap().server().port()).isEqualTo(7272);
    }

    /// WP9 open item 7: the defaults keep the transport below half the step (240 s < 300 s), and a step deadline raised
    /// alone keeps the default transport valid; the largest valid transport is half the step minus one second.
    @Test
    void parse_transportTimeout_justBelowHalfTheStep_isAccepted() {
        var config = parsed(edit("stepDeadlineSeconds = 900\ntransportTimeoutSeconds = 449", "stepDeadlineSeconds = 481"));

        assertThat(config.scan().transportTimeout()).isEqualTo(Duration.ofSeconds(240));
        assertThat(parse(edit("stepDeadlineSeconds = 900\ntransportTimeoutSeconds = 449", "stepDeadlineSeconds = 480")).isFailure()).isTrue();
    }
}
