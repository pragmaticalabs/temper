package org.pragmatica.temper.merge.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.temper.merge.engine.Policy;

import static org.assertj.core.api.Assertions.assertThat;


/// The hand-rolled chain took an invalid poll argument, exited at once and printed TIMEOUT. Here a bad setting is a
/// start-up refusal with its own message.
class RunnerConfigTest {
    @TempDir
    Path home;

    private String refusal(String text) {
        return RunnerConfig.parsed(home, text)
                           .fold(Cause::message, config -> "accepted " + config);
    }

    @Test
    void noFile_givesTheDefaults_underHome() {
        var config = RunnerConfig.load(Map.of("TEMPER_MERGE_HOME", home.toString()))
                                 .unwrap();
        assertThat(config.repo()).isEqualTo("pragmaticalabs/pragmatica");
        assertThat(config.eventsLog()).isEqualTo(home.resolve("events.log"));
        assertThat(config.jobs()).isEqualTo(home.resolve("jobs"));
        assertThat(config.policy()).isEqualTo(Policy.DEFAULT);
    }

    @Test
    void theDefaultHome_isTemperMergeRunnerUnderHOME() {
        assertThat(RunnerConfig.homeOf(Map.of("HOME", "/home/builder"))).isEqualTo(Path.of("/home/builder/temper/merge-runner"));
    }

    @Test
    void settingsAreRead() throws Exception {
        Files.writeString(home.resolve("merge-runner.properties"),
                          "repo=pragmaticalabs/temper\neventsLog=/var/tmp/x.log\npoll=30\ndeadline.checks=600\n");
        var config = RunnerConfig.load(Map.of("TEMPER_MERGE_HOME", home.toString()))
                                 .unwrap();
        assertThat(config.repo()).isEqualTo("pragmaticalabs/temper");
        assertThat(config.eventsLog()).isEqualTo(Path.of("/var/tmp/x.log"));
        assertThat(config.policy()
                         .poll()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.policy()
                         .checks()).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void anInvalidPoll_isRefusedAtStart_withItsOwnReason() {
        assertThat(refusal("poll=5")).isEqualTo("merge-runner.properties: poll must be at least 10s: 5s");
        assertThat(refusal("poll=-30")).isEqualTo("merge-runner.properties: poll must be whole seconds: -30");
        assertThat(refusal("poll=2m")).isEqualTo("merge-runner.properties: poll must be whole seconds: 2m");
        assertThat(refusal("poll=x\ndeadline.run=y")).isEqualTo(
            "merge-runner.properties: poll must be whole seconds: x; deadline.run must be whole seconds: y");
    }

    @Test
    void theShortestPoll_isAccepted_andAMalformedFileIsRefused() {
        assertThat(refusal("poll=10")).startsWith("accepted ");
        assertThat(refusal("poll=\\uZZZZ")).startsWith("merge-runner.properties is not a properties file: ");
    }

    @Test
    void anUnreadableSettingsFile_isRefused() throws Exception {
        Files.createDirectories(home.resolve("merge-runner.properties"));
        assertThat(RunnerConfig.load(Map.of("TEMPER_MERGE_HOME", home.toString()))
                               .fold(Cause::message, _ -> "loaded")).startsWith("cannot read " + home.resolve("merge-runner.properties"));
    }

    @Test
    void aDeadlineNotAboveThePoll_isRefused() {
        assertThat(refusal("poll=60\ndeadline.queue=60")).isEqualTo(
            "merge-runner.properties: every deadline must be above the poll (60s): 60s");
    }

    @Test
    void anUnknownKeyOrABadRepo_isRefused() {
        assertThat(refusal("pol=60")).isEqualTo("merge-runner.properties: unknown key(s) pol");
        assertThat(refusal("repo=pragmatica")).isEqualTo("merge-runner.properties: repo must be owner/name: pragmatica");
    }
}
