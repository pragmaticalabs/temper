package org.pragmatica.temper.cli.alarm;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.cli.config.AlarmConfig;
import org.pragmatica.temper.protocol.api.FleetAlarmsView;
import org.pragmatica.temper.protocol.api.FleetAlarmsView.Alarm;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.MaintenanceRunDto;


/// Each condition's decision from what the check read; pure. `controller-down` is decided by [AlarmCheck], from
/// whether the controller answered at all.
public sealed interface Conditions {
    /// The age backstop of a running group=1 ticket: listed, but never the reason `host-alarms` fires.
    String PORTGROUP_LONG = "portgroup-long";
    /// The `GET /hosts` state of a link that grants work.
    String UP = "UP";

    /// `dump-failed`: the latest dump run failed. No run at all is clear here; `dump-stale` reports it.
    static Condition dumpFailed(Option<MaintenanceRunDto> latest) {
        return latest.filter(run -> !run.ok())
                     .map(Conditions::failedDump)
                     .or(() -> Condition.clear(AlarmCode.DUMP_FAILED));
    }

    /// `dump-stale`: no good dump ended within `staleAfter` before `now`; never a good dump is stale too.
    static Condition dumpStale(Option<Instant> lastGoodEnded, Instant now, Duration staleAfter) {
        var cutoff = now.minus(staleAfter);

        return lastGoodEnded.filter(ended -> !ended.isBefore(cutoff))
                            .map(_ -> Condition.clear(AlarmCode.DUMP_STALE))
                            .or(() -> Condition.firing(AlarmCode.DUMP_STALE,
                                                       staleDetail(lastGoodEnded, staleAfter)));
    }

    /// `disk-low`: a measured path has fewer than `minFreeBytes` usable bytes.
    static Condition diskLow(List<FreeSpace> measured, long minFreeBytes) {
        var low = measured.stream()
                          .filter(space -> space.usableBytes() < minFreeBytes)
                          .map(FreeSpace::described)
                          .toList();

        return low.isEmpty()
               ? Condition.clear(AlarmCode.DISK_LOW)
               : Condition.firing(AlarmCode.DISK_LOW,
                                  String.join(", ", low) + " (threshold " + minFreeBytes / AlarmConfig.GIB + " GiB)");
    }

    /// `worker-lost`: a configured worker host whose link is not UP (a LOST host's `state` is `LOST`, m4-design §11).
    /// `GET /hosts` carries no time of the link's last change, so there is no grace period: a link that is reconnecting
    /// (CONNECTING) counts as lost at this check, and clears at the first check that finds it UP again. A retired host
    /// with no active unit is left out (m4-design §10, ruling Q-M4-10): it takes no new work, so its deletion is not an
    /// alarm; one still running units is not, since those units are what a loss would cost.
    static Condition workerLost(List<HostView> hosts) {
        var lost = hosts.stream()
                        .filter(host -> !UP.equals(host.state()))
                        .filter(host -> !drained(host))
                        .map(host -> host.name() + " " + host.state())
                        .toList();

        return lost.isEmpty()
               ? Condition.clear(AlarmCode.WORKER_LOST)
               : Condition.firing(AlarmCode.WORKER_LOST, "link not up: " + String.join(", ", lost));
    }

    /// `host-alarms`: any host lists a current alarm. The detail is one line per alarm,
    /// hosts and alarms in reply order, then `route` as a link. Clear only when every host's list is empty, `portgroup-long`
    /// (the lower-severity age backstop) aside: it alone does not fire, but it is listed with the alarms that do.
    static Condition hostAlarms(FleetAlarmsView view, String route) {
        var alarms = view.hosts()
                         .stream()
                         .flatMap(host -> host.alarms()
                                              .stream()
                                              .map(alarm -> Map.entry(host.host(),
                                                                      alarm)))
                         .toList();
        var fires = alarms.stream().anyMatch(entry -> !PORTGROUP_LONG.equals(entry.getValue().kind()));
        var lines = fires
                    ? alarms.stream().map(entry -> alarmLine(entry.getKey(), entry.getValue())).toList()
                    : List.<String> of();

        return lines.isEmpty()
               ? Condition.clear(AlarmCode.HOST_ALARMS)
               : Condition.firing(AlarmCode.HOST_ALARMS, String.join("\n", lines) + "\n\n" + route);
    }

    /// `<host> <kind> <ticket|-> age=<n>m limit=<n>m`; `days=<d>` for host-retiring and `skew=<s>s` for clock-skew
    /// stand where the alarm has them (each figure is printed when the alarm carries it).
    private static String alarmLine(String host, Alarm alarm) {
        var figures = Stream.of(alarm.ageMin().map(age -> "age=" + age + "m"),
                                alarm.limitMin().map(limit -> "limit=" + limit + "m"),
                                alarm.days().map(days -> "days=" + days),
                                alarm.skewS().map(skew -> "skew=" + skew + "s"))
                            .flatMap(Option::stream);

        return Stream.concat(Stream.of(host,
                                       alarm.kind(),
                                       alarm.ticket().or("-")),
                             figures)
                     .collect(Collectors.joining(" "));
    }

    /// Retired (`now ≥ retireAt`, judged by the controller) with nothing active on it.
    private static boolean drained(HostView host) {
        return host.retired() && host.active() == 0;
    }

    /// `dump-file-stale`: the newest verified dump file in `dir` is older than `staleAfter`, or there is none. The
    /// controller publishes only verified dumps there, so a stopped dump and a failing one both show as staleness.
    static Condition dumpFileStale(Option<Instant> newest, Instant now, Duration staleAfter, Path dir) {
        var cutoff = now.minus(staleAfter);

        return newest.filter(modified -> !modified.isBefore(cutoff))
                     .map(_ -> Condition.clear(AlarmCode.DUMP_FILE_STALE))
                     .or(() -> Condition.firing(AlarmCode.DUMP_FILE_STALE,
                                                dumpFileDetail(newest, staleAfter, dir)));
    }

    private static String dumpFileDetail(Option<Instant> newest, Duration staleAfter, Path dir) {
        return newest.map(modified -> "the newest temper-*.dump in " + dir
                                     + " is from " + modified
                                     + ", more than " + staleAfter
                                     + " ago")
                     .or("no temper-*.dump in " + dir);
    }

    private static Condition failedDump(MaintenanceRunDto run) {
        return Condition.firing(AlarmCode.DUMP_FAILED,
                                "pg_dump that ended " + run.endedAt()
                               + " failed: " + run.detail()
                                                  .or("no detail recorded"));
    }

    private static String staleDetail(Option<Instant> lastGoodEnded, Duration staleAfter) {
        return lastGoodEnded.map(ended -> "the latest good pg_dump ended " + ended
                                         + ", more than " + staleAfter
                                         + " ago")
                            .or("no good pg_dump recorded");
    }

    record unused() implements Conditions {}
}
