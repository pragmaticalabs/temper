package org.pragmatica.kiln.service.github;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.assertj.core.api.Assertions.assertThat;


/// [GitLsRemote] over a stub runner: the command it runs, a zero exit parsed, a non-zero exit a failure.
class GitLsRemoteTest {
    private static final class Stub implements ProcessRunner {
        private final int rc;
        private final List<Command> ran = new ArrayList<>();

        Stub(int rc) {
            this.rc = rc;
        }

        @Override
        public Promise<Completed> run(Command command) {
            ran.add(command);
            return Promise.success(Completed.completed("git", rc, ("a".repeat(40) + "\trefs/heads/release-1\n").getBytes(StandardCharsets.UTF_8), new byte[0], false));
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void heads_runsLsRemoteWithoutPrompting_andParsesAZeroExit() {
        var stub = new Stub(0);
        var heads = GitLsRemote.gitLsRemote(stub, TimeSpan.timeSpan(5).seconds()).heads("o/r").await().unwrap();

        assertThat(heads).extracting(BranchName::value).containsExactly("release-1");
        assertThat(stub.ran).singleElement()
                            .satisfies(command -> {
                                assertThat(command.argv()).containsExactly("git", "ls-remote", "--heads", "https://github.com/o/r.git");
                                assertThat(command.env()).containsEntry("GIT_TERMINAL_PROMPT", "0");
                            });
    }

    @Test
    void heads_failsOnANonZeroExit() {
        assertThat(GitLsRemote.gitLsRemote(new Stub(128), TimeSpan.timeSpan(5).seconds()).heads("o/r").await().isFailure()).isTrue();
    }
}
