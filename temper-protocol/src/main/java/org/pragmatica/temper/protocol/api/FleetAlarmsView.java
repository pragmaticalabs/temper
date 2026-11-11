package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;


/// `GET /api/v1/fleet/alarms` answer. Hosts in the `fleet/tickets` order with its `status`
/// rule (`ok`, `UNREACHABLE`, `NOT-COLLECTED`, `RETIRED` past its `retireAt`). `takenAt` and `hostAt` are the latest snapshot's controller and host
/// instants, `skewS` its measured skew, `retireAt` the configured retirement. `alarms` are the CURRENT alarms only: a
/// fired alarm whose condition no longer holds on the latest snapshot is not listed; a ticket is listed once, at its
/// highest fired threshold. An `UNREACHABLE` host keeps its last rows, which the reader dates by `takenAt`.
public record FleetAlarmsView(int v, Instant asOf, List<HostAlarms> hosts) {
    public static final int VERSION = 1;

    public FleetAlarmsView {
        hosts = List.copyOf(hosts);
    }

    public static FleetAlarmsView fleetAlarmsView(Instant asOf, List<HostAlarms> hosts) {
        return new FleetAlarmsView(VERSION, asOf, hosts);
    }

    /// One expected host and its current alarms.
    public record HostAlarms(String host,
                             String status,
                             Option<Instant> takenAt,
                             Option<Instant> hostAt,
                             Option<Integer> skewS,
                             Option<Instant> retireAt,
                             List<Alarm> alarms) {
        public HostAlarms {
            alarms = List.copyOf(alarms);
        }

        public static HostAlarms hostAlarms(String host,
                                            String status,
                                            Option<Instant> takenAt,
                                            Option<Instant> hostAt,
                                            Option<Integer> skewS,
                                            Option<Instant> retireAt,
                                            List<Alarm> alarms) {
            return new HostAlarms(host, status, takenAt, hostAt, skewS, retireAt, alarms);
        }
    }

    /// One current alarm. `ticket`, `ageMin` and `limitMin` belong to the per-ticket kinds (`queue-wait`,
    /// `portgroup-held`, `portgroup-long`; `waiter-gone` has a ticket only), `days` to `host-retiring`, `skewS` to `clock-skew`.
    /// `ageMin` is as of the host's `hostAt`: the ticket's wait or run time, but for `portgroup-held` its stall (time since
    /// its last progress); `firedAt` is on the controller clock.
    public record Alarm(String kind,
                        Option<String> ticket,
                        Option<Integer> ageMin,
                        Option<Integer> limitMin,
                        Option<Integer> days,
                        Option<Integer> skewS,
                        Instant firedAt) {
        public static Alarm alarm(String kind,
                                  Option<String> ticket,
                                  Option<Integer> ageMin,
                                  Option<Integer> limitMin,
                                  Option<Integer> days,
                                  Option<Integer> skewS,
                                  Instant firedAt) {
            return new Alarm(kind, ticket, ageMin, limitMin, days, skewS, firedAt);
        }
    }
}
