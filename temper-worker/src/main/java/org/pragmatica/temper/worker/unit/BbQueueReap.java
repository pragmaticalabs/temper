package org.pragmatica.temper.worker.unit;

import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// `~/tools/bb-queue reap` after a holder death (maintainer ruling "A11 ticket release", m1-design §14). bb-queue's `reap()`
/// runs only inside a queue client (it has no timer), so on an idle host a `kill -9`'d holder's ticket stays RUNNING,
/// holding the port group in every listing, until the next client. `bb-queue reap` takes the queue lock, runs that
/// `reap()`, prints `reaped <id>: <state>` per ticket it moves, or `reap: nothing to move`, and exits 0; each line is
/// logged.
///
/// An older bb-queue has no `reap` subcommand, and its answer is not a usage error: the 2026-09-30 version reads
/// `reap` as the listing's `N`, prints the full listing and exits 0. So the output itself is checked against the
/// contract above (at least one line, every line one of the two shapes); anything else, or a non-zero exit, is logged
/// as a warning that names the older bb-queue.
///
/// The command is the `bb-queue 0` listing's ([org.pragmatica.temper.worker.config.WorkerConfig#bbQueue]) with `reap`
/// as its argument: the same executable, environment (`PATH`, `HOME`, `USER`), timeout and output cap.
///
/// Called after the worker itself decides a unit's end on a dead holder: [HoldMonitor]'s `VOID(port-group-lost)`
/// (after the kill) and [GrantWatcher]'s dead holder before the container ran. The startup scan only reports a dead
/// holder (the controller decides, reconcile rules 4-5) and must not wait on the queue lock before `Hello`, so it
/// does not call it: such a ticket is released at the next queue client, as before this ruling.
public record BbQueueReap(ProcessRunner runner, Command listing) {
    private static final Logger LOG = LoggerFactory.getLogger(BbQueueReap.class);
    public static final String REAP = "reap";
    private static final Pattern CONTRACT = Pattern.compile("reaped \\S+: .*|reap: nothing to move");

    public static BbQueueReap bbQueueReap(ProcessRunner runner, Command listing) {
        return new BbQueueReap(runner, listing);
    }

    /// FER (degrade forward): a failure (a missing tool, a timeout) is logged as a warning and absorbed, and so is an
    /// older bb-queue without the subcommand (its output is not the contract, or its exit is non-zero). Guarantee: the
    /// unit's verdict and its collection never depend on the reap; on such a host the ticket is released at the next
    /// queue client, the behaviour before the ruling. Mechanism: `recover` to `unit()` after the warning, which
    /// `withFailure` logs before the returned promise resolves; the older bb-queue is a success that [#checked] warns
    /// about.
    public Promise<Unit> reaped(UnitRef ref) {
        return ran(ref).withFailure(cause -> LOG.warn("unit {}: bb-queue reap failed, the ticket is released at the next queue client: {}",
                                                      ref.containerName(),
                                                      cause))
                  .recover(_ -> unit());
    }

    private Promise<Unit> ran(UnitRef ref) {
        return command().async()
                      .flatMap(runner::run)
                      .map(completed -> logged(ref, completed))
                      .map(completed -> checked(ref, completed));
    }

    private Result<Command> command() {
        return Command.command(List.of(listing.executable(),
                                       REAP),
                               listing.env(),
                               listing.timeout())
                      .map(command -> command.withOutputLimit(listing.outputLimit()));
    }

    private static Completed logged(UnitRef ref, Completed completed) {
        completed.stdoutText()
                 .lines()
                 .forEach(line -> LOG.info("unit {}: bb-queue reap: {}",
                                           ref.containerName(),
                                           line));

        return completed;
    }

    private static Unit checked(UnitRef ref, Completed completed) {
        return recognised(completed)
               ? unit()
               : olderBbQueue(ref, completed);
    }

    private static boolean recognised(Completed completed) {
        var lines = completed.stdoutText().lines().toList();

        return completed.rc() == 0
               && !lines.isEmpty()
               && lines.stream()
                       .allMatch(CONTRACT.asMatchPredicate());
    }

    private static Unit olderBbQueue(UnitRef ref, Completed completed) {
        LOG.warn("unit {}: bb-queue has no reap subcommand (older bb-queue); ticket released at the next queue client (rc {})",
                 ref.containerName(),
                 completed.rc());

        return unit();
    }
}
