package org.pragmatica.temper.controller.config;

import java.nio.file.Path;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// `[fleet] bbHosts`, `bbScanKey`, `bbKnownHosts`: the two entry forms, and the start refusals.
class FleetBbConfigTest {
    private static final Path HOME = Path.of("/home/builder");

    private static final String KEYS = """
        bbScanKey = "~/.temper/kiln_scan"
        bbKnownHosts = "~/.temper/known_hosts"
        """;

    private static Result<ControllerConfig> parse(String fleet) {
        var toml = ConfigLoaderTest.FULL.replace("[worker]", "[fleet]\n" + fleet + "\n[worker]");

        return TomlParser.parse(toml).flatMap(doc -> ConfigLoader.parse(doc, HOME, "0.1.0-build"));
    }

    @Test
    void bothForms_parse_withTheKeyPathsExpanded() {
        var fleet = parse("bbHosts = [\"oldbox\", \"buildbox=builder@192.0.2.72\"]\n" + KEYS).unwrap().fleet();

        assertThat(fleet.bbHosts()).extracting(ControllerConfig.BbHost::name).containsExactly("oldbox", "buildbox");
        assertThat(fleet.bbHosts().get(0).link()).isEqualTo(none());
        assertThat(fleet.bbHosts().get(1).link()).isEqualTo(some(ControllerConfig.BbLink.bbLink("builder", "192.0.2.72")));
        assertThat(fleet.bbScanKey()).isEqualTo(some(Path.of("/home/builder/.temper/kiln_scan")));
        assertThat(fleet.bbKnownHosts()).isEqualTo(some(Path.of("/home/builder/.temper/known_hosts")));
    }

    @Test
    void bareEntriesOnly_needNoKeys_andAbsentBbHostsIsEmpty() {
        assertThat(parse("bbHosts = [\"oldbox\"]").unwrap().fleet().bbScanKey()).isEqualTo(none());
        assertThat(parse("kilnUrl = \"http://127.0.0.1:7171\"").unwrap().fleet().bbHosts()).isEmpty();
    }

    @Test
    void anEqualsEntry_withoutTheScanKeyOrKnownHosts_refusesTheStart() {
        assertThat(refusal("bbHosts = [\"b=builder@198.51.100.1\"]\nbbKnownHosts = \"~/k\"")).contains("bbScanKey");
        assertThat(refusal("bbHosts = [\"b=builder@198.51.100.1\"]\nbbScanKey = \"~/k\"")).contains("bbKnownHosts");
    }

    @Test
    void malformedDuplicateAndLinkedNames_refuseTheStart() {
        assertThat(refusal("bbHosts = [\"-bad\"]")).contains("malformed");
        assertThat(refusal("bbHosts = [\"a b\"]")).contains("malformed");
        assertThat(refusal("bbHosts = [\"x=Root@198.51.100.1\"]\n" + KEYS)).contains("malformed");
        assertThat(refusal("bbHosts = [\"x=builder@198.51.100.1/24\"]\n" + KEYS)).contains("malformed");
        assertThat(refusal("bbHosts = [\"x=builder@\"]\n" + KEYS)).contains("malformed");
        assertThat(refusal("bbHosts = [\"x\", \"x=builder@198.51.100.1\"]\n" + KEYS)).contains("duplicate host x");
        assertThat(refusal("bbHosts = [\"bigboy\"]")).contains("bigboy is also a [[hosts]] name");
        assertThat(refusal("bbHosts = \"oldbox\"")).contains("array of strings");
    }

    private static String refusal(String fleet) {
        var result = parse(fleet);

        assertThat(result.isFailure()).as(fleet).isTrue();

        return result.fold(cause -> cause.message(), _ -> "");
    }

    /// A misspelt `[fleet]` key (here `bbHost`, so no bb host is collected) is warned about, naming the key, and the
    /// config still loads; known keys warn nothing. Mutation: no warning → red.
    @Test
    void anUnknownFleetKey_isWarnedAbout_andStillLoads() {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(ConfigLoader.class);

        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(parse("bbHost = [\"oldbox\"]\n").unwrap().fleet().bbHosts()).isEmpty();
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                                     .containsExactly("[fleet] ?: unknown key 'bbHost' ignored (misspelt or misplaced?)");
            appender.list.clear();
            parse("bbHosts = [\"oldbox\"]\n" + KEYS).unwrap();
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    /// Every key `[fleet]` knows loads without a warning. Mutation: drop one from `FLEET_KEYS` (m14: `stateDir`) → red.
    @Test
    void everyKnownFleetKey_loadsWithNoWarning() {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(ConfigLoader.class);

        appender.start();
        logger.addAppender(appender);
        try {
            parse("kilnUrl = \"http://kiln:9\"\nstateDir = \"/var/lib/temper\"\nbbHosts = [\"oldbox\"]\n" + KEYS).unwrap();
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
