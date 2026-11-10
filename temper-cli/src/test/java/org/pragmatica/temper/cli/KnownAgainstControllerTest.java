package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.protocol.api.KnownAddRequest;
import org.pragmatica.temper.protocol.api.KnownDto;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.cli.FakeController.Reply;

/// `known list|add|expire` (m2-design §4.4) as whole CLI invocations against the fake controller: 0 on success, 2 for a
/// 409, 64 for usage, a 404 or a 400, 69 when the controller is unreachable. Bodies are the protocol DTOs written and
/// read by the CLI's own mapper (`TemperJson`), so the wire shapes are the controller's.
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KnownAgainstControllerTest {
    private static final Instant EXPIRED_AT = Instant.parse("2026-10-19T12:00:00Z");
    private static final String ACTIVE_LINE = "known 12 ACTIVE test=org.x.EmberAmnesiacRestartTest issue=1679 until=\"#1679 closes\" "
                                              + "reason=\"flaky until #1679\" by=cto";
    private static final String EXPIRED_LINE = "known 13 EXPIRED test=org.x.FooTest#one issue=1679 until=\"#1679 closes\" "
                                               + "reason=\"flaky until #1679\" by=cto expired-at=2026-10-19T12:00:00Z";
    private static final String[] ADD = {"known",
                                         "add",
                                         "org.x.EmberAmnesiacRestartTest",
                                         "--issue",
                                         "1679",
                                         "--reason",
                                         "flaky until #1679",
                                         "--until",
                                         "#1679 closes"};

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
    void list_printsOneLinePerEntry_activeOnlyByDefault() {
        controller.onJson("GET /known", (Object) new KnownDto[]{Fixtures.known(12, "org.x.EmberAmnesiacRestartTest", none())});

        var result = cli.run("known", "list");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.out()).containsExactly(ACTIVE_LINE);
        assertThat(target()).isEqualTo("/api/v1/known?all=false");
    }

    @Test
    void list_all_asksForExpiredToo_andMarksThem() {
        controller.onJson("GET /known",
                          (Object) new KnownDto[]{Fixtures.known(12, "org.x.EmberAmnesiacRestartTest", none()),
                                                  Fixtures.known(13, "org.x.FooTest#one", some(EXPIRED_AT))});

        var result = cli.run("known", "list", "--all");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly(ACTIVE_LINE, EXPIRED_LINE);
        assertThat(target()).isEqualTo("/api/v1/known?all=true");
    }

    /// Ruling R-M2-15: a row the controller could not read is listed as UNREADABLE with its id and why, and `--json`
    /// carries it with its `unreadable` reason.
    @Test
    void list_showsAnUnreadableRow_inTheLineAndInJson() {
        controller.onJson("GET /known", (Object) new KnownDto[]{Fixtures.unreadableKnown(14)});

        var line = cli.run("known", "list");

        controller.onJson("GET /known", (Object) new KnownDto[]{Fixtures.unreadableKnown(14)});
        var json = cli.run("known", "list", "--json");

        assertThat(line.exit()).as(line.err()).isZero();
        assertThat(line.out()).containsExactly("known 14 UNREADABLE test=org.x.UnreadableTest issue=7 until=u reason=\" \" by=cto "
                                               + "unreadable=\"Invalid reason: ' '\"");
        assertThat(json.exit()).as(json.err()).isZero();
        assertThat(TemperJson.mapper()
                             .readString(String.join("\n", json.out()), KnownDto[].class)
                             .unwrap()).containsExactly(Fixtures.unreadableKnown(14));
    }

    @Test
    void expire_ofAnUnreadableRow_printsItExpired_exitsZero() {
        controller.onJson("POST /known/14/expire", Fixtures.unreadableKnown(14));

        var result = cli.run("known", "expire", "14");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).singleElement().asString().startsWith("known 14 UNREADABLE ");
    }

    @Test
    void list_ofNoEntries_printsNothing() {
        controller.on("GET /known", Reply.json(200, "[]"));

        var result = cli.run("known", "list");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEmpty();
    }

    /// The ledger left M2 (ruling R-M2-3): `known list --ledger` stays the later form, answered without a controller.
    @Test
    void list_ledger_isNotInM0_exits64_withoutContactingTheController() {
        var result = cli.run("known", "list", "--ledger");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.out()).containsExactly("known - USAGE not in M0");
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void add_postsTheRequest_withTheConfiguredOwner_andPrintsTheStoredEntry() {
        controller.on("POST /known", Reply.json(201, controller.write(Fixtures.known(12, "org.x.EmberAmnesiacRestartTest", none()))));

        var result = cli.run(ADD);

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.out()).containsExactly(ACTIVE_LINE);
        assertThat(sentRequest()).isEqualTo(KnownAddRequest.knownAddRequest("org.x.EmberAmnesiacRestartTest",
                                                                            1679,
                                                                            "flaky until #1679",
                                                                            "#1679 closes",
                                                                            "tester"));
    }

    @Test
    void add_ownerFlag_winsOverTheEnvironment() {
        controller.on("POST /known", Reply.json(201, controller.write(Fixtures.known(12, "org.x.EmberAmnesiacRestartTest", none()))));

        var result = cli.run(withOwner("cto"));

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(sentRequest().createdBy()).isEqualTo("cto");
    }

    @Test
    void add_withoutAnOwner_exits64_withoutContactingTheController() {
        var result = cli.env("TEMPER_OWNER", null)
                        .run(ADD);

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("known add needs an owner");
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void add_withoutUntil_exits64_withoutContactingTheController() {
        var result = cli.run("known", "add", "org.x.FooTest", "--issue", "1679", "--reason", "flaky");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("known add needs --until");
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void add_ofAnActiveTest_exitsTwo_withTheControllersReasonOnStderr() {
        controller.on("POST /known", Reply.json(409, "{\"error\":\"An active known entry for org.x.EmberAmnesiacRestartTest already exists\"}"));

        var result = cli.run(ADD);

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err().lines()).containsExactly("temper: Controller refused the request: "
                                                         + "An active known entry for org.x.EmberAmnesiacRestartTest already exists");
    }

    @Test
    void add_rejectedByTheController_exits64() {
        controller.on("POST /known", Reply.json(400, "{\"error\":\"Invalid testId: 'org.x.'\"}"));

        var result = cli.run("known", "add", "org.x.", "--issue", "1", "--reason", "r", "--until", "u");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("Invalid testId: 'org.x.'");
    }

    @Test
    void add_rejectedForSeveralReasons_printsEveryReasonOnOneLine() {
        controller.on("POST /known",
                      Reply.json(400, "{\"error\":\"Composite:\\n  Invalid testId: 'org.x.'\\n  Invalid until: ' '\"}"));

        var result = cli.run("known", "add", "org.x.", "--issue", "1", "--reason", "r", "--until", " ");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err().lines()).containsExactly("temper: Controller rejected the request: Composite:; Invalid testId: 'org.x.'; "
                                                         + "Invalid until: ' '");
    }

    @Test
    void add_unreachableController_exits69() throws IOException {
        var result = cli.env("TEMPER_URL", "http://127.0.0.1:" + ExitCodeContractTest.closedPort())
                        .run(ADD);

        assertThat(result.exit()).isEqualTo(69);
    }

    @Test
    void expire_postsToTheIdsRoute_andPrintsTheExpiredEntry() {
        controller.onJson("POST /known/13/expire", Fixtures.known(13, "org.x.FooTest#one", some(EXPIRED_AT)));

        var result = cli.run("known", "expire", "13");

        assertThat(result.exit()).as(result.err())
                                 .isZero();
        assertThat(result.out()).containsExactly(EXPIRED_LINE);
        assertThat(controller.received()
                             .getLast()
                             .method()).isEqualTo("POST");
    }

    @Test
    void expire_ofAnUnknownId_exits64() {
        var result = cli.run("known", "expire", "404");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("Not found: /known/404/expire");
    }

    /// The id is judged locally under its own name, not as a job id.
    @Test
    void expire_ofANonPositiveOrNonNumericId_exits64_namingTheKnownId() {
        var zero = cli.run("known", "expire", "0");
        var word = cli.run("known", "expire", "twelve");

        assertThat(zero.exit()).isEqualTo(64);
        assertThat(zero.err()).contains("known id must be positive: 0");
        assertThat(word.exit()).isEqualTo(64);
        assertThat(word.err()).contains("Invalid known id: twelve");
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void expire_unreachableController_exits69() throws IOException {
        var result = cli.env("TEMPER_URL", "http://127.0.0.1:" + ExitCodeContractTest.closedPort())
                        .run("known", "expire", "13");

        assertThat(result.exit()).isEqualTo(69);
    }

    @Test
    void list_unreachableController_exits69() throws IOException {
        var result = cli.env("TEMPER_URL", "http://127.0.0.1:" + ExitCodeContractTest.closedPort())
                        .run("known", "list");

        assertThat(result.exit()).isEqualTo(69);
    }

    @Test
    void bareGroup_namesItsOperations_exits64() {
        var result = cli.run("known");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("'known' needs an operation: add|expire|list");
    }

    private static String[] withOwner(String owner) {
        var args = Arrays.copyOf(ADD, ADD.length + 2);

        args[ADD.length] = "--owner";
        args[ADD.length + 1] = owner;
        return args;
    }

    private String target() {
        return controller.received()
                         .getLast()
                         .target();
    }

    private KnownAddRequest sentRequest() {
        return TemperJson.mapper()
                         .readString(controller.received()
                                               .getLast()
                                               .body(),
                                     KnownAddRequest.class)
                         .fold(cause -> fail(cause.message()), request -> request);
    }
}
