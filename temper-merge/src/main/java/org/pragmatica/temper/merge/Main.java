package org.pragmatica.temper.merge;

import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Contract;
import org.pragmatica.temper.merge.cli.Commands;
import org.pragmatica.temper.merge.cli.RunnerConfig;
import org.pragmatica.temper.merge.cli.Service;
import org.pragmatica.temper.merge.engine.Engine;
import org.pragmatica.temper.merge.engine.GitHubReader;
import org.pragmatica.temper.merge.engine.QueueWriter;
import org.pragmatica.temper.merge.store.EventsLog;
import org.pragmatica.temper.merge.store.FileLedger;
import org.pragmatica.temper.merge.store.JobFiles;


/// `temper-merge submit <pr> <sha> [--ready]` | `status <id>` | `serve`.
///
/// `serve` is the runner: a round ([Service#round]) every `poll` seconds, forever. This build wires
/// [GitHubReader#unconfigured] and [QueueWriter#pending]: no GitHub identity is loaded and no mutation can be sent
/// until the owner decides the write identity (#101), so a submitted job reports the missing read identity as its
/// observation and ends as `TIMEOUT` in `SUBMITTED`.
public sealed interface Main {
    /// The JDK's entry-point signature; the exit code is the whole contract of the process.
    @Contract
    static void main(String[] argv) {
        var args = Arrays.asList(argv);
        var code = RunnerConfig.load(System.getenv()).fold(cause -> printed(new Commands.Outcome(Commands.USAGE,
                                                                                                 List.of("temper-merge: " + cause.message()))),
                                                           config -> run(config, args));

        System.exit(code);
    }

    private static int run(RunnerConfig config, List<String> args) {
        var commands = Commands.commands(config, InstantSource.system(), new SecureRandom());
        var rest = args.isEmpty()
                   ? List.<String> of()
                   : args.subList(1, args.size());

        return switch (args.isEmpty()
                       ? ""
                       : args.getFirst()) {
            case "submit" -> printed(commands.submit(rest));
            case "status" -> printed(commands.status(rest));
            case "serve" -> serve(config);
            default -> printed(new Commands.Outcome(Commands.USAGE,
                                                    List.of("usage: temper-merge submit <pr> <sha> [--ready] | status <id> | serve")));
        };
    }

    private static int printed(Commands.Outcome outcome) {
        var out = outcome.code() == Commands.OK
                  ? System.out
                  : System.err;

        outcome.lines().forEach(out::println);

        return outcome.code();
    }

    private static int serve(RunnerConfig config) {
        var clock = InstantSource.system();
        var jobs = JobFiles.jobFiles(config.jobs());
        var ledger = FileLedger.fileLedger(jobs,
                                           EventsLog.eventsLog(config.eventsLog()),
                                           clock);
        var engine = Engine.engine(GitHubReader.unconfigured(), QueueWriter.pending(), ledger, config.policy(), clock);
        var service = Service.service(jobs, engine);

        System.out.println("temper-merge: serving " + config.repo()
                          + " from " + config.jobs()
                          + ", events " + config.eventsLog()
                          + ", poll " + config.policy().poll().toSeconds()
                          + "s");
        while (!Thread.currentThread().isInterrupted()) {
            service.round()
                   .await()
                   .onFailure(cause -> System.err.println("temper-merge: round failed: " + cause.message()));
            pause(config.policy().poll().toMillis());
        }

        return Commands.OK;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    record unused() implements Main {}
}
