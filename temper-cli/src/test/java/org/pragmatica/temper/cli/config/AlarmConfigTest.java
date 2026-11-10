package org.pragmatica.temper.cli.config;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.cli.TestSupport;
import org.pragmatica.temper.cli.alarm.AlarmCode;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class AlarmConfigTest {
    private static final String TOKEN = "ghp_alarmTokenValue";

    @TempDir
    Path home;

    private final TestSupport.Out out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});
    private Path dir;

    @BeforeEach
    void dir() throws IOException {
        dir = Files.createDirectories(home.resolve(".config/temper"));
    }

    private CliContext context() {
        var time = TestSupport.time();

        return CliContext.cliContext(_ -> Option.none(), home, time.clock(), time.pacer(), out.console());
    }

    private void config(String text) throws IOException {
        Files.writeString(dir.resolve("config"), text);
    }

    private AlarmConfig loaded() {
        return AlarmConfig.alarmConfig(context())
                          .fold(cause -> fail(cause.message()), config -> config);
    }

    private Cause refusal() {
        return AlarmConfig.alarmConfig(context())
                          .fold(cause -> cause, config -> fail("loaded " + config));
    }

    @Test
    void defaults_githubIssueSink_withTheDocumentedValues() throws IOException {
        config("url = \"http://bigboy:7070\"\n[alarm]\nrepo = \"pragmaticalabs/temper\"\nhost = \"bigboy\"\n");

        var config = loaded();

        assertThat(config.sink()).isEqualTo(new AlarmConfig.Sink.GithubIssue("pragmaticalabs/temper",
                                                                             home.resolve(".config/temper/alarm-token"),
                                                                             URI.create("https://api.github.com"),
                                                                             Duration.ofHours(6)));
        assertThat(config.host()).isEqualTo("bigboy");
        assertThat(config.paths()).containsExactly(home.resolve(".temper"), home.resolve(".local/share/temper/evidence"));
        assertThat(config.minFreeBytes()).isEqualTo(50 * AlarmConfig.GIB);
        assertThat(config.staleAfter()).isEqualTo(Duration.ofHours(26));
    }

    @Test
    void everyKey_isRead() throws IOException {
        config("""
               [alarm]
               sink = "github-issue"
               repo = "acme/ci"
               token-file = "~/secrets/gh"
               github-api = "http://127.0.0.1:9999/"
               repeat = "30m"
               host = "ci-1"
               paths = ["/data", "~/work"]
               min-free-gib = 7
               stale-after = "2h"
               """);

        var config = loaded();

        assertThat(config.sink()).isEqualTo(new AlarmConfig.Sink.GithubIssue("acme/ci",
                                                                             home.resolve("secrets/gh"),
                                                                             URI.create("http://127.0.0.1:9999"),
                                                                             Duration.ofMinutes(30)));
        assertThat(config.host()).isEqualTo("ci-1");
        assertThat(config.paths()).containsExactly(Path.of("/data"), home.resolve("work"));
        assertThat(config.minFreeBytes()).isEqualTo(7 * AlarmConfig.GIB);
        assertThat(config.staleAfter()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void stdoutSink_needsNoRepo() throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"bigboy\"\n");

        assertThat(loaded().sink()).isEqualTo(new AlarmConfig.Sink.Stdout());
    }

    @Test
    void githubIssueSink_withoutRepo_isUsage() throws IOException {
        config("[alarm]\nhost = \"bigboy\"\n");

        assertThat(CliError.exitCodeOf(refusal())).isEqualTo(ExitCode.USAGE);
        assertThat(refusal().message()).contains("alarm.repo is not set");
    }

    @Test
    void misspelledKey_unknownSink_badValues_areUsage() throws IOException {
        for (var text : List.of("[alarm]\nsink = \"stdout\"\nhost = \"b\"\nmin_free_gib = 5\n",
                                "[alarm]\nsink = \"file\"\nhost = \"b\"\n",
                                "[alarm]\nsink = \"stdout\"\nhost = \"b\"\nstale-after = \"soon\"\n",
                                "[alarm]\nsink = \"stdout\"\nhost = \"b\"\nmin-free-gib = 0\n",
                                "[alarm]\nsink = \"stdout\"\nhost = \"b\"\npaths = []\n",
                                "[alarm]\nsink = \"stdout\"\nhost = \"b\"\npaths = \"/data\"\n",
                                "[alarm]\nrepo = \"not a repo\"\nhost = \"b\"\n",
                                "[alarm]\nrepo = \"a/b\"\nhost = \"b\"\ngithub-api = \"ftp://x\"\n",
                                "[alarm\nsink = 1\n")) {
            config(text);

            assertThat(CliError.exitCodeOf(refusal())).as(text).isEqualTo(ExitCode.USAGE);
        }
    }

    /// A host named like a marker kind would claim other hosts' issues by their `opened` line: refused (exit 64).
    @ParameterizedTest
    @ValueSource(strings = {"opened", "firing", "cleared"})
    void host_namedLikeAMarkerKind_isUsage(String host) throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"" + host + "\"\n");

        assertThat(CliError.exitCodeOf(refusal())).isEqualTo(ExitCode.USAGE);
        assertThat(Console.oneLine(refusal())).isEqualTo("alarm.host may not be a marker kind (opened, firing, cleared): "
                                                         + host);
    }

    /// The host ends a marker line: anything outside a host name's charset (a space, a newline, `>`, a leading dot,
    /// over 63 characters) is refused (exit 64).
    @ParameterizedTest
    @ValueSource(strings = {"big boy", "big\\nboy", "big-->boy", ".bigboy", "", "a123456789012345678901234567890123456789012345678901234567890123"})
    void host_outsideTheHostNameCharset_isUsage(String host) throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"" + host + "\"\n");

        assertThat(CliError.exitCodeOf(refusal())).as(host).isEqualTo(ExitCode.USAGE);
        assertThat(Console.oneLine(refusal())).as(host).startsWith("alarm.host must be a host name");
    }

    /// Host names as they come: dots, digits, dashes, underscores and capitals, up to 63 characters.
    @ParameterizedTest
    @ValueSource(strings = {"bigboy", "cloudbb-1", "Big_Boy.example.org", "a12345678901234567890123456789012345678901234567890123456789012"})
    void host_aHostName_isRead(String host) throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"" + host + "\"\n");

        assertThat(loaded().host()).isEqualTo(host);
    }

    /// Unset, the host is the first DNS label of this machine's name, however long the full name.
    @Test
    void defaultHost_isTheFirstLabelOfTheMachineName() {
        var seventy = "cloudbb-1." + "x".repeat(56) + ".org";

        assertThat(seventy).hasSize(70);
        assertThat(AlarmConfig.defaultHost("bigboy.example.org").or("refused")).isEqualTo("bigboy");
        assertThat(AlarmConfig.defaultHost(seventy).or("refused")).isEqualTo("cloudbb-1");
        assertThat(AlarmConfig.defaultHost("bigboy").or("refused")).isEqualTo("bigboy");
    }

    /// Unset, and this machine's own name does not resolve: exit 64, asking for `alarm.host`, the lookup's failure kept.
    @Test
    void machineHost_whenTheLookupFails_isUsage_setAlarmHost() {
        var cause = AlarmConfig.machineHost(() -> {
                                   throw new UnknownHostException("bigboy: nodename nor servname provided");
                               })
                               .fold(failed -> failed, host -> fail("host " + host));

        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.USAGE);
        assertThat(cause.message()).isEqualTo("Invalid alarm.host: unset, and this machine's name does not resolve: set alarm.host");
        assertThat(Console.oneLine(cause)).contains("nodename nor servname provided");
    }

    /// A lookup that answers goes through the first-label default.
    @Test
    void machineHost_whenTheLookupAnswers_isItsFirstLabel() {
        assertThat(AlarmConfig.machineHost(() -> "bigboy.example.org").or("refused")).isEqualTo("bigboy");
    }

    /// An IP literal is no host name: exit 64, asking for `alarm.host`.
    @ParameterizedTest
    @ValueSource(strings = {"fe80:0:0:0:1%lo0", "::1", "192.0.2.5", "cleared"})
    void defaultHost_fromAnIpLiteral_isUsage_setAlarmHost(String machineName) {
        var cause = AlarmConfig.defaultHost(machineName).fold(failed -> failed, host -> fail("host " + host));

        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.USAGE);
        assertThat(cause.message()).isEqualTo("alarm.host is unset and this machine's name (" + machineName
                                              + ") is no host name: set alarm.host");
    }

    /// The fake clock's day is 2026-10-13 (UTC): a skip may last through 2026-10-20, seven days ahead.
    @Test
    void skip_untilAtMostSevenDaysAhead_isRead() throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"b\"\nskip = [\"dump-failed\", \"dump-stale\"]\nskip-until = \"2026-10-20\"\n");

        assertThat(loaded().skip()).isEqualTo(Option.some(AlarmConfig.Skip.skip(List.of(AlarmCode.DUMP_FAILED, AlarmCode.DUMP_STALE),
                                                                              LocalDate.parse("2026-10-20"))));
    }

    @Test
    void skip_untilEightDaysAhead_isUsage() throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"b\"\nskip = [\"dump-failed\"]\nskip-until = \"2026-10-21\"\n");

        assertThat(CliError.exitCodeOf(refusal())).isEqualTo(ExitCode.USAGE);
        assertThat(refusal().message()).contains("at most 7 days after today (2026-10-13)");
    }

    @Test
    void skip_withoutSkipUntil_isUsage() throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"b\"\nskip = [\"dump-failed\"]\n");

        assertThat(CliError.exitCodeOf(refusal())).isEqualTo(ExitCode.USAGE);
        assertThat(refusal().message()).contains("alarm.skip-until is required");
    }

    /// Only dump-failed and dump-stale may be skipped; every other code is refused, and the message names the set.
    @ParameterizedTest
    @ValueSource(strings = {"controller-down", "disk-low", "worker-lost", "dump-file-stale", "check-blind", "dump-staleness"})
    void skip_ofAnyOtherCode_isUsage(String code) throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"b\"\nskip = [\"dump-stale\", \"" + code + "\"]\nskip-until = \"2026-10-15\"\n");

        assertThat(CliError.exitCodeOf(refusal())).isEqualTo(ExitCode.USAGE);
        assertThat(Console.oneLine(refusal())).isEqualTo("alarm.skip may name only dump-failed and dump-stale, not: " + code
                                                  + " (controller-down, disk-low, worker-lost, dump-file-stale and check-blind"
                                                  + " are never skipped)");
    }

    /// A skip-until already past is accepted: its expiry is reported at every run, not refused.
    @Test
    void skip_untilInThePast_isRead_andExpired() throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"b\"\nskip = [\"dump-stale\"]\nskip-until = \"2026-10-12\"\n");

        assertThat(loaded().skip().map(skip -> skip.expiredOn(LocalDate.parse("2026-10-13")))).isEqualTo(Option.some(true));
        assertThat(loaded().skippedAt(Instant.parse("2026-10-13T10:00:00Z"))).isEmpty();
    }

    @Test
    void dumpDir_defaultsUnderTheEvidenceRoot() throws IOException {
        config("[alarm]\nsink = \"stdout\"\nhost = \"b\"\n");

        assertThat(loaded().dumpDir()).isEqualTo(home.resolve(".local/share/temper/evidence/pgdump"));
        assertThat(loaded().skip()).isEqualTo(Option.none());
    }

    @Test
    void token_isTheFilesTrimmedLine() throws IOException {
        var file = Files.writeString(dir.resolve("alarm-token"), "  " + TOKEN + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

        assertThat(AlarmConfig.token(context(), file).unwrap()).isEqualTo(TOKEN);
        assertThat(out.errors()).isEmpty();
    }

    /// The failures name the file, never its contents.
    @Test
    void token_missingOrMalformed_isUsage_withoutTheContents() throws IOException {
        var file = dir.resolve("alarm-token");
        var missing = AlarmConfig.token(context(), file).fold(cause -> cause, token -> fail("read " + token));

        assertThat(CliError.exitCodeOf(missing)).isEqualTo(ExitCode.USAGE);
        assertThat(missing.message()).contains(file.toString());

        Files.writeString(file, TOKEN + " " + TOKEN + "\n");
        var malformed = AlarmConfig.token(context(), file).fold(cause -> cause, token -> fail("read " + token));

        assertThat(CliError.exitCodeOf(malformed)).isEqualTo(ExitCode.USAGE);
        assertThat(Console.oneLine(malformed)).doesNotContain(TOKEN);
    }

    @Test
    void token_sharedFile_warnsWithoutTheContents() throws IOException {
        var file = Files.writeString(dir.resolve("alarm-token"), TOKEN + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(AlarmConfig.token(context(), file).unwrap()).isEqualTo(TOKEN);
        assertThat(out.errors()).contains("warning:", "chmod 600").doesNotContain(TOKEN);
    }
}
