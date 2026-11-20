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
    /// its last progress); `firedAt` is on the controller clock. For the off-queue kinds (`off-queue-reactor`, `lingering-reactor`) `ticket`
    /// reads `pid=<n> owner=<o> cwd=<path>` (owner: the checkout prefix under ~/work/co, else `unowned`; owner and path with `%` written
    /// `%25` and a space `%20`, nothing else encoded, the path last; `reactorOwner` and `reactorCwd` read them back), `ageMin` is the launcher's age and `limitMin` the lingering limit; no field was added, so a CLI that predates
    /// the kinds still decodes the reply.
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

        public static Alarm reactorAlarm(String kind,
                                         Option<Integer> ageMin,
                                         Option<Integer> limitMin,
                                         Instant firedAt,
                                         long pid,
                                         String owner,
                                         String cwd) {
            return new Alarm(kind,
                             Option.some("pid=" + pid + " owner=" + encodeField(owner) + " cwd=" + encodeField(cwd)),
                             ageMin,
                             limitMin,
                             Option.none(),
                             Option.none(),
                             firedAt);
        }

        /// `%` becomes `%25` and a space `%20`; nothing else is touched. Applied to owner and cwd in a reactor ticket, so the
        /// ticket stays unambiguous: owner holds no space and the cwd is last.
        public static String encodeField(String cwd) {
            return cwd.replace("%", "%25")
                      .replace(" ", "%20");
        }

        /// The inverse of `encodeField`, in one pass (a literal `%20` in the path was written `%2520` and comes back as `%20`).
        public static String decodeField(String encoded) {
            var out = new StringBuilder(encoded.length());

            for (int i = 0; i < encoded.length(); i++) {
                if (encoded.startsWith("%25", i)) {
                    out.append('%');
                    i += 2;
                } else if (encoded.startsWith("%20", i)) {
                    out.append(' ');
                    i += 2;
                } else {
                    out.append(encoded.charAt(i));
                }
            }

            return out.toString();
        }

        /// The original owner out of a reactor alarm's ticket string: what sits between `owner=` and ` cwd=`, decoded.
        public static Option<String> reactorOwner(String ticket) {
            var from = ticket.indexOf(" owner=");
            var to = ticket.indexOf(" cwd=");

            return from < 0 || to < from
                   ? Option.none()
                   : Option.some(decodeField(ticket.substring(from + 7, to)));
        }

        /// The original cwd out of a reactor alarm's ticket string `pid=<n> owner=<o> cwd=<encoded>`: everything after the
        /// first ` cwd=`, decoded.
        public static Option<String> reactorCwd(String ticket) {
            var at = ticket.indexOf(" cwd=");

            return at < 0
                   ? Option.none()
                   : Option.some(decodeField(ticket.substring(at + 5)));
        }
    }
}
