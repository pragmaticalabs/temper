package org.pragmatica.temper.cli.command;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.temper.domain.task.ReadPointStamp;
import org.pragmatica.temper.protocol.api.CheckpointDto;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.LeaseCall;
import org.pragmatica.temper.protocol.api.ObservedDto;
import org.pragmatica.temper.protocol.api.TreeReportDto;
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
                                                            .or("") + lane(view);
    }

    /// The lane registry's columns, after the task's own: ` hb-age=45s`, ` STALE`, the tree report as the agent
    /// SELF-REPORTED it (` head=… dirty=N unpushed=N`, each count marked `(prev)` when the report came from an earlier
    /// lease epoch than the task's current one: a successor's claim keeps the predecessor's report until its own first
    /// `hb`), and what the parent measured (` ctxK=… turns=…`).
    private static String lane(TaskView view) {
        return view.hbAge()
                   .map(age -> " hb-age=" + age + "s")
                   .or("") + (view.stale()
                              ? " STALE"
                              : "") + view.report()
                                          .map(report -> report(report,
                                                                view.epoch()))
                                          .or("") + view.observed()
                                                        .map(TaskLines::observed)
                                                        .or("");
    }

    private static String report(TreeReportDto report, long epoch) {
        var prev = report.epoch() == epoch
                   ? ""
                   : "(prev)";

        return " head=" + shortSha(report.head())
             + " dirty=" + report.dirty() + prev
             + " unpushed=" + report.unpushed() + prev;
    }

    private static String observed(ObservedDto observed) {
        return " ctxK=" + observed.ctxK() + " turns=" + observed.turns();
    }

    /// `task 42 HEARTBEAT epoch=7 head=0cf5631 dirty=2 unpushed=0`: what the CLI measured and sent.
    static String heartbeat(long task, long epoch, LeaseCall.Tree tree) {
        return leaseVerb(task, "HEARTBEAT", epoch)
             + " head=" + shortSha(tree.head())
             + " dirty=" + tree.dirty()
             + " unpushed=" + tree.unpushed();
    }

    static String checkpointed(long task, CheckpointDto stored) {
        return leaseVerb(task, "CHECKPOINT", stored.epoch())
             + " seq=" + stored.seq()
             + " pushedSha=" + shortSha(stored.pushedSha());
    }

    static String observed(long task, int ctxK, int turns) {
        return "task " + task + " OBSERVED ctxK=" + ctxK + " turns=" + turns;
    }

    /// What a claim adds after its line, so a successor's first call yields its state: one `WARNING` line per warning,
    /// then the latest checkpoint (`checkpoint …`, one `checkpoint item …` per checklist entry, one `checkpoint read …`).
    static List<String> claimContext(ClaimResponse claim) {
        return Stream.concat(claim.warnings().stream().map(TaskLines::warning),
                             Stream.ofNullable(claim.checkpoint()).flatMap(TaskLines::checkpoint))
                     .toList();
    }

    private static String warning(String code) {
        return "WARNING " + code + (code.equals("dirty-predecessor")
                                    ? ": the previous holder's last tree report was dirty or had unpushed commits; inspect the tree and the checkpoint before writing"
                                    : "");
    }

    private static Stream<String> checkpoint(CheckpointDto checkpoint) {
        return Stream.of(Stream.of("checkpoint seq=" + checkpoint.seq()
                                  + " epoch=" + checkpoint.epoch()
                                  + " at=" + checkpoint.at()
                                  + " pushedSha=" + checkpoint.pushedSha()
                                  + " note=" + Lines.quoted(checkpoint.note())),
                         checkpoint.checklist()
                                   .stream()
                                   .map(item -> "checkpoint item " + (item.done()
                                                                      ? "done "
                                                                      : "todo ") + Lines.quoted(item.item())),
                         checkpoint.readMap()
                                   .stream()
                                   .map(entry -> "checkpoint read " + Lines.quoted(entry.path())
                                                + " lines=" + Lines.quoted(entry.lines())))
                     .flatMap(lines -> lines);
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
