package org.pragmatica.temper.cli.command;

import java.util.List;
import java.util.Map;

import org.pragmatica.temper.cli.transport.FleetLine;
import org.pragmatica.temper.protocol.json.TemperJson;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Which heartbeat sources an `events --until` wait depends on (exit 4).
class DependenciesTest {
    private static FleetLine.Heartbeat beat(Map<String, Boolean> stale) {
        return new FleetLine.Heartbeat("e", 0, stale, true, List.of());
    }

    private static EventMatch match(String raw) {
        return EventMatch.eventMatch(raw).unwrap();
    }

    @Test
    void githubKinds_dependOnKilnAndGithub_andUnknownKindsCountAsKiln() {
        assertThat(Dependencies.dependencies(List.of("check-concluded", "required-list-changed-v2"), List.of())).isEqualTo(new Dependencies(List.of("selfcheck:highwater",
                                                                                                                                                    "kiln",
                                                                                                                                                    "kiln:github",
                                                                                                                                                    "kiln:highwater"),
                                                                                                                                            false));
    }

    @Test
    void controllerKinds_dependOnTheStreamOnly() {
        var stream = Dependencies.dependencies(List.copyOf(Dependencies.CONTROLLER_KINDS), List.of());

        assertThat(stream).isEqualTo(new Dependencies(List.of("selfcheck:highwater"), false));
        assertThat(stream.staleIn(beat(Map.of("kiln", true, "selfcheck:highwater", false))).isEmpty()).isTrue();
        assertThat(stream.staleIn(beat(Map.of("selfcheck:highwater", true))).stream()).containsExactly("selfcheck:highwater");
    }

    /// Lead ruling 11-03: the stream's integrity gates every wait, and is named before a kind's
    /// own source.
    @Test
    void everyWait_dependsOnSelfcheckHighwaterFirst() {
        var github = Dependencies.dependencies(List.of("pr-state"), List.of());
        var host = Dependencies.dependencies(List.of("host-answering"), List.of(match("host=bigboy")));

        assertThat(github.staleIn(beat(Map.of("kiln", true, "kiln:github", false, "selfcheck:highwater", true)))
                         .stream()).containsExactly("selfcheck:highwater");
        assertThat(host.staleIn(beat(Map.of("hostlink:bigboy", false, "selfcheck:highwater", true))).stream()).containsExactly("selfcheck:highwater");
        assertThat(github.staleIn(beat(Map.of("kiln",
                                              false,
                                              "kiln:github",
                                              false,
                                              "kiln:highwater",
                                              true,
                                              "selfcheck:highwater",
                                              false)))
                         .stream()).as("kiln's feed integrity gates a kiln kind")
                  .containsExactly("kiln:highwater");
        assertThat(host.staleIn(beat(Map.of("hostlink:bigboy",
                                            false,
                                            "kiln:highwater",
                                            true,
                                            "selfcheck:highwater",
                                            false)))
                       .isEmpty()).as("but not a host kind")
                  .isTrue();
        assertThat(host.staleIn(beat(Map.of("hostlink:bigboy", false))).stream()).as("absent counts as stale")
                  .containsExactly("selfcheck:highwater");
    }

    /// Lead ruling 2026-11-04: the detector's kinds depend on kiln, kiln:github, kiln:highwater and
    /// t2:detector, after the every-wait selfcheck:highwater; `verdict-registered` is the controller's own.
    @Test
    void t2DetectorKinds_dependOnKilnAndTheDetector() {
        for (var kind : List.of("ungated-merge", "merged", "queue-advanced", "go-invalidated")) {
            assertThat(Dependencies.dependencies(List.of(kind),
                                                 List.of())).as(kind)
                      .isEqualTo(new Dependencies(List.of("selfcheck:highwater",
                                                          "kiln",
                                                          "kiln:github",
                                                          "kiln:highwater",
                                                          "t2:detector"),
                                                  false));
        }

        assertThat(Dependencies.dependencies(List.of("verdict-registered"), List.of())).isEqualTo(new Dependencies(List.of("selfcheck:highwater"),
                                                                                                                   false));
        assertThat(Dependencies.dependencies(List.of("base-moved", "pr-state"), List.of())).isEqualTo(new Dependencies(List.of("selfcheck:highwater",
                                                                                                                               "kiln",
                                                                                                                               "kiln:github",
                                                                                                                               "kiln:highwater"),
                                                                                                                       false));
    }

    /// D.stale (C part): with the detector stopped (stale in the heartbeat), a wait on `ungated-merge` ends on it
    /// (exit 4). Mutation: omit t2:detector from Dependencies → nothing stale.
    @Test
    void staleIn_namesTheDetector_whenItIsStale() {
        var ungated = Dependencies.dependencies(List.of("ungated-merge"), List.of());

        assertThat(ungated.staleIn(beat(Map.of("selfcheck:highwater",
                                               false,
                                               "kiln",
                                               false,
                                               "kiln:github",
                                               false,
                                               "kiln:highwater",
                                               false,
                                               "t2:detector",
                                               true)))
                          .stream()).containsExactly("t2:detector");
        assertThat(ungated.staleIn(beat(Map.of("selfcheck:highwater",
                                               false,
                                               "kiln",
                                               false,
                                               "kiln:github",
                                               false,
                                               "kiln:highwater",
                                               false,
                                               "t2:detector",
                                               false)))
                          .isEmpty()).isTrue();
    }

    @Test
    void hostKinds_dependOnTheMatchedHostLink() {
        assertThat(Dependencies.dependencies(List.of("host-unreachable"), List.of(match("subject=cloudbb-3")))).isEqualTo(new Dependencies(List.of("selfcheck:highwater",
                                                                                                                                                   "hostlink:cloudbb-3"),
                                                                                                                                           false));
        assertThat(Dependencies.dependencies(List.of("host-answering"), List.of(match("host=bigboy")))).isEqualTo(new Dependencies(List.of("selfcheck:highwater",
                                                                                                                                           "hostlink:bigboy"),
                                                                                                                                   false));
        assertThat(Dependencies.dependencies(List.of("host-answering"), List.of(match("reason=x")))).isEqualTo(new Dependencies(List.of("selfcheck:highwater"),
                                                                                                                                true));
    }

    @Test
    void staleIn_namesTheFirstStaleOrAbsentSource() {
        var github = Dependencies.dependencies(List.of("pr-state"), List.of());

        assertThat(github.staleIn(beat(Map.of("kiln",
                                              false,
                                              "kiln:github",
                                              false,
                                              "kiln:highwater",
                                              false,
                                              "selfcheck:highwater",
                                              false)))
                         .isEmpty()).isTrue();
        assertThat(github.staleIn(beat(Map.of("kiln", true, "kiln:github", true, "selfcheck:highwater", false)))
                         .stream()).containsExactly("kiln");
        assertThat(github.staleIn(beat(Map.of("kiln", false, "kiln:highwater", false, "selfcheck:highwater", false)))
                         .stream()).containsExactly("kiln:github");
    }

    @Test
    void staleIn_withEveryHostLink_namesAStaleHostLinkOnly() {
        var hosts = Dependencies.dependencies(List.of("host-answering"), List.of());

        assertThat(hosts.staleIn(beat(Map.of("kiln", true, "hostlink:bigboy", false, "selfcheck:highwater", false)))
                        .isEmpty()).isTrue();
        assertThat(hosts.staleIn(beat(Map.of("kiln",
                                             true,
                                             "hostlink:bigboy",
                                             false,
                                             "hostlink:cloudbb-4",
                                             true,
                                             "selfcheck:highwater",
                                             false)))
                        .stream()).containsExactly("hostlink:cloudbb-4");
    }

    @Test
    void eventMatch_holdsOnSubjectOrATopLevelPayloadValue() {
        var json = TemperJson.mapper();
        var payload = json.readTree("{\"attempt\":2,\"conclusion\":\"failure\",\"nested\":{\"a\":1},\"none\":null}")
                          .unwrap();
        var event = new FleetLine.Event("l", "e", 1, "check-concluded", "acme/app#7", false, payload);

        assertThat(match("subject=acme/app#7").holdsFor(event)).isTrue();
        assertThat(match("subject=acme/app#8").holdsFor(event)).isFalse();
        assertThat(match("attempt=2").holdsFor(event)).isTrue();
        assertThat(match("conclusion=failure").holdsFor(event)).isTrue();
        assertThat(match("conclusion=success").holdsFor(event)).isFalse();
        assertThat(match("nested={\"a\":1}").holdsFor(event)).isFalse();
        assertThat(match("none=null").holdsFor(event)).isFalse();
        assertThat(match("missing=").holdsFor(event)).isFalse();
        assertThat(match("k=v=w").value()).isEqualTo("v=w");
    }

    /// A ticket kind depends on `tickets:<h>` for the `host` match, after the every-wait stream source; with
    /// no host match on every stale `tickets:*` source; never on kiln. `subject` does not name the host (it is `<h>/<id>`).
    @Test
    void ticketKinds_dependOnTheHostsTicketSource() {
        for (var kind : List.of("ticket-queued", "ticket-started", "ticket-finished")) {
            var named = Dependencies.dependencies(List.of(kind), List.of(match("host=bigboy")));

            assertThat(named).as(kind)
                      .isEqualTo(new Dependencies(List.of("selfcheck:highwater", "tickets:bigboy"),
                                                  false));
            assertThat(named.staleIn(beat(Map.of("selfcheck:highwater", false, "tickets:bigboy", true, "kiln", true)))
                            .stream()).containsExactly("tickets:bigboy");
            assertThat(named.staleIn(beat(Map.of("selfcheck:highwater",
                                                 false,
                                                 "tickets:bigboy",
                                                 false,
                                                 "tickets:other",
                                                 true,
                                                 "kiln",
                                                 true)))
                            .isEmpty()).isTrue();
            assertThat(named.staleIn(beat(Map.of("selfcheck:highwater", false))).stream()).as("absent counts as stale")
                      .containsExactly("tickets:bigboy");
        }

        var every = Dependencies.dependencies(List.of("ticket-finished"),
                                              List.of(match("ticket=7"), match("subject=bigboy/7")));

        assertThat(every.named()).containsExactly("selfcheck:highwater");
        assertThat(every.staleIn(beat(Map.of("selfcheck:highwater",
                                             false,
                                             "tickets:a",
                                             false,
                                             "tickets:b",
                                             true,
                                             "hostlink:c",
                                             true)))
                        .stream()).containsExactly("tickets:b");
    }

    /// S1.16: `queue-wait`, `portgroup-held`, `waiter-gone` and `clock-skew` are evaluated
    /// in the collection round, so `--match host=h` depends on `tickets:h` and never on kiln; `host-retiring` is the
    /// controller's own and depends on no source but the stream. Mutation: leave them as kiln kinds (the CLI's fail-closed
    /// default) → the named list carries `kiln`, `kiln:github`, `kiln:highwater`, red.
    @Test
    void s1_16_collectionAlarms_dependOnTheHostsTicketSource_hostRetiringOnNone() {
        for (var kind : List.of("queue-wait", "portgroup-held", "waiter-gone", "clock-skew")) {
            var named = Dependencies.dependencies(List.of(kind), List.of(match("host=h")));

            assertThat(named).as(kind).isEqualTo(new Dependencies(List.of("selfcheck:highwater", "tickets:h"), false));
            assertThat(named.staleIn(beat(Map.of("selfcheck:highwater", false, "tickets:h", true, "kiln", false)))
                            .stream()).as(kind)
                      .containsExactly("tickets:h");
            assertThat(named.staleIn(beat(Map.of("selfcheck:highwater", false, "tickets:h", false, "kiln", true)))
                            .isEmpty()).as(kind + " never on kiln")
                      .isTrue();
        }

        var retiring = Dependencies.dependencies(List.of("host-retiring"), List.of(match("host=h")));

        assertThat(retiring.named()).containsExactly("selfcheck:highwater");
        assertThat(retiring.staleIn(beat(Map.of("selfcheck:highwater", false, "kiln", true, "tickets:h", true)))
                           .isEmpty()).isTrue();
    }
}
