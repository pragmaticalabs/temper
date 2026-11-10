package org.pragmatica.temper.worker.facts;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// The bb-* ticket counts of the contention facts (spec §4.5, m0-design §12.7), read from `~/tools/bb-queue 0`
/// output. Q-10: reading is not touching; the worker only runs the listing, which takes no queue lock (bb-queue's
/// own `mkdir -p` of the existing queue directories is the tool's behaviour, not a temper write).
///
/// The listing (bb-queue, 2026-09-30) has three sections, each a header line, a column-header line, rows, and a
/// blank line:
///
/// ```
/// RUNNING (reactors on host: 2; cap 3 unless a ticket overrides; port group held by: <ticket or empty>)
/// TICKET                       OWNER    PRIO AGE           CHECKOUT ARGS
/// <one row per running ticket, `[port-group]`/`[waiter gone]` markers after its args>
///   + 1 reactor(s) not started through the queue (direct mvn or BB_QUEUE=0)      (optional, not a ticket)
///
/// WAITING (grant order: ...)
/// POS  TICKET                       OWNER    PRIO WAITED        CHECKOUT ARGS
/// <one row per waiting ticket, starting with its position>
///
/// FINISHED (most recent 0)
/// ```
///
/// The counts are a snapshot of a lock-free listing: a ticket that moves between directories mid-listing can be
/// missed once. Anything that is not this shape is unparsable: the counts are `none()` and a note says why; the probe
/// never fails because of bb-queue.
public sealed interface BbQueue {
    String RUNNING_HEADER = "RUNNING (";
    String HELD_BY = "port group held by:";
    String RUNNING_COLUMNS = "TICKET ";
    String UNTRACKED_PREFIX = "  + ";
    String WAITING_HEADER = "WAITING (";
    String WAITING_COLUMNS = "POS ";
    Pattern RUNNING_ROW = Pattern.compile("^\\S+\\s+\\S.*$");
    Pattern WAITING_ROW = Pattern.compile("^[0-9]+\\s+\\S+\\s+\\S.*$");
    String NOTE_PREFIX = "bb-queue: ";

    /// Ticket counts as listed.
    record Tickets(int running, int waiting) {
        static Tickets tickets(int running, int waiting) {
            return new Tickets(running, waiting);
        }
    }

    /// The facts contribution: both counts, or neither and a note.
    record BbFacts(Option<Integer> running, Option<Integer> waiting, List<String> notes) {
        public BbFacts {
            notes = List.copyOf(notes);
        }

        static BbFacts bbFacts(Option<Integer> running, Option<Integer> waiting, List<String> notes) {
            return new BbFacts(running, waiting, notes);
        }
    }

    /// Runs the listing and parses it.
    ///
    /// FER (degrade forward): a missing tool, a timeout, a non-zero exit or an unparsable listing becomes `none()`
    /// counts plus a note. Guarantee: the facts never claim a count they could not read, and a bb-queue problem never
    /// fails a heartbeat, a unit start or a unit end. Mechanism: the cause's message is kept as the note.
    static Promise<BbFacts> read(ProcessRunner runner, Command command) {
        return runner.run(command)
                     .flatMap(completed -> tickets(completed).async())
                     .map(BbQueue::counted)
                     .recover(BbQueue::unread);
    }

    private static BbFacts counted(Tickets tickets) {
        return BbFacts.bbFacts(some(tickets.running()), some(tickets.waiting()), List.of());
    }

    private static BbFacts unread(Cause cause) {
        return BbFacts.bbFacts(none(),
                               none(),
                               List.of(NOTE_PREFIX + cause.message()));
    }

    private static Result<Tickets> tickets(Completed completed) {
        return completed.requireZero()
                        .filter(cause("output truncated"),
                                done -> !done.truncated())
                        .map(Completed::stdoutText)
                        .flatMap(BbQueue::listedTickets);
    }

    /// The running and waiting ticket counts of a listing.
    static Result<Tickets> listedTickets(String output) {
        var lines = output.lines().toList();

        return Result.all(running(lines), waiting(lines)).map(Tickets::tickets);
    }

    private static Result<Integer> running(List<String> lines) {
        return section(lines,
                       line -> line.startsWith(RUNNING_HEADER) && line.contains(HELD_BY),
                       RUNNING_COLUMNS,
                       "RUNNING").flatMap(rows -> rowCount(tracked(rows), RUNNING_ROW, "RUNNING"));
    }

    /// Running rows without the "+ N reactor(s) not started through the queue" line, which is not a ticket.
    private static List<String> tracked(List<String> rows) {
        return rows.stream()
                   .filter(row -> !row.startsWith(UNTRACKED_PREFIX))
                   .toList();
    }

    private static Result<Integer> waiting(List<String> lines) {
        return section(lines, line -> line.startsWith(WAITING_HEADER), WAITING_COLUMNS, "WAITING").flatMap(rows -> rowCount(rows,
                                                                                                                            WAITING_ROW,
                                                                                                                            "WAITING"));
    }

    /// The rows between a section's column-header line and the next blank line (or the end).
    private static Result<List<String>> section(List<String> lines,
                                                Predicate<String> header,
                                                String columns,
                                                String name) {
        var start = indexOf(lines, header);

        return start.filter(index -> hasColumns(lines, index, columns))
                    .map(index -> rows(lines, index + 2))
                    .toResult(cause("no " + name + " section"));
    }

    private static Option<Integer> indexOf(List<String> lines, Predicate<String> header) {
        return Option.from(IntStream.range(0,
                                           lines.size())
                                    .filter(index -> header.test(lines.get(index)))
                                    .boxed()
                                    .findFirst());
    }

    private static boolean hasColumns(List<String> lines, int header, String columns) {
        return header + 1 < lines.size() && lines.get(header + 1)
                                                 .startsWith(columns);
    }

    private static List<String> rows(List<String> lines, int from) {
        return lines.subList(from,
                             lines.size())
                    .stream()
                    .takeWhile(line -> !line.isBlank())
                    .toList();
    }

    private static Result<Integer> rowCount(List<String> rows, Pattern shape, String name) {
        return rows.stream()
                   .allMatch(row -> shape.matcher(row)
                                         .matches())
               ? success(rows.size())
               : cause("unparsable " + name + " row").result();
    }

    record unused() implements BbQueue {}
}
