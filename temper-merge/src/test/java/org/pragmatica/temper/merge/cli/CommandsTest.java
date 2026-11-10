package org.pragmatica.temper.merge.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.merge.engine.FakeGitHub;

import static org.assertj.core.api.Assertions.assertThat;


class CommandsTest {
    @TempDir
    Path home;

    private final FakeGitHub.Clock clock = new FakeGitHub.Clock();

    private Commands commands() {
        return Commands.commands(RunnerConfig.load(Map.of("TEMPER_MERGE_HOME", home.toString()))
                                             .unwrap(),
                                 clock,
                                 new Random(1));
    }

    @Test
    void submit_recordsTheJob_appendsOneLine_andPrintsTheId() throws Exception {
        var commands = commands();
        var submitted = commands.submit(List.of("2849", FakeGitHub.SHA, "--ready"));
        assertThat(submitted.code()).isEqualTo(Commands.OK);
        var id = submitted.lines()
                          .getFirst();
        assertThat(id).matches("mr-\\d+-2849-[0-9a-f]{4}");
        assertThat(Files.readAllLines(home.resolve("events.log"))).containsExactly(
            "2026-11-08T12:00:00Z\tjob=" + id + "\tpr=2849\tsha=" + FakeGitHub.SHA + "\tstate=SUBMITTED\treason=submitted --ready");
        var status = commands.status(List.of(id));
        assertThat(status.code()).isEqualTo(Commands.OK);
        assertThat(status.lines()).contains("state=SUBMITTED", "repo=pragmaticalabs/pragmatica", "sha=" + FakeGitHub.SHA);
    }

    @Test
    void submit_refusesAShortShaABadPrAndExtraArguments() {
        var commands = commands();
        assertThat(commands.submit(List.of("2849", "584bc4e"))
                           .code()).isEqualTo(Commands.USAGE);
        assertThat(commands.submit(List.of("0", FakeGitHub.SHA))
                           .code()).isEqualTo(Commands.USAGE);
        assertThat(commands.submit(List.of("2849", FakeGitHub.SHA.toUpperCase()))
                           .code()).isEqualTo(Commands.USAGE);
        assertThat(commands.submit(List.of("2849", FakeGitHub.SHA, "--force"))
                           .code()).isEqualTo(Commands.USAGE);
        assertThat(Files.exists(home.resolve("events.log"))).isFalse();
    }

    @Test
    void status_ofAnUnknownJob_isNotFound() {
        assertThat(commands().status(List.of("mr-1-1-0000"))
                             .code()).isEqualTo(Commands.NOT_FOUND);
        assertThat(commands().status(List.of())
                             .code()).isEqualTo(Commands.USAGE);
    }

    @Test
    void aStoreThatCannotBeWrittenOrAnIdThatIsNotOne_isAFailure_notASuccess() throws Exception {
        assertThat(commands().status(List.of("../x"))
                             .lines()).containsExactly("temper-merge: not a job id: ../x");
        Files.writeString(home.resolve("jobs"), "a file where the jobs directory belongs");
        var submitted = commands().submit(List.of("2849", FakeGitHub.SHA));
        assertThat(submitted.code()).isEqualTo(Commands.FAILED);
        assertThat(submitted.lines()
                            .getFirst()).startsWith("temper-merge: cannot create job mr-");
    }
}
