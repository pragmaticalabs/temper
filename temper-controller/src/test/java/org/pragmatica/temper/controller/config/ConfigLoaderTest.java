package org.pragmatica.temper.controller.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.temper.controller.host.LinkTiming;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;


class ConfigLoaderTest {
    private static final Path HOME = Path.of("/home/builder");
    private static final String BUILD = "0.1.0-build";

    static final String FULL = """
        [server]
        bind = "192.0.2.10"
        port = 7070
        tokenFile = "~/.config/temper/token"
        [db]
        host = "127.0.0.1"
        port = 55432
        name = "temper"
        user = "temper"
        passwordFile = "~/.config/temper/pg-password"
        [repo]
        origin = "git@github.com:pragmaticalabs/pragmatica.git"
        mirror = "~/.temper/controller-mirror.git"
        [github]
        anonymous = true
        [scheduler]
        intervalSeconds = 2
        starvationSeconds = 1200
        leaseSeconds = 30
        hostLostSeconds = 90
        retireWindowSeconds = 300
        [units]
        memoryMb = 6144
        cpuShares = 1024
        diskFloorGiB = 50
        mavenOpts = ""
        tcHubPrefix = "mirror.local/"
        [portGroup]
        modules = ["aether/ember", "aether/forge/forge-tests"]
        [worker]
        jar = "/opt/temper/temper-worker-0.1.0.jar"
        [[hosts]]
        name = "bigboy"
        address = "localhost"
        user = "builder"
        key = "~/.temper/temper_worker"
        knownHosts = "~/.temper/known_hosts"
        cap = 5
        memoryBudgetMb = 20480
        portIsolated = false
        """;

    private static ControllerConfig parse(String toml) {
        return TomlParser.parse(toml)
                         .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                         .unwrap();
    }

    /// D-6 A1 (owner ruling): the dedup key's runner component is the whole build version, judge and suffix included,
    /// so builds of one `project.version` and one commit, clean, `-dirty` and `+unjudged`, never share a key.
    @Test
    void dedupRunner_isTheWholeBuildVersion_judgeAndSuffixIncluded() {
        var runners = Stream.of("0.1.0-SNAPSHOT+0123456789ab",
                                "0.1.0-SNAPSHOT+0123456789ab-dirty",
                                "0.1.0-SNAPSHOT+fedcba987654",
                                "0.1.0-SNAPSHOT+unjudged")
                            .map(version -> TomlParser.parse(FULL)
                                                      .flatMap(doc -> ConfigLoader.parse(doc, HOME, version))
                                                      .unwrap()
                                                      .dedupRunner())
                            .toList();

        assertThat(runners).containsExactly("0.1.0-SNAPSHOT+0123456789ab",
                                            "0.1.0-SNAPSHOT+0123456789ab-dirty",
                                            "0.1.0-SNAPSHOT+fedcba987654",
                                            "0.1.0-SNAPSHOT+unjudged");
    }

    @Test
    void parse_readsEverySection_fullConfig() {
        var config = parse(FULL);

        assertThat(config.version()).as("the build's version, not a configured one").isEqualTo(BUILD);
        assertThat(config.server().port()).isEqualTo(7070);
        assertThat(config.server().tokenFile()).isEqualTo(HOME.resolve(".config/temper/token"));
        assertThat(config.db().port()).isEqualTo(55432);
        assertThat(config.repo().mirror()).isEqualTo(HOME.resolve(".temper/controller-mirror.git"));
        assertThat(config.scheduler().starvation()).isEqualTo(Duration.ofSeconds(1200));
        assertThat(config.scheduler().hostLost()).as("m4-design §2.1: [scheduler] hostLostSeconds")
                  .isEqualTo(Duration.ofSeconds(90));
        assertThat(config.scheduler().retireWindow()).as("#107: [scheduler] retireWindowSeconds override")
                  .isEqualTo(Duration.ofSeconds(300));
        assertThat(config.units().memory().value()).isEqualTo(6144);
        assertThat(config.units().diskFloorBytes()).isEqualTo(50L * 1024 * 1024 * 1024);
        assertThat(config.units().mavenOpts().isEmpty()).isTrue();
        assertThat(config.units().tcHubPrefix().or("")).isEqualTo("mirror.local/");
        assertThat(config.portGroup().modules()).containsExactly("aether/ember", "aether/forge/forge-tests");
        assertThat(config.portGroup().owner().value()).isEqualTo("temper");
        assertThat(config.portGroup().maxHolding()).isEqualTo(1);
        assertThat(config.units().gateMemory().value()).isEqualTo(16384);
        assertThat(config.evidence().root()).isEqualTo(HOME.resolve(".local/share/temper/evidence"));
        assertThat(config.evidence().maxSmallBytes()).isEqualTo(256L * 1024 * 1024);
        assertThat(config.retention().interval()).isEqualTo(Duration.ofMinutes(60));
        assertThat(config.pgDump().at()).isEqualTo(LocalTime.of(3, 30));
        assertThat(config.pgDump().container()).isEqualTo("temper-pg");
        assertThat(config.worker().jar()).isEqualTo(Path.of("/opt/temper/temper-worker-0.1.0.jar"));
        assertThat(config.hosts()).hasSize(1);
        assertThat(config.hosts().getFirst().name().value()).isEqualTo("bigboy");
        assertThat(config.hosts().getFirst().cap()).isEqualTo(5);
        assertThat(config.hosts().getFirst().key()).isEqualTo(HOME.resolve(".temper/temper_worker"));
    }

    @Test
    void parse_emptyPortGroupList_isAllowed() {
        assertThat(parse(FULL.replace("modules = [\"aether/ember\", \"aether/forge/forge-tests\"]", "modules = []")).portGroup()
                        .modules()).isEqualTo(List.of());
    }

    @Test
    void parse_readsM1Sections_whenSet() {
        var config = parse(FULL
                          + """
            [evidence]
            root = "/srv/evidence"
            maxSmallMiB = 8
            maxFullMiB = 64
            minFreeGiB = 5
            [retention]
            intervalMinutes = 15
            [pgDump]
            at = "01:05"
            container = "temper-pg-acc"
            """);

        assertThat(config.evidence().root()).isEqualTo(Path.of("/srv/evidence"));
        assertThat(config.evidence().maxFullBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(config.evidence().minFreeBytes()).isEqualTo(5L * 1024 * 1024 * 1024);
        assertThat(config.retention().interval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(config.pgDump().at()).isEqualTo(LocalTime.of(1, 5));
        assertThat(config.pgDump().container()).isEqualTo("temper-pg-acc");
    }

    /// F2: the instance labelling this controller's unit containers; `temper` unless set, and label-safe.
    @Test
    void parse_serverInstance_defaultsToTemper_isConfigurable_andValidated() {
        assertThat(parse(FULL).server().instance().value()).isEqualTo("temper");
        assertThat(parse(FULL.replace("[server]\n", "[server]\ninstance = \"acceptance\"\n")).server()
                        .instance()
                        .value()).isEqualTo("acceptance");
        TomlParser.parse(FULL.replace("[server]\n", "[server]\ninstance = \"a=b\"\n"))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("server"));
    }

    /// D3: `readTokenFile` is optional (absent: no read token, as before D3) and `~`-expanded like `tokenFile`.
    @Test
    void parse_serverReadTokenFile_isOptional_andExpanded() {
        assertThat(parse(FULL).server().readTokenFile().isEmpty()).isTrue();
        assertThat(parse(FULL.replace("[server]\n", "[server]\nreadTokenFile = \"~/.config/temper/read-token\"\n")).server()
                        .readTokenFile()
                        .or(Path.of("absent"))).isEqualTo(HOME.resolve(".config/temper/read-token"));
        assertThat(parse(FULL.replace("[server]\n", "[server]\nreadTokenFile = \"\"\n")).server()
                        .readTokenFile()
                        .isEmpty()).as("blank counts as absent")
                  .isTrue();
    }

    /// A non-string `readTokenFile` is refused, not read as the path `"1"`.
    @Test
    void parse_fails_nonStringReadTokenFile() {
        TomlParser.parse(FULL.replace("[server]\n", "[server]\nreadTokenFile = 1\n"))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("Invalid [server] readTokenFile"));
    }

    @Test
    void parse_fails_badPortGroupOwnerOrDumpTime() {
        TomlParser.parse(FULL.replace("[portGroup]\n", "[portGroup]\nowner = \"bad owner\"\n"))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("portGroup"));
        TomlParser.parse(FULL + "[pgDump]\nat = \"25:99\"\n")
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("pgDump"));
    }

    @Test
    void parse_fails_missingPortGroupModules() {
        TomlParser.parse(FULL.replace("modules = [\"aether/ember\", \"aether/forge/forge-tests\"]", ""))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("portGroup"));
    }

    /// §12.7 item 3: the version comes from the build; a hand-typed one is refused, even when it is well-formed.
    @Test
    void parse_fails_handTypedVersion() {
        TomlParser.parse("version = \"" + BUILD + "\"\n" + FULL)
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("version"));
    }

    @Test
    void parse_fails_optionLikeSshUser() {
        TomlParser.parse(FULL.replace("user = \"builder\"", "user = \"-oProxyCommand=sh\""))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("hosts"));
    }

    @Test
    void parse_fails_optionLikeOrigin() {
        TomlParser.parse(FULL.replace("origin = \"git@github.com", "origin = \"--upload-pack=x git@github.com"))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value));
    }

    @Test
    void parse_fails_noHosts() {
        TomlParser.parse(FULL.substring(0,
                                        FULL.indexOf("[[hosts]]")))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("hosts"));
    }

    @Test
    void parse_refusesRetireWindowSeconds_notPositive() {
        assertThat(TomlParser.parse(FULL.replace("retireWindowSeconds = 300", "retireWindowSeconds = 0"))
                             .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                             .isFailure()).isTrue();
    }

    @Test
    void parse_refusesHostLostSeconds_notPositive() {
        assertThat(TomlParser.parse(FULL.replace("hostLostSeconds = 90", "hostLostSeconds = 0"))
                             .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                             .isFailure()).isTrue();
    }

    /// m4-design §2.1: `hostLostSeconds` must exceed `stableUp`; both boundary values come from the constant the guard
    /// reads, so changing `stableUp` moves this test with it: `stableUp` itself is refused, one second more is taken.
    @Test
    void parse_refusesHostLostSeconds_notAboveStableUp() {
        var stableUp = LinkTiming.DEFAULT.stableUp().toSeconds();

        TomlParser.parse(FULL.replace("hostLostSeconds = 90", "hostLostSeconds = " + stableUp))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("hostLostSeconds (" + stableUp
                                                                          + ") must exceed stableUp (" + stableUp
                                                                          + " s) plus the expected connect time",
                                                                           "first drop after start is LOST at once"));
        assertThat(TomlParser.parse(FULL.replace("hostLostSeconds = 90",
                                                 "hostLostSeconds = " + (stableUp + 1)))
                             .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                             .map(config -> config.scheduler()
                                                  .hostLost())
                             .or(Duration.ZERO)).isEqualTo(Duration.ofSeconds(stableUp + 1));
    }

    @Test
    void parse_appliesDefaults_optionalKeysAbsent() {
        var minimal = FULL.replaceAll("(?m)^(intervalSeconds|starvationSeconds|leaseSeconds|hostLostSeconds|retireWindowSeconds|memoryMb|cpuShares|diskFloorGiB|cap|portIsolated) = .*$",
                                      "");
        var config = parse(minimal);

        assertThat(config.scheduler().interval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.scheduler().lease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.scheduler().hostLost()).as("m4-design §2.1, R-M4-1: 10 min")
                  .isEqualTo(Duration.ofSeconds(600));
        assertThat(config.scheduler().retireWindow()).as("#107: default 10 min").isEqualTo(Duration.ofMinutes(10));
        assertThat(config.units().cpuShares()).isEqualTo(1024);
        assertThat(config.hosts().getFirst().cap()).isEqualTo(5);
        assertThat(config.hosts().getFirst().portIsolated()).isFalse();
        assertThat(config.hosts().getFirst().sharedCpu()).as("m4-design §9.1: a host is dedicated unless it says so")
                  .isFalse();
        assertThat(config.hosts().getFirst().retireAt().isPresent()).as("§10: never retired unless it says so")
                  .isFalse();
        assertThat(config.hosts().getFirst().memFloorGiB()).as("no memory floor unless the host sets one").isZero();
    }

    /// `memFloorGiB`: a whole number of GiB, 0 to 1024 inclusive.
    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "1024"})
    void parse_readsMemFloorGiB_whenSet(String value) {
        var host = parse(FULL.replace("portIsolated = false\n", "portIsolated = false\nmemFloorGiB = " + value + "\n")).hosts()
                        .getFirst();

        assertThat(host.memFloorGiB()).isEqualTo(Integer.parseInt(value));
    }

    /// A negative, fractional, quoted or absurd floor is refused with the reason, never read as another number
    /// (`2.5` as 2, `"6"` as off).
    @ParameterizedTest
    @ValueSource(strings = {"-1|must be from 0 to 1024, was -1", "1025|must be from 0 to 1024, was 1025", "2.5|must be a whole number, was 2.5", "\"6\"|must be a whole number, was \"6\"", "true|must be a whole number, was true"})
    void parse_refusesABadMemFloorGiB_namingTheReason(String testCase) {
        var value = testCase.substring(0, testCase.indexOf('|'));
        var reason = testCase.substring(testCase.indexOf('|') + 1);

        TomlParser.parse(FULL.replace("portIsolated = false\n", "portIsolated = false\nmemFloorGiB = " + value + "\n"))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(config -> Assertions.fail("expected a failure, got " + config))
                  .onFailure(cause -> assertThat(cause.message()).contains("Invalid [hosts] memFloorGiB: " + reason));
    }

    /// S1.15: `[fleet] queueWaitAlarmMin` and `portGroupHeldAlarmMin` absent → 60 and 120;
    /// 1 and 10080 are accepted. Mutation: a default other than 60/120, or a range that refuses an end → red.
    @ParameterizedTest
    @ValueSource(strings = {"1", "10080", "200"})
    void s1_15_parse_readsTheAlarmLimits_whenSet(String value) {
        var fleet = parse(FULL + "[fleet]\nqueueWaitAlarmMin = " + value + "\nportGroupHeldAlarmMin = " + value + "\n").fleet();

        assertThat(fleet.queueWaitAlarmMin()).isEqualTo(Integer.parseInt(value));
        assertThat(fleet.portGroupHeldAlarmMin()).isEqualTo(Integer.parseInt(value));
        assertThat(parse(FULL).fleet().queueWaitAlarmMin()).as("absent").isEqualTo(60);
        assertThat(parse(FULL).fleet().portGroupHeldAlarmMin()).as("absent").isEqualTo(120);
    }

    /// S1.15: 0, -1, 10081, a string, a float and a boolean each refuse the start, naming the section, the key and the
    /// reason. There is no off switch. Mutation: accept 0 → the `0` case parses, red.
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10081", "\"60\"", "1.5", "true"})
    void s1_15_parse_refusesABadAlarmLimit_namingTheKey(String value) {
        for (var key : List.of("queueWaitAlarmMin", "portGroupHeldAlarmMin")) {
            TomlParser.parse(FULL + "[fleet]\n" + key + " = " + value + "\n")
                      .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                      .onSuccess(config -> Assertions.fail("expected a failure for " + key
                                                          + " = " + value
                                                          + ", got " + config))
                      .onFailure(cause -> assertThat(cause.message()).contains("Invalid [fleet] " + key
                                                                              + ": must be a whole number from 1 to 10080"));
        }
    }

    /// m4-design §8, §9.1, §10: the cloudbb entry's `sharedCpu` and `retireAt`.
    @Test
    void parse_readsSharedCpuAndRetireAt_whenSet() {
        var host = parse(FULL.replace("portIsolated = false\n",
                                      "portIsolated = false\nsharedCpu = true\nretireAt = \"2026-11-13T00:00:00Z\"\n")).hosts()
                        .getFirst();

        assertThat(host.sharedCpu()).isTrue();
        assertThat(host.retireAt().map(Instant::toString).or("")).isEqualTo("2026-11-13T00:00:00Z");
    }

    /// A `sharedCpu` that is not a boolean, or a `retireAt` that is not an ISO-8601 instant string, is refused, never
    /// read as false or absent: either would put the host back in service for what it must not run.
    @ParameterizedTest
    @ValueSource(strings = {"sharedCpu = \"true\"", "sharedCpu = 1", "retireAt = \"2026-11-13\"", "retireAt = \"soon\"", "retireAt = 1"})
    void parse_refusesABadSharedCpuOrRetireAt(String line) {
        TomlParser.parse(FULL.replace("portIsolated = false\n", "portIsolated = false\n" + line + "\n"))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains(line.substring(0,
                                                                                          line.indexOf(' '))));
    }

    /// The start log prints only the cause's message, so an invalid value's message names the reason, not just the key.
    @Test
    void parse_invalidValue_messageNamesTheReason() {
        TomlParser.parse(FULL.replace("portIsolated = false\n", "portIsolated = false\nsharedCpu = \"true\"\n"))
                  .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                  .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                  .onFailure(cause -> assertThat(cause.message()).contains("Invalid [hosts] sharedCpu: must be true or false"));
    }

    /// A key a `[[hosts]]` entry is not read for (here a misspelt `memFloorGiB`) is warned about, naming the host and
    /// the key, and the config still loads with the default; a host with only known keys warns nothing.
    @Test
    void parse_unknownHostKey_warnsAndStillLoads() {
        var toml = FULL.replace("portIsolated = false\n", "portIsolated = false\nmemFloorGib = 6\n");

        assertThat(logged(() -> assertThat(parse(toml).hosts().getFirst().memFloorGiB()).isZero())).containsExactly("WARN [hosts] bigboy: unknown key 'memFloorGib' ignored (misspelt or misplaced?)");
        assertThat(logged(() -> parse(FULL))).isEmpty();
    }

    private static List<String> logged(Runnable action) {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(ConfigLoader.class);

        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }

        return appender.list.stream()
                            .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                            .toList();
    }

    /// `retiredSince`: at and after the instant, never before (m4-design §10: "no new grants at or after `retireAt`").
    @Test
    void host_retiredSince_isAtOrAfterTheInstant() {
        var at = Instant.parse("2026-11-13T00:00:00Z");
        var host = parse(FULL.replace("portIsolated = false\n",
                                      "portIsolated = false\nretireAt = \"2026-11-13T00:00:00Z\"\n")).hosts()
                        .getFirst();

        assertThat(host.retiredSince(at.minusMillis(1)).isPresent()).isFalse();
        assertThat(host.retiredSince(at).or(Instant.EPOCH)).isEqualTo(at);
        assertThat(host.retiredSince(at.plusSeconds(1)).or(Instant.EPOCH)).isEqualTo(at);
        assertThat(parse(FULL).hosts().getFirst().retiredSince(at).isPresent()).isFalse();
    }

    /// maintainer ruling on the rerun bound: the install allowance defaults to 5 min, is configurable, and is positive.
    @Test
    void parse_rerunInstallSeconds_defaultsTo300_isConfigurable_andPositive() {
        assertThat(parse(FULL).units().rerunInstallAllowance()).isEqualTo(Duration.ofMinutes(5));
        assertThat(parse(FULL.replace("diskFloorGiB = 50\n", "diskFloorGiB = 50\nrerunInstallSeconds = 90\n")).units()
                        .rerunInstallAllowance()).isEqualTo(Duration.ofSeconds(90));
        for (var bad : List.of("0", "-1")) {
            TomlParser.parse(FULL.replace("diskFloorGiB = 50\n",
                                          "diskFloorGiB = 50\nrerunInstallSeconds = " + bad + "\n"))
                      .flatMap(doc -> ConfigLoader.parse(doc, HOME, BUILD))
                      .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                      .onFailure(cause -> assertThat(cause.message()).contains("rerunInstallSeconds"));
        }
    }
}
