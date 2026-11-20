package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import org.pragmatica.lang.Option;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;


/// The reactor alarm ticket `pid=<n> owner=<o> cwd=<path>`: the path is last and percent-encoded (`%` -> `%25`, space -> `%20`),
/// so it round-trips whatever it holds. Mutations: drop the `%` encoding -> the `%` rows are red (literal `%20` comes back as a
/// space); drop the space encoding -> the space rows are red; cwd before owner -> every row is red (the ticket no longer
/// starts with pid/owner and the cwd read-back takes the owner along).
class ReactorTicketCwdTest {
    private static String ticketOf(String cwd) {
        return ticketOf("orq1", cwd);
    }

    private static String ticketOf(String owner, String cwd) {
        return FleetAlarmsView.Alarm.reactorAlarm("lingering-reactor",
                                                  Option.some(45),
                                                  Option.some(30),
                                                  Instant.parse("2026-11-06T10:00:00Z"),
                                                  77,
                                                  owner,
                                                  cwd)
                                    .ticket()
                                    .or("");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/home/aether/v3455d", "/home/aether/v3455d dir", "/home/aether/100%", "/home/a b/100% done", "/home/aether/a%20b", "/home/aether/%2520"})
    void reactorCwd_roundTripsThroughTheTicketString(String cwd) {
        var ticket = ticketOf(cwd);

        assertThat(ticket).startsWith("pid=77 owner=orq1 cwd=");
        assertThat(ticket.substring("pid=77 owner=orq1 cwd=".length())).doesNotContain(" ");
        assertThat(FleetAlarmsView.Alarm.reactorCwd(ticket)).isEqualTo(Option.some(cwd));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/x y", "/x%y", "/x%20y z"})
    void issueLine_withEncodedCwd_parsesBackToThePath(String cwd) {
        var line = "bigboy lingering-reactor " + ticketOf(cwd) + " age=45m limit=30m";
        var between = line.substring(line.indexOf(" cwd=") + 5, line.lastIndexOf(" age="));

        assertThat(FleetAlarmsView.Alarm.decodeField(between)).isEqualTo(cwd);
    }

    /// The owner is the checkout prefix, a directory name: it may hold a space or `%` too, and is encoded like the cwd, so
    /// the line `pid=.. owner=.. cwd=..` splits on its keys. Mutation: leave the owner unencoded -> every row is red.
    @ParameterizedTest
    @ValueSource(strings = {"orq1", "a b", "100%", "a%20b", "a\"b\\c", "x cwd=y"})
    void reactorOwner_roundTripsThroughTheTicketString_besideACwdWithASpace(String owner) {
        var ticket = ticketOf(owner, "/home/a b/c%d");

        assertThat(ticket).startsWith("pid=77 owner=");
        assertThat(FleetAlarmsView.Alarm.reactorOwner(ticket)).isEqualTo(Option.some(owner));
        assertThat(FleetAlarmsView.Alarm.reactorCwd(ticket)).isEqualTo(Option.some("/home/a b/c%d"));
    }
}
