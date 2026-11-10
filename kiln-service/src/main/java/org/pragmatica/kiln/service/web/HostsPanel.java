package org.pragmatica.kiln.service.web;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.temper.HostsRead;


/// The Hosts section of the temper tab: one row per host with temper's status, `retireAt`
/// (with "in N d"), `skewS` and each current alarm as `<kind> <ticket> <ageMin>m` (or `<days>d`, `<skewS>s`). It is "as
/// of" the last reply that decoded. While the `hosts` source is not OK or is stale, the last rows stay, marked stale with
/// their age; kiln flags nothing and emits no event, because the controller is the only emitter.
final class HostsPanel {
    private HostsPanel() {}

    static String render(DashboardView view, Instant now) {
        var source = view.sources();
        var degraded = source.stale(Source.HOSTS, now) || source.of(Source.HOSTS)
                                                                .map(status -> !(status.state() instanceof SourceState.Ok))
                                                                .or(false);

        return "<div id=\"hosts\" class=\"hosts" + (degraded
                                                    ? " stale"
                                                    : "")
             + "\">\n<h3>Hosts" + Html.flag(degraded)
             + "</h3>\n" + Html.asOf(view.fleet().map(Observed::observedAt),
                                     now) + state(view, now) + view.fleet()
                                                                   .map(reply -> rows(reply, degraded, now))
                                                                   .or("<p>no fleet alarms read yet</p>")
             + "\n</div>";
    }

    private static String state(DashboardView view, Instant now) {
        return view.sources()
                   .of(Source.HOSTS)
                   .filter(status -> !(status.state() instanceof SourceState.Ok))
                   .map(status -> "<p class=\"stale\">" + Html.state(status.state(),
                                                                     now) + "</p>\n")
                   .or("");
    }

    private static String rows(Observed<HostsRead.Answered> reply, boolean degraded, Instant now) {
        var age = degraded
                  ? "<p class=\"stale\">rows observed " + Html.age(reply.observedAt(), now) + "</p>\n"
                  : "";

        return reply.value()
                    .hosts()
                    .isEmpty()
               ? age + "<p>no hosts listed</p>"
               : age
                + "<ul>\n" + reply.value()
                                  .hosts()
                                  .stream()
                                  .map(host -> row(host, now))
                                  .collect(Collectors.joining("\n"))
                + "\n</ul>";
    }

    private static String row(HostsRead.HostRow host, Instant now) {
        return "<li><b>" + Html.text(host.host())
             + "</b> " + Html.text(host.status()) + host.retireAt()
                                                        .map(at -> "; retire " + retire(at, now))
                                                        .or("") + host.skewS()
                                                                      .map(skew -> "; skew " + skew + "s")
                                                                      .or("")
             + "; " + alarms(host)
             + "</li>";
    }

    private static String alarms(HostsRead.HostRow host) {
        return host.alarms()
                   .isEmpty()
               ? "no alarms"
               : host.alarms()
                     .stream()
                     .map(HostsPanel::alarm)
                     .collect(Collectors.joining(", "));
    }

    private static String alarm(HostsRead.AlarmRow alarm) {
        return "<span class=\"alarm\">" + Html.text(alarm.kind()) + alarm.ticket()
                                                                         .map(ticket -> " " + Html.text(ticket))
                                                                         .or("") + alarm.ageMin()
                                                                                        .map(minutes -> " " + minutes
                                                                                                       + "m")
                                                                                        .or("") + alarm.days()
                                                                                                       .map(days -> " " + days
                                                                                                                   + "d")
                                                                                                       .or("") + alarm.skewS()
                                                                                                                      .map(skew -> " " + skew
                                                                                                                                  + "s")
                                                                                                                      .or("")
             + "</span>";
    }

    /// `2026-12-01T00:00:00Z (in 3 d)`; a time already passed reads `(passed)`.
    private static String retire(Instant at, Instant now) {
        var days = Duration.between(now, at).toDays();

        return at.truncatedTo(ChronoUnit.SECONDS) + (at.isAfter(now)
                                                     ? " (in " + days + " d)"
                                                     : " (passed)");
    }
}
