package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.protocol.api.RefusalAddRequest;
import org.pragmatica.temper.protocol.api.RefusalDto;
import org.pragmatica.temper.protocol.api.RefusalMatcherDto;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.cli.FakeController.Reply;

/// `refusal list|add|expire` (m1-design §6) as whole CLI invocations against the fake controller. Bodies are the
/// protocol DTOs written and read by the CLI's own mapper (`TemperJson`), so the wire shapes are the controller's.
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RefusalAgainstControllerTest {
    private static final RefusalMatcherDto EXCLUDE = new RefusalMatcherDto.ExcludeTestClass("FlakyIT",
                                                                                            "#9",
                                                                                            some("failsafe"));
    private static final RefusalMatcherDto IT_TEST = new RefusalMatcherDto.ItTestRequired("forge-tests");
    private static final Instant EXPIRED_AT = Instant.parse("2026-10-13T12:00:00Z");
    private static final String ACTIVE_LINE = "refusal R-9 ACTIVE matcher=excludeTestClass simpleClassName=FlakyIT disabledMarker=#9 runner=failsafe issue=9 until=\"#9 closed\" reason=\"flaky until #9\"";
    private static final String EXPIRED_LINE = "refusal R-OLD EXPIRED matcher=itTestRequired moduleSegment=forge-tests issue=9 until=\"#9 closed\" reason=\"flaky until #9\" expired-at=2026-10-13T12:00:00Z";

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

    @Test
    void list_printsOneLinePerRefusal_activeOnlyByDefault() {
        controller.onJson("GET /refusals", (Object) new RefusalDto[]{Fixtures.refusal("R-9", EXCLUDE, none())});

        var result = cli.run("refusal", "list");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.out()).containsExactly(ACTIVE_LINE);
        assertThat(target()).isEqualTo("/api/v1/refusals?all=false");
    }

    @Test
    void list_all_asksForExpiredToo_andMarksThem() {
        controller.onJson("GET /refusals",
                          (Object) new RefusalDto[]{Fixtures.refusal("R-9", EXCLUDE, none()), Fixtures.refusal("R-OLD",
                                                                                                                IT_TEST,
                                                                                                                some(EXPIRED_AT))});

        var result = cli.run("refusal", "list", "--all");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly(ACTIVE_LINE, EXPIRED_LINE);
        assertThat(target()).isEqualTo("/api/v1/refusals?all=true");
    }

    @Test
    void list_ofNoRefusals_printsNothing() {
        controller.on("GET /refusals", Reply.json(200, "[]"));

        var result = cli.run("refusal", "list");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEmpty();
    }

    @Test
    void list_unreachableController_exits69() throws IOException {
        var result = cli.env("TEMPER_URL", "http://127.0.0.1:" + ExitCodeContractTest.closedPort())
                        .run("refusal", "list");

        assertThat(result.exit()).isEqualTo(69);
    }

    @Test
    void add_postsTheRequestWithTheMatcherFromTheFile_andPrintsTheStoredRefusal() throws IOException {
        controller.on("POST /refusals", Reply.json(201, controller.write(Fixtures.refusal("R-9", EXCLUDE, none()))));

        var result = cli.run("refusal",
                             "add",
                             "R-9",
                             "--matcher",
                             matcherFile(controller.write(EXCLUDE)).toString(),
                             "--reason",
                             "flaky until #9",
                             "--issue",
                             "9",
                             "--until",
                             "#9 closed");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.out()).containsExactly(ACTIVE_LINE);
        assertThat(sentRequest()).isEqualTo(RefusalAddRequest.refusalAddRequest("R-9",
                                                                                EXCLUDE,
                                                                                some(9),
                                                                                "flaky until #9",
                                                                                some("#9 closed")));
    }

    /// The seed format of `RefusalMatcherDto`'s documentation, as a person writes the file.
    @Test
    void add_readsAHandWrittenMatcher_withoutOptionalFields() throws IOException {
        controller.on("POST /refusals", Reply.json(201, controller.write(Fixtures.refusal("R-IT", IT_TEST, none()))));

        var result = cli.run("refusal",
                             "add",
                             "R-IT",
                             "--matcher",
                             matcherFile("{\"itTestRequired\":{\"moduleSegment\":\"forge-tests\"}}").toString(),
                             "--reason",
                             "why");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(sentRequest()).isEqualTo(RefusalAddRequest.refusalAddRequest("R-IT", IT_TEST, none(), "why", none()));
    }

    @Test
    void add_ofAnExistingCode_exitsTwo_withTheControllersReasonOnStderr() throws IOException {
        controller.on("POST /refusals", Reply.json(409, "{\"error\":\"Refusal R-9 exists\"}"));

        var result = cli.run("refusal", "add", "R-9", "--matcher", matcherFile(controller.write(EXCLUDE)).toString(), "--reason", "why");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err().lines()).containsExactly("temper: Controller refused the request: Refusal R-9 exists");
    }

    @Test
    void add_rejectedByTheController_exits64() throws IOException {
        controller.on("POST /refusals", Reply.json(400, "{\"error\":\"Invalid code: R-M2\"}"));

        var result = cli.run("refusal", "add", "R-M2", "--matcher", matcherFile(controller.write(EXCLUDE)).toString(), "--reason", "why");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("Invalid code: R-M2");
    }

    @Test
    void add_withAnUnknownMatcherKind_exits64_withoutContactingTheController() throws IOException {
        var result = cli.run("refusal", "add", "R-9", "--matcher", matcherFile("{\"shellScript\":{\"run\":\"x\"}}").toString(), "--reason", "why");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("Invalid --matcher");
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void add_withAMissingMatcherFile_exits64_withoutContactingTheController() {
        var result = cli.run("refusal", "add", "R-9", "--matcher", home.resolve("absent.json").toString(), "--reason", "why");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("Cannot read");
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void add_unreachableController_exits69() throws IOException {
        var matcher = matcherFile(controller.write(EXCLUDE)).toString();
        var result = cli.env("TEMPER_URL", "http://127.0.0.1:" + ExitCodeContractTest.closedPort())
                        .run("refusal", "add", "R-9", "--matcher", matcher, "--reason", "why");

        assertThat(result.exit()).isEqualTo(69);
    }

    @Test
    void expire_postsToTheCodesRoute_andPrintsTheExpiredRefusal() {
        controller.onJson("POST /refusals/R-OLD/expire", Fixtures.refusal("R-OLD", IT_TEST, some(EXPIRED_AT)));

        var result = cli.run("refusal", "expire", "R-OLD");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.out()).containsExactly(EXPIRED_LINE);
        assertThat(controller.received()
                             .getLast()
                             .method()).isEqualTo("POST");
    }

    @Test
    void add_rejectedForSeveralReasons_printsEveryReasonOnOneLine() throws IOException {
        controller.on("POST /refusals",
                      Reply.json(400, "{\"error\":\"Composite:\\n  Blank reason\\n  Invalid issue: 0\"}"));

        var result = cli.run("refusal", "add", "R-9", "--matcher", matcherFile(controller.write(EXCLUDE)).toString(), "--reason", " ", "--issue", "0");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err().lines()).containsExactly("temper: Controller rejected the request: Composite:; Blank reason; Invalid issue: 0");
    }

    @Test
    void bareGroup_namesItsOperations_exits64() {
        var result = cli.run("refusal");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("'refusal' needs an operation: add|expire|list");
    }

    @Test
    void expire_ofAnUnknownCode_exits64() {
        var result = cli.run("refusal", "expire", "R-NONE");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("Not found: /refusals/R-NONE/expire");
    }

    @Test
    void expire_unreachableController_exits69() throws IOException {
        var result = cli.env("TEMPER_URL", "http://127.0.0.1:" + ExitCodeContractTest.closedPort())
                        .run("refusal", "expire", "R-OLD");

        assertThat(result.exit()).isEqualTo(69);
    }

    private Path matcherFile(String json) throws IOException {
        return Files.writeString(home.resolve("matcher.json"), json);
    }

    private String target() {
        return controller.received()
                         .getLast()
                         .target();
    }

    private RefusalAddRequest sentRequest() {
        return TemperJson.mapper()
                         .readString(controller.received()
                                               .getLast()
                                               .body(),
                                     RefusalAddRequest.class)
                         .fold(cause -> fail(cause.message()), request -> request);
    }
}
