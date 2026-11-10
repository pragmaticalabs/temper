package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.MacProcess;
import org.pragmatica.temper.store.api.MacSession;

import static org.pragmatica.lang.Option.some;


/// Owner liveness of a running or waiting ticket, computed at read time: exactly five values.
/// The rules apply in order and the first that matches wins: no session triple and no `detached_reader` is `UNKNOWN`;
/// a stale `mac` source is `STALE-SOURCE` (so a silent Mac job never turns into DEAD); a `detached_reader` is judged by
/// its label in the latest post; otherwise the session is `SESSION-ALIVE` only when the latest post lists exactly its
/// (pid, start), else `DEAD`. It is the session's liveness, not the agent's.
public sealed interface Liveness {
    String UNKNOWN = "UNKNOWN(no session field)";
    String ALIVE = "SESSION-ALIVE";
    Pattern PID = Pattern.compile("^[1-9][0-9]{0,9}$");
    Pattern START = Pattern.compile("^[1-9][0-9]{8,10}$");

    /// What the Mac side says: `fresh` = the `mac` source is not stale, `lastOkAt` its last success, `latest` the entries
    /// of the latest post, `sessions` every (pid, start) any post has listed.
    record MacState(boolean fresh, Option<Instant> lastOkAt, List<MacProcess> latest, List<MacSession> sessions) {}

    /// A ticket's session, all three fields valid.
    record Session(int pid, long start, String label) {}

    static String liveness(BbTicket ticket, MacState mac) {
        var session = session(ticket);
        var reader = ticket.field("detached_reader").filter(label -> MacRoutes.LABEL.matcher(label).matches());

        return session.isEmpty() && reader.isEmpty()
               ? UNKNOWN
               : mac.fresh()
                 ? reader.map(label -> detached(label, mac))
                         .or(() -> session.map(found -> session(found, mac))
                                          .or(UNKNOWN))
                 : "STALE-SOURCE(mac job silent since " + iso(mac.lastOkAt()) + ")";
    }

    private static String detached(String label, MacState mac) {
        return "DETACHED(reader=" + label + (mac.latest()
                                                .stream()
                                                .anyMatch(process -> process.label()
                                                                            .equals(label))
                                             ? " ALIVE)"
                                             : " DEAD)");
    }

    private static String session(Session session, MacState mac) {
        return mac.latest()
                  .stream()
                  .anyMatch(process -> process.pid() == session.pid() && process.start() == session.start())
               ? ALIVE
               : "DEAD(last seen " + iso(lastSeen(session, mac)) + ")";
    }

    private static Option<Instant> lastSeen(Session session, MacState mac) {
        return mac.sessions()
                  .stream()
                  .filter(row -> row.pid() == session.pid() && row.start() == session.start())
                  .findFirst()
                  .map(row -> some(row.lastSeen()))
                  .orElseGet(Option::none);
    }

    /// The triple is all-or-nothing: any field missing or malformed and there is no session.
    static Option<Session> session(BbTicket ticket) {
        return ticket.field("session_pid")
                     .filter(text -> PID.matcher(text).matches())
                     .map(Long::parseLong)
                     .filter(pid -> pid <= Integer.MAX_VALUE)
                     .flatMap(pid -> ticket.field("session_start")
                                           .filter(text -> START.matcher(text).matches())
                                           .map(Long::parseLong)
                                           .flatMap(start -> ticket.field("session_label")
                                                                   .filter(label -> MacRoutes.LABEL.matcher(label).matches())
                                                                   .map(label -> new Session(pid.intValue(),
                                                                                             start,
                                                                                             label))));
    }

    /// An instant to the second, or `never`.
    private static String iso(Option<Instant> at) {
        return at.map(instant -> instant.truncatedTo(ChronoUnit.SECONDS)
                                        .toString())
                 .or("never");
    }

    record unused() implements Liveness {}
}
