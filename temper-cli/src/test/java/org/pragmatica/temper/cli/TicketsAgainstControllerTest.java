package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.temper.cli.FakeController.Reply;
import static org.pragmatica.temper.cli.FleetFixtures.EPOCH;
import static org.pragmatica.temper.cli.FleetFixtures.heartbeat;
import static org.pragmatica.temper.cli.FleetFixtures.reply;
import static org.assertj.core.api.Assertions.assertThat;


/// `temper tickets` (WP-L) against a stub serving the reply, and the `ticket-*` wait
/// dependency. The CLI prints the controller's liveness text; it computes none.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TicketsAgainstControllerTest {
    private static final String TICKETS = "GET /fleet/tickets";
    private static final String AFTER = "GET /fleet/events/after";
    private static final String MAC_OK = "2026-11-04T10:00:00Z";

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

    private static Map<String, Object> map(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();

        for (var i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }

        return map;
    }

    private static Map<String, Object> ticket(String id, String dir, String liveness, String agent) {
        return map("id",
                   id,
                   "dir",
                   dir,
                   "owner",
                   "t3wpl",
                   "liveness",
                   liveness,
                   "agent",
                   agent,
                   "process",
                   "waiter-alive",
                   "lane",
                   "L",
                   "pr",
                   71,
                   "co",
                   "/home/builder/work/co/t3wpl-build",
                   "output",
                   "/home/builder/work/co/t3wpl-build/.bb/out",
                   "sessionLabel",
                   "CTO",
                   "createdAt",
                   "2026-11-04T09:00:00Z",
                   "args",
                   "-B clean install");
    }

    private static Map<String, Object> okHost(String host,
                                              List<Map<String, Object>> tickets,
                                              List<Map<String, Object>> done) {
        return map("host",
                   host,
                   "via",
                   "hostlink",
                   "status",
                   "ok",
                   "since",
                   null,
                   "error",
                   null,
                   "takenAt",
                   "2026-11-04T10:00:05Z",
                   "queue",
                   "present",
                   "tickets",
                   tickets,
                   "done",
                   done);
    }

    private static Map<String, Object> down(String host, String since, String error) {
        return map("host",
                   host,
                   "via",
                   "hostlink",
                   "status",
                   "UNREACHABLE",
                   "since",
                   since,
                   "error",
                   error,
                   "takenAt",
                   null,
                   "queue",
                   null,
                   "tickets",
                   List.of(),
                   "done",
                   List.of());
    }

    private static Map<String, Object> retired(String host) {
        Map<String, Object> row = new java.util.HashMap<>(down(host, null, null));

        row.put("status", "RETIRED");
        return row;
    }

    private static Map<String, Object> uncollected(String host) {
        return map("host",
                   host,
                   "via",
                   "none",
                   "status",
                   "NOT-COLLECTED",
                   "since",
                   null,
                   "error",
                   null,
                   "takenAt",
                   null,
                   "queue",
                   null,
                   "tickets",
                   List.of(),
                   "done",
                   List.of());
    }

    private static Map<String, Object> mac(boolean stale, String postedAt, int sessions) {
        return map("lastOkAt", MAC_OK, "stale", stale, "postedAt", postedAt, "sessions", sessions);
    }

    private static Map<String, Object> payload(Map<String, Object> mac, Map<String, Object>... hosts) {
        return map("v", 1, "asOf", "2026-11-04T10:00:10Z", "mac", mac, "hosts", List.of(hosts));
    }

    private static Map<String, Object> done(String id) {
        return map("id",
                   id,
                   "state",
                   "done",
                   "rc",
                   "0",
                   "endedAt",
                   "2026-11-04T08:00:00Z",
                   "owner",
                   "t3wpb",
                   "agent",
                   null,
                   "co",
                   "/home/builder/work/co/t3wpb-x",
                   "output",
                   "/home/builder/work/co/t3wpb-x/.bb/out");
    }

    @SafeVarargs
    private void serve(Map<String, Object> mac, Map<String, Object>... hosts) {
        controller.onJson(TICKETS, payload(mac, hosts));
    }

    /// The five owner-liveness values are printed exactly as the controller computed them; none is rewritten.
    /// Mutation: print DEAD for everything, or drop the liveness column → red.
    @Test
    void everyLivenessValue_isPrintedVerbatim() {
        var values = List.of("SESSION-ALIVE",
                             "DEAD(last seen 2026-11-04T09:30:00Z)",
                             "DEAD(last seen never)",
                             "DETACHED(reader=lead1 ALIVE)",
                             "DETACHED(reader=lead1 DEAD)",
                             "STALE-SOURCE(mac job silent since 2026-11-04T09:00:00Z)",
                             "STALE-SOURCE(mac job silent since never)",
                             "UNKNOWN(no session field)");
        var tickets = new java.util.ArrayList<Map<String, Object>>();

        for (var i = 0; i < values.size(); i++) {
            tickets.add(ticket("t" + i, "running", values.get(i), null));
        }

        serve(mac(false, MAC_OK, 3), okHost("bigboy", tickets, List.of()));
        var run = cli.run("tickets");

        for (var i = 0; i < values.size(); i++) {
            assertThat(run.out()).contains("  running t" + i
                                          + " | " + values.get(i)
                                          + " | waiter-alive | owner=t3wpl co=t3wpl-build lane=L pr=71"
                                          + " output=/home/builder/work/co/t3wpl-build/.bb/out");
        }
    }

    /// T3.6: a ticket with an agent never prints a bare liveness. Mutation: drop the suffix → red.
    @Test
    void agent_followsTheLiveness_neverABareValue() {
        serve(mac(false, MAC_OK, 1),
              okHost("bigboy",
                     List.of(ticket("a1", "running", "SESSION-ALIVE", "t3wpb"),
                             ticket("a2", "waiting", "SESSION-ALIVE", null)),
                     List.of()));
        var run = cli.run("tickets");

        assertThat(run.out().get(2)).contains(" | SESSION-ALIVE agent=t3wpb | ");
        assertThat(run.out().get(3)).contains(" | SESSION-ALIVE | ").doesNotContain("agent=");
    }

    /// L.out: every host status and a done section, exact lines; exit 0 when the Mac is fresh, a host UNREACHABLE makes it 4
    /// (T3.8), NOT-COLLECTED never does (T3.9).
    @Test
    @SuppressWarnings("unchecked")
    void golden_everyHostStatus() {
        var orphan = ticket("w1", "waiting", "UNKNOWN(no session field)", null);

        orphan.put("process", "process-orphan");
        orphan.put("owner", null);
        orphan.put("lane", null);
        orphan.put("pr", null);
        orphan.put("co", null);
        orphan.put("output", null);
        serve(mac(false, MAC_OK, 3),
              okHost("bigboy", List.of(orphan, ticket("r1", "running", "SESSION-ALIVE", "t3wpb")), List.of(done("d1"))),
              down("cloudbb-3", "2026-11-04T09:58:00Z", "ssh exit 255: Connection refused"),
              uncollected("oldbox"));
        var run = cli.run("tickets");

        assertThat(run.exit()).as(run.err()).isEqualTo(4);
        assertThat(run.out()).containsExactly("mac ok last-post=" + MAC_OK + " sessions=3",
                                              "host bigboy ok taken=2026-11-04T10:00:05Z running=1 waiting=1 done=1",
                                              "  running r1 | SESSION-ALIVE agent=t3wpb | waiter-alive | owner=t3wpl co=t3wpl-build lane=L pr=71 output=/home/builder/work/co/t3wpl-build/.bb/out",
                                              "  waiting w1 | UNKNOWN(no session field) | process-orphan | owner=- co=- lane=- pr=- output=-",
                                              "  done d1 | done rc=0 ended=2026-11-04T08:00:00Z | owner=t3wpb agent=- co=t3wpb-x output=/home/builder/work/co/t3wpb-x/.bb/out",
                                              "host cloudbb-3 UNREACHABLE since 2026-11-04T09:58:00Z (ssh exit 255: Connection refused)",
                                              "host oldbox NOT-COLLECTED (no link)");
    }

    /// #107: a RETIRED host is one line and never sets exit 4: with only retired hosts, or retired plus healthy, the exit
    /// is 0. Mutation: count RETIRED as unreachable (`isUnreachable`) → exit 4, red.
    @Test
    void retiredHosts_areOneLine_andNeverSetExitFour() {
        serve(mac(false, MAC_OK, 3), okHost("bigboy", List.of(), List.of()), retired("cloudbb-3"));
        var mixed = cli.run("tickets");

        assertThat(mixed.exit()).as(mixed.err()).isZero();
        assertThat(mixed.out()).contains("host cloudbb-3 RETIRED (past retireAt, not read)");

        serve(mac(false, MAC_OK, 3), retired("cloudbb-3"), retired("cloudbb-4"));
        var only = cli.run("tickets");

        assertThat(only.exit()).as(only.err()).isZero();
        assertThat(only.out()).contains("host cloudbb-3 RETIRED (past retireAt, not read)", "host cloudbb-4 RETIRED (past retireAt, not read)");
    }

    /// T3.8: an UNREACHABLE host is one row, never with tickets, even when the reply carries some. Mutation: print the
    /// host's stale tickets / skip the row / exit 0 → red.
    @Test
    void unreachableHost_isOneRow_withoutItsTickets_andExitsFour() {
        var stale = down("h2", null, null);

        stale.put("tickets", List.of(ticket("old", "running", "SESSION-ALIVE", null)));
        stale.put("done", List.of(done("oldd")));
        serve(mac(false, MAC_OK, 0),
              okHost("h1", List.of(ticket("t", "running", "DEAD(last seen never)", null)), List.of()),
              stale);
        var run = cli.run("tickets");

        assertThat(run.exit()).isEqualTo(4);
        assertThat(run.out()).hasSize(4)
                  .contains("host h2 UNREACHABLE since never")
                  .noneMatch(line -> line.contains(" old"));
    }

    /// The error text is cut to 120 characters and every character outside 0x20-0x7e shows as `?`.
    @Test
    void error_isCutTo120_andNonPrintableBecomeQuestionMarks() {
        var error = "x".repeat(110) + "\né\t" + "tail-after-the-cut";

        serve(mac(false, MAC_OK, 0), down("h", "2026-11-04T09:58:00Z", error));
        var run = cli.run("tickets");

        assertThat(run.out().get(1)).isEqualTo("host h UNREACHABLE since 2026-11-04T09:58:00Z ("
                                              + "x".repeat(110)
                                              + "???"
                                              + "tail-af"
                                              + ")");
    }

    /// T3.9: a bbHosts entry with no address is configuration, exit 0.
    @Test
    void notCollected_neverSetsExit() {
        serve(mac(false, MAC_OK, 0), uncollected("oldbox"));
        var run = cli.run("tickets");

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly("mac ok last-post=" + MAC_OK + " sessions=0",
                                              "host oldbox NOT-COLLECTED (no link)");
    }

    @Test
    void staleMac_printsTheStaleLine_andExitsFour() {
        serve(mac(true, null, 0),
              okHost("bigboy", List.of(), List.of()));
        var run = cli.run("tickets");

        assertThat(run.exit()).isEqualTo(4);
        assertThat(run.out()).containsExactly("mac STALE-SOURCE last-post=never",
                                              "host bigboy ok taken=2026-11-04T10:00:05Z running=0 waiting=0 done=0");
        serve(mac(true, "2026-11-04T09:00:00Z", 2),
              okHost("bigboy", List.of(), List.of()));
        assertThat(cli.run("tickets").out().getFirst()).isEqualTo("mac STALE-SOURCE last-post=2026-11-04T09:00:00Z");
    }

    @Test
    void queueAbsent_isNamed_andWaitingIsListedAfterRunning() {
        var host = okHost("bigboy",
                          List.of(ticket("w", "waiting", "DEAD(last seen never)", null),
                                  ticket("r", "running", "DEAD(last seen never)", null)),
                          List.of());

        host.put("queue", "absent");
        serve(mac(false, MAC_OK, 0), host);
        var run = cli.run("tickets");

        assertThat(run.exit()).isZero();
        assertThat(run.out().get(1)).endsWith("running=1 waiting=1 done=0 queue=absent");
        assertThat(run.out().get(2)).startsWith("  running r ");
        assertThat(run.out().get(3)).startsWith("  waiting w ");
    }

    @Test
    void query_carriesHostAndDone_andOnlyWhenGiven() {
        serve(mac(false, MAC_OK, 0),
              okHost("bigboy", List.of(), List.of()));
        cli.run("tickets");
        cli.run("tickets", "--host", "bigboy", "--done", "0");
        cli.run("tickets", "--done", "200");
        assertThat(controller.received().stream().map(FakeController.Received::target)).containsExactly("/api/v1/fleet/tickets",
                                                                                                        "/api/v1/fleet/tickets?host=bigboy&done=0",
                                                                                                        "/api/v1/fleet/tickets?done=200");
        assertThat(controller.received()).allSatisfy(call -> assertThat(call.authorization()).isEqualTo("Bearer " + CliHarness.TOKEN));
    }

    @Test
    void json_printsTheReplyVerbatim_withTheSameExit() {
        var body = "{\"v\":1,\"asOf\":\"2026-11-04T10:00:10Z\",\"mac\":{\"lastOkAt\":null,\"stale\":true,\"postedAt\":null,\"sessions\":0},\"hosts\":[]}";

        controller.on(TICKETS, Reply.json(200, body + "\n"));
        var run = cli.run("tickets", "--json");

        assertThat(run.exit()).isEqualTo(4);
        assertThat(run.out()).containsExactly(body);
    }

    // --- exit codes ---
    @Test
    void usage_isSixtyFour_withoutACall() {
        for (var args : List.of(new String[]{"tickets", "--done", "201"},
                                new String[]{"tickets", "--done", "-1"},
                                new String[]{"tickets", "--done", "x"},
                                new String[]{"tickets", "--host", "-bad"},
                                new String[]{"tickets", "--host"},
                                new String[]{"tickets", "extra"},
                                new String[]{"tickets", "--all"})) {
            assertThat(cli.run(args).exit()).as(String.join(" ", args)).isEqualTo(64);
        }

        assertThat(controller.received()).isEmpty();
    }

    /// T3.tun: the controller's `404 NO_HOST` is a usage-class refusal, 64.
    @Test
    void unknownHost_isSixtyFour() {
        controller.on(TICKETS, Reply.json(404, "{\"error\":\"NO_HOST\"}"));
        var run = cli.run("tickets", "--host", "nope");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.err()).contains("NO_HOST");
    }

    @Test
    void malformedDone_rejectedByTheController_isSixtyFour() {
        controller.on(TICKETS, Reply.json(400, "{\"error\":\"bad done\"}"));
        assertThat(cli.run("tickets").exit()).isEqualTo(64);
    }

    @Test
    void refusedToken_isSeventySeven() throws IOException {
        var wrong = CliHarness.withToken(home.resolve("w"), "other").env("TEMPER_URL", controller.url());

        assertThat(wrong.run("tickets").exit()).isEqualTo(77);
        controller.on(TICKETS, Reply.json(403, "{\"error\":\"forbidden\"}"));
        assertThat(cli.run("tickets").exit()).isEqualTo(77);
    }

    @Test
    void unavailableController_isSixtyNine() throws IOException {
        var down = CliHarness.withToken(home.resolve("d"), CliHarness.TOKEN).env("TEMPER_URL", "http://127.0.0.1:1");

        assertThat(down.run("tickets").exit()).isEqualTo(69);
        controller.on(TICKETS, Reply.json(503, "{}"));
        assertThat(cli.run("tickets").exit()).isEqualTo(69);
    }

    @Test
    void versionSkew_isThirteen() {
        serve(mac(false, MAC_OK, 0));
        controller.temperVersion(Optional.of("0.1.0-SNAPSHOT+222222222222"));
        var run = cli.version("0.1.0-SNAPSHOT+111111111111").run("tickets");

        assertThat(run.exit()).isEqualTo(13);
        assertThat(run.out()).isEmpty();
    }

    /// Malformed or unknown JSON is an unreadable answer (69), and nothing is printed: a half-read listing is not shown.
    @Test
    void malformedAndUnknownJson_areSixtyNine_andPrintNothing() {
        var good = payload(mac(false, MAC_OK, 0),
                           okHost("bigboy", List.of(ticket("t", "running", "SESSION-ALIVE", null)), List.of()));
        var unknownField = new LinkedHashMap<>(good);
        var unknownVersion = new LinkedHashMap<>(good);
        var unknownStatus = payload(mac(false, MAC_OK, 0), map("host", "h", "via", "none", "status", "WEIRD"));
        var unknownDir = payload(mac(false, MAC_OK, 0),
                                 okHost("h", List.of(ticket("t", "sleeping", "SESSION-ALIVE", null)), List.of()));
        var unknownProcess = ticket("t", "running", "SESSION-ALIVE", null);

        unknownField.put("surprise", 1);
        unknownVersion.put("v", 2);
        unknownProcess.put("process", "zombie");
        for (var body : List.of(Reply.json(200, "{not json"),
                                Reply.json(200, ""),
                                Reply.json(200, "[]"),
                                Reply.json(200, "{\"v\":1}"),
                                Reply.json(200, controllerJson(unknownField)),
                                Reply.json(200, controllerJson(unknownVersion)),
                                Reply.json(200, controllerJson(unknownStatus)),
                                Reply.json(200, controllerJson(unknownDir)),
                                Reply.json(200,
                                           controllerJson(payload(mac(false, MAC_OK, 0),
                                                                  okHost("h", List.of(unknownProcess), List.of())))))) {
            controller.on(TICKETS, body);
            var run = cli.run("tickets");

            assertThat(run.exit()).as(body.body()).isEqualTo(69);
            assertThat(run.out()).isEmpty();
        }
    }

    /// A row missing a member the listing prints (a ticket's id or liveness, a done row's id, a host's name) is an
    /// unreadable answer, not a line with `null` in it.
    @Test
    void rowsMissingWhatTheyPrint_areSixtyNine_andPrintNothing() {
        var noLiveness = ticket("t", "running", "SESSION-ALIVE", null);
        var noTicketId = ticket("t", "running", "SESSION-ALIVE", null);
        var noDoneId = done("d");
        var noHostName = okHost("h", List.of(), List.of());

        noLiveness.remove("liveness");
        noTicketId.remove("id");
        noDoneId.remove("id");
        noHostName.remove("host");
        for (var host : List.of(okHost("h", List.of(noLiveness), List.of()),
                                okHost("h", List.of(noTicketId), List.of()),
                                okHost("h", List.of(), List.of(noDoneId)),
                                noHostName)) {
            controller.on(TICKETS, Reply.json(200, controllerJson(payload(mac(false, MAC_OK, 0), host))));
            var run = cli.run("tickets");

            assertThat(run.exit()).as(host.toString()).isEqualTo(69);
            assertThat(run.out()).isEmpty();
        }
    }

    /// The error cut keeps 0x20 and 0x7e and turns 0x1f and 0x7f into `?`; an empty error has no parentheses.
    @Test
    void error_printableRangeBoundaries_andEmptyError() {
        serve(mac(false, MAC_OK, 0), down("h", null, " ~\u007f\u001fa"));
        assertThat(cli.run("tickets").out().get(1)).isEqualTo("host h UNREACHABLE since never ( ~??a)");
        serve(mac(false, MAC_OK, 0), down("h", null, ""));
        assertThat(cli.run("tickets").out().get(1)).isEqualTo("host h UNREACHABLE since never");
        serve(mac(false, MAC_OK, 0), down("h", null, "x".repeat(120) + "y"));
        assertThat(cli.run("tickets").out().get(1)).isEqualTo("host h UNREACHABLE since never (" + "x".repeat(120) + ")");
    }

    /// `co` prints as its last path component: a trailing slash is ignored, a bare `/` has none (`-`).
    @Test
    void co_printsItsBasename() {
        var slash = ticket("a", "running", "DEAD(last seen never)", null);
        var root = ticket("b", "running", "DEAD(last seen never)", null);
        var plain = ticket("c", "running", "DEAD(last seen never)", null);

        slash.put("co", "/work/co/x-y/");
        root.put("co", "/");
        plain.put("co", "bare");
        serve(mac(false, MAC_OK, 0),
              okHost("h", List.of(slash, root, plain), List.of()));
        assertThat(cli.run("tickets").out()).anySatisfy(line -> assertThat(line).startsWith("  running a ")
                                                                          .contains(" co=x-y "))
                  .anySatisfy(line -> assertThat(line).startsWith("  running b ")
                                                .contains(" co=- "))
                  .anySatisfy(line -> assertThat(line).startsWith("  running c ")
                                                .contains(" co=bare "));
    }

    /// Lead ruling 2026-11-04: a null `takenAt` of a collected host prints `-`. Mutation: drop the NONE default → red.
    @Test
    void nullTakenAt_printsDash() {
        var host = okHost("h", List.of(), List.of());

        host.put("takenAt", null);
        serve(mac(false, MAC_OK, 0), host);
        assertThat(cli.run("tickets").out()).containsExactly("mac ok last-post=" + MAC_OK + " sessions=0",
                                                              "host h ok taken=- running=0 waiting=0 done=0");
    }

    /// Every nullable field of a finished ticket prints `-`. Mutation: drop one NONE default, or print a literal
    /// `null`/empty → red.
    @Test
    void doneRow_nullFields_eachPrintDash() {
        var bare = done("d9");

        bare.put("state", null);
        bare.put("rc", null);
        bare.put("endedAt", null);
        bare.put("owner", null);
        bare.put("agent", null);
        bare.put("co", null);
        bare.put("output", null);
        serve(mac(false, MAC_OK, 0), okHost("h", List.of(), List.of(bare)));
        assertThat(cli.run("tickets").out()).containsExactly("mac ok last-post=" + MAC_OK + " sessions=0",
                                                              "host h ok taken=2026-11-04T10:00:05Z running=0 waiting=0 done=1",
                                                              "  done d9 | - rc=- ended=- | owner=- agent=- co=- output=-");
    }

    /// T3.8 + L.out: one UNREACHABLE host together with a stale Mac prints both rows exactly and exits 4.
    @Test
    void unreachableHost_withStaleMac_exactLines_exitFour() {
        serve(mac(true, "2026-11-04T09:00:00Z", 2),
              down("h2", "2026-11-04T09:58:00Z", "ssh exit 255: Connection refused"));
        var run = cli.run("tickets");

        assertThat(run.exit()).as(run.err()).isEqualTo(4);
        assertThat(run.out()).containsExactly("mac STALE-SOURCE last-post=2026-11-04T09:00:00Z",
                                              "host h2 UNREACHABLE since 2026-11-04T09:58:00Z (ssh exit 255: Connection refused)");
    }

    /// A refusal whose body is not JSON is shown as its text.
    @Test
    void rejectedWithPlainText_showsTheText() {
        controller.on(TICKETS, Reply.text(400, "done must be 0..200\n"));
        var run = cli.run("tickets");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.err()).contains("done must be 0..200");
    }

    private static String controllerJson(Map<String, Object> body) {
        return org.pragmatica.temper.protocol.json.TemperJson.mapper()
                                                             .writeAsString(body)
                                                             .unwrap();
    }

    // --- L.dep: the ticket-* wait dependency ---
    /// `events --until ticket-finished --match host=bigboy` exits 4 while `tickets:bigboy` is stale. Mutation: no
    /// dependency for ticket kinds (they fall to kiln) → exit 5/0 → red.
    @Test
    void until_ticketFinished_exitsFour_whenTheHostsTicketSourceIsStale() {
        controller.on(AFTER, reply(heartbeat(EPOCH, 42, sources(Map.of("tickets:bigboy", true)), true)));
        var run = cli.run("events", "--until", "ticket-finished", "--match", "host=bigboy", "--after", EPOCH + ":42");

        assertThat(run.exit()).as(run.err()).isEqualTo(4);
        assertThat(run.err()).contains("tickets:bigboy");
    }

    private static Map<String, Boolean> sources(Map<String, Boolean> overrides) {
        var all = new java.util.TreeMap<>(FleetFixtures.FRESH);

        all.put("tickets:bigboy", false);
        all.put("tickets:cloudbb-3", false);
        all.putAll(overrides);

        return all;
    }

    /// Another host's stale source, and a stale kiln, do not end a wait on bigboy's tickets; a source it depends on that
    /// the heartbeat does not list does (fail closed).
    @Test
    void until_ticketKind_ignoresOtherHostsAndKiln() {
        for (var stale : List.of("tickets:cloudbb-3", "kiln")) {
            controller.on(AFTER, reply(heartbeat(EPOCH, 42, sources(Map.of(stale, true)), true)));
            assertThat(cli.run("events",
                               "--until",
                               "ticket-started",
                               "--match",
                               "host=bigboy",
                               "--after",
                               EPOCH + ":42",
                               "--timeout",
                               "1s")
                          .exit()).as(stale)
                      .isEqualTo(5);
        }

        controller.on(AFTER, reply(heartbeat(EPOCH, 42, FleetFixtures.FRESH, true)));
        assertThat(cli.run("events",
                           "--until",
                           "ticket-started",
                           "--match",
                           "host=bigboy",
                           "--after",
                           EPOCH + ":42",
                           "--timeout",
                           "1s")
                      .exit()).as("tickets:bigboy not listed")
                  .isEqualTo(4);
    }

    @Test
    void until_ticketKind_withoutAHostMatch_dependsOnEveryTicketSource() {
        controller.on(AFTER, reply(heartbeat(EPOCH, 42, sources(Map.of("tickets:cloudbb-3", true)), true)));
        var run = cli.run("events", "--until", "ticket-queued", "--after", EPOCH + ":42");

        assertThat(run.exit()).as(run.err()).isEqualTo(4);
        assertThat(run.err()).contains("tickets:cloudbb-3");
    }
}
