package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.FakeController.Reply;

/// `jobs (--sha S | --ref R) [--json]` (WP-C, R-M2-12) against the fake controller: every job, newest first, read page by
/// page (`before`) until a short page, so a truncated `subscribe` reply always has a command that lists the rest.
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JobsAgainstControllerTest {
    @TempDir
    Path home;

    private FakeController controller;
    private CliHarness cli;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN);
        cli = CliHarness.against(home, controller);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private static JobView[] descending(long from, long to) {
        return LongStream.iterate(from, id -> id >= to, id -> id - 1).mapToObj(id -> Fixtures.job(id, JobState.PASSED)).toArray(JobView[]::new);
    }

    private List<String> targets() {
        return controller.received().stream().map(FakeController.Received::target).toList();
    }

    @Test
    void jobs_pagesUntilAShortPage_andPrintsEveryJobNewestFirst() {
        controller.on("GET /jobs",
                      Reply.json(200, controller.write(descending(1000, 801))),
                      Reply.json(200, controller.write(descending(800, 798))));

        var result = cli.run("jobs", "--ref", "main");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).hasSize(203);
        assertThat(result.out().getFirst()).isEqualTo("job 1000 state=PASSED recipe=module-test sha=0cf5631");
        assertThat(result.out().getLast()).isEqualTo("job 798 state=PASSED recipe=module-test sha=0cf5631");
        assertThat(targets()).containsExactly("/api/v1/jobs?limit=200&ref=main", "/api/v1/jobs?before=801&limit=200&ref=main");
    }

    @Test
    void jobs_json_printsTheViewsAsOneArray() {
        var views = descending(42, 41);
        controller.onJson("GET /jobs", (Object) views);

        var result = cli.run("jobs", "--sha", Fixtures.SHA, "--json");

        assertThat(result.exit()).isZero();
        assertThat(TemperJson.mapper().readString(String.join("\n", result.out()), JobView[].class).unwrap()).containsExactly(views);
    }

    @Test
    void jobs_none_printsNothing() {
        controller.on("GET /jobs", Reply.json(200, "[]"));

        var result = cli.run("jobs", "--sha", Fixtures.SHA);

        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEmpty();
    }

    @Test
    void jobs_needsExactlyOneFilter_andABadOneIs64() {
        assertThat(cli.run("jobs").exit()).isEqualTo(64);
        assertThat(cli.run("jobs", "--sha", Fixtures.SHA, "--ref", "main").exit()).isEqualTo(64);
        assertThat(controller.received()).isEmpty();

        controller.on("GET /jobs", Reply.json(400, "{\"error\":\"Invalid sha: '0cf5631'\"}"));
        assertThat(cli.run("jobs", "--sha", "0cf5631").exit()).isEqualTo(64);
    }
}
