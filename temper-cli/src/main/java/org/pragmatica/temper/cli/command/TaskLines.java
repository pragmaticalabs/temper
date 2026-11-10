package org.pragmatica.temper.cli.command;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.pragmatica.temper.domain.task.ReadPointStamp;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.TaskEventDto;
import org.pragmatica.temper.protocol.api.TaskView;


/// The `task` group's one-line forms (m3-design §11.2, spec §8.1's shape): `task 42 CODE LEASED epoch=7
/// readPoint=0cf5631 deadline=…` for a claim, `task <id> <STAGE> <STATUS> …` for a task, one `event …` line per event,
/// `stamp …` for a checkout's read-point stamp.
sealed interface TaskLines {
    static String task(TaskView view) {
        return "task " + view.id()
             + " " + view.stage()
             + " " + view.status()
             + " slug=" + view.slug()
             + " round=" + view.round()
             + "/" + view.roundCap()
             + " visit=" + view.visit()
             + " epoch=" + view.epoch() + view.raisedFrom()
                                              .map(from -> " raisedFrom=" + from)
                                              .or("") + view.leaseHolder()
                                                            .map(holder -> " holder=" + holder)
                                                            .or("");
    }

    static String claimed(ClaimResponse claim) {
        return "task " + claim.task()
             + " " + claim.stage()
             + " LEASED epoch=" + claim.epoch()
             + " readPoint=" + shortSha(claim.readPoint())
             + " deadline=" + claim.deadline();
    }

    static String stamp(ReadPointStamp stamp, Path dir) {
        return "stamp " + stamp.task() + " epoch=" + stamp.epoch() + " readPoint=" + stamp.readPoint() + " dir=" + dir;
    }

    static String event(TaskEventDto event) {
        return "event " + event.seq()
             + " " + event.at()
             + " " + event.kind()
             + " " + event.stage()
             + " " + event.status()
             + " epoch=" + event.epoch()
             + " actor=" + event.actor() + facts(event.facts());
    }

    static String leaseVerb(long task, String verb, long epoch) {
        return "task " + task + " " + verb + " epoch=" + epoch;
    }

    private static String facts(Map<String, String> facts) {
        return new TreeMap<>(facts).entrySet()
                                   .stream()
                                   .map(fact -> " " + fact.getKey() + "=" + fact.getValue())
                                   .collect(Collectors.joining());
    }

    private static String shortSha(String sha) {
        return sha.length() >= 7
               ? sha.substring(0, 7)
               : sha;
    }

    record unused() implements TaskLines {}
}
