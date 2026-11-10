package org.pragmatica.kiln.core.github;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.AppId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.causes;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.kiln.core.Fixtures.resource;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §4.4: the check-runs and statuses pages. `github/check-runs-pr2504.json` is the live answer at #2504's head
/// (captured 2026-10-30): 16 runs, 15 of app 15368 and one of CodeRabbit (347564).
class CheckDecodeTest {
    @Test
    void checkRuns_readsTheLivePage() {
        var runs = ok(CheckRun.decode(resource("github/check-runs-pr2504.json")));

        assertThat(runs).hasSize(16);
        assertThat(runs.getFirst()).isEqualTo(new CheckRun(123801992322L,
                                                           "consensus-shard-2",
                                                           some(ok(AppId.appId(15368))),
                                                           "completed",
                                                           some("success"),
                                                           some(Instant.parse("2026-10-29T22:57:55Z")),
                                                           some(111060667982L)));
        assertThat(runs).filteredOn(run -> run.name()
                                              .equals("CodeRabbit"))
                        .extracting(CheckRun::app)
                        .containsExactly(some(ok(AppId.appId(347564))));
    }

    /// GitHub's schema allows a run with no app and no start; a queued run has no conclusion.
    @Test
    void checkRuns_readsNullableFieldsAsAbsent() {
        var runs = ok(CheckRun.decode("{\"total_count\": 1, \"check_runs\": [{\"id\": 5, \"name\": \"b\", \"app\": null,"
                                      + " \"status\": \"queued\", \"conclusion\": null, \"started_at\": null, \"x\": 1}]}"));

        assertThat(runs).containsExactly(new CheckRun(5, "b", none(), "queued", none(), none()));
    }

    @Test
    void checkRuns_failsOnAMalformedPage() {
        assertThat(causes(CheckRun.decode("{\"check_runs\": [{\"id\": 5, \"status\": \"queued\"}]}"))).contains(GitHubError.BadField.FACTORY.apply("name"));
        assertThat(causes(CheckRun.decode("{\"check_runs\": [{\"id\": 5, \"name\": \"b\", \"app\": {\"id\": \"x\"},"
                                           + " \"status\": \"queued\"}]}"))).contains(GitHubError.BadField.FACTORY.apply("app.id"));
        assertThat(causes(CheckRun.decode("{\"check_runs\": [{\"id\": 1.5, \"name\": \"b\", \"status\": \"queued\"}]}"))).contains(GitHubError.BadField.FACTORY.apply("id"));
        assertThat(causes(CheckRun.decode("{\"total_count\": 0}"))).contains(GitHubError.BadField.FACTORY.apply("check_runs"));
        assertThat(causes(CheckRun.decode("[]"))).contains(GitHubError.BadField.FACTORY.apply("check_runs"));
    }

    /// GitHub's documented shape of `statuses` (no required context of this repo posts one today: the live answer is
    /// `[]`).
    @Test
    void statuses_readsTheDocumentedShape() {
        var json = """
                   [{"url": "https://api.github.com/repos/o/r/statuses/x", "avatar_url": null, "id": 1, "node_id": "n",
                     "state": "success", "description": "Build skipped", "target_url": null, "context": "ci",
                     "created_at": "2026-10-30T01:00:00Z", "updated_at": "2026-10-30T01:05:00Z",
                     "creator": {"login": "bot", "id": 9}},
                    {"id": 2, "state": "pending", "description": null, "context": "ci", "updated_at": "2026-10-30T01:00:00Z"}]
                   """;

        assertThat(ok(CommitStatus.decode(json))).containsExactly(new CommitStatus(1,
                                                                                    "ci",
                                                                                    "success",
                                                                                    some("Build skipped"),
                                                                                    Instant.parse("2026-10-30T01:05:00Z")),
                                                                   new CommitStatus(2,
                                                                                    "ci",
                                                                                    "pending",
                                                                                    none(),
                                                                                    Instant.parse("2026-10-30T01:00:00Z")));
        assertThat(ok(CommitStatus.decode("[\n\n]"))).isEmpty();
    }

    @Test
    void statuses_failsOnAMalformedPage() {
        assertThat(causes(CommitStatus.decode("[{\"id\": 1, \"state\": \"success\", \"context\": \"ci\"}]"))).contains(GitHubError.BadField.FACTORY.apply("updated_at"));
        assertThat(causes(CommitStatus.decode("[{\"id\": 1, \"state\": \"success\", \"context\": \"ci\","
                                               + " \"description\": 3, \"updated_at\": \"2026-10-30T01:00:00Z\"}]"))).contains(GitHubError.BadField.FACTORY.apply("description"));
        assertThat(causes(CommitStatus.decode("{\"state\": \"success\"}"))).contains(GitHubError.BadField.FACTORY.apply("$"));
    }
}
