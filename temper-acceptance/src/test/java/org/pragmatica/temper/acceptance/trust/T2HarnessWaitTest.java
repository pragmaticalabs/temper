package org.pragmatica.temper.acceptance.trust;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// T2 judges only a job that ended. `temper wait` at `--timeout` exits 7 printing the last non-terminal state; T2 used
/// to judge that unfinished job (T1's head 11 / job 39: HTTP 404 from `/result`). A timed-out wait is a FAIL report
/// naming the bound and the state, exit 1, and the controller is never asked for the job's record; a terminal wait is
/// judged as before.
class T2HarnessWaitTest {
    private static final String SHA = "c0ffee1234";
    private static final long JOB = 39;
    private static final long PARENT = 7;
    private static final String PROBE = "fx.includes.CrashedForkProbeTest";
    private static final String CRASH = "[ERROR] The forked VM terminated without properly saying goodbye. VM crash or System.exit called?";

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer controller;

    @BeforeEach
    void startController() throws IOException {
        controller = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        controller.createContext("/", this::answer);
        controller.start();
    }

    @AfterEach
    void stopController() {
        controller.stop(0);
    }

    @Test
    void timedOutWait_isAFailReportWithTheState_exitsNonZero_andIsNeverJudged(@TempDir Path out) throws IOException {
        var cli = new Cli(7, "wait 39 RUNNING\n");

        var written = T2Harness.judged(settings(cli, out)).await().expect("a written report");

        assertThat(written.overall()).isEqualTo(T2Harness.FAIL);
        assertThat(T1Harness.exitFor(written.overall())).as("never a pass").isEqualTo(T1Harness.EXIT_FAIL);
        assertThat(requests).as("an unfinished job is never judged, nor its control read").isEmpty();
        assertThat(cli.argvs).containsExactly(List.of("temper", "submit", "gate", "--sha", SHA, "--fresh", "--prio", "1"),
                                              List.of("temper", "wait", "39", "--timeout", "21600s"));
        assertThat(Files.readString(written.report())).contains("- crashed SHA " + SHA + ", job 39, probe " + PROBE)
                                                      .contains("- **stopped:** wait timed out after 6h at state RUNNING")
                                                      .contains("**T2: FAIL**")
                                                      .doesNotContain("- verdict ")
                                                      .doesNotContain("- control:");
    }

    @Test
    void terminalWait_isJudgedAsBefore(@TempDir Path out) throws IOException {
        var cli = new Cli(1, "wait 39 FAILED testcases=0 failed=0 observed-failures=0 evidence=not-stored\n");

        var written = T2Harness.judged(settings(cli, out)).await().expect("a written report");

        assertThat(written.overall()).isEqualTo("PASS");
        assertThat(T1Harness.exitFor(written.overall())).isEqualTo(T1Harness.EXIT_PASS);
        assertThat(requests).contains("/api/v1/jobs/39/result", "/api/v1/jobs/39/testcases", "/api/v1/jobs/39/manifest",
                                      "/api/v1/jobs/7/result");
        assertThat(Files.readString(written.report())).contains("- verdict FAILED missing-classes")
                                                      .contains("- control: parent job 7 is PASSED")
                                                      .contains("**T2: PASS**")
                                                      .doesNotContain("stopped:");
    }

    private T2Harness.Settings settings(Cli cli, Path out) {
        var processes = Processes.processes(cli);
        var api = ControllerApi.controllerApi(URI.create("http://127.0.0.1:" + controller.getAddress().getPort()), "t");

        return new T2Harness.Settings(api, TemperCli.temperCli(processes, List.of("temper"), Map.of()),
                                      Judge.judge(processes, api, Path.of("bb-report")), SHA, PARENT, PROBE, 1,
                                      T2Harness.DEFAULT_WAIT_HOURS, T2Harness.base(out, "2026-10-16"));
    }

    /// The controller's read routes for the crashed job 39 (FAILED missing-classes, the probe missing, rc 1, the
    /// crash line in its log) and the parent job 7 (PASSED); every path asked is recorded.
    private void answer(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        requests.add(path);
        var body = switch (path) {
            case "/api/v1/jobs/39/result" -> json(result(JOB, "FAILED", some("missing-classes"), 1));
            case "/api/v1/jobs/39/testcases" -> "[]";
            case "/api/v1/jobs/39/manifest" -> json(new ManifestView(true, false, List.of(), List.of(), List.of(PROBE), List.of(), List.of()));
            case "/api/v1/jobs/39/log" -> CRASH + "\n";
            case "/api/v1/jobs/7/result" -> json(result(PARENT, "PASSED", none(), 0));
            default -> null;
        };
        var bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    private static String json(Object value) {
        return TemperJson.mapper().writeAsString(value).expect("encodable");
    }

    private static ResultRecordDto result(long job, String verdict, Option<String> reason, int rc) {
        return new ResultRecordDto(job, verdict, reason, none(), new SourceView(none(), some("b".repeat(40))), new RecipeDto.Gate(), List.of(),
                                   new ResultRecordDto.CountsDto(1, 1, 0, 0, 0, 1, 1, some(1), some(1), some(0)), List.of(), 0,
                                   List.of(ResultRecordDto.UnitResultDto.unitResultDto("gate", some(rc), some("bb"), none(), some(Instant.now()),
                                                                             some(Instant.now()), none(), none())),
                                   none(), ResultRecordDto.EvidenceDto.NOT_STORED, new ResultRecordDto.TimingsDto(none(), none()), none(), List.of());
    }

    /// The CLI: `submit` exits 0 naming job 39; `wait` exits `waitRc` printing `waitLine`. Every argv is recorded.
    private static final class Cli implements ProcessRunner {
        final List<List<String>> argvs = new CopyOnWriteArrayList<>();
        private final int waitRc;
        private final String waitLine;

        Cli(int waitRc, String waitLine) {
            this.waitRc = waitRc;
            this.waitLine = waitLine;
        }

        @Override
        public Promise<Completed> run(Command command) {
            argvs.add(command.argv());
            return "submit".equals(command.argv().get(1))
                   ? completed(0, "submit 39 gate " + SHA + "\n")
                   : completed(waitRc, waitLine);
        }

        private static Promise<Completed> completed(int rc, String stdout) {
            return Promise.success(Completed.completed("temper", rc, stdout.getBytes(StandardCharsets.UTF_8), new byte[0], false));
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            return Causes.cause("not used").result();
        }
    }
}
