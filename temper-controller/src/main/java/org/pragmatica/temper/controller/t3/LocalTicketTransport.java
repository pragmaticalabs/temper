package org.pragmatica.temper.controller.t3;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// A [TicketTransport] that runs `ops/bb/bb-tickets` on this machine over a fixture queue root, as kiln's
/// `LocalScanTransport` runs `kiln-scan`: `BB_TEST_QUEUE_ROOT` is `queueRoot.apply(host)`, and
/// `environment` (for example `BB_TICKETS_NOW`) is added to this JVM's environment. For tests and the acceptance chain;
/// hosts are read over ssh ([TicketTransports]).
public record LocalTicketTransport(Path script, Fn1<Path, String> queueRoot, Map<String, String> environment) implements TicketTransport {
    /// The script as the controller module's tests see it.
    public static final Path BB_TICKETS = Path.of("..", "ops", "bb", "bb-tickets");
    private static final ProcessRunner RUNNER = ProcessRunner.processRunner();

    public LocalTicketTransport {
        environment = Map.copyOf(environment);
    }

    public static LocalTicketTransport localTicketTransport(Path script,
                                                            Fn1<Path, String> queueRoot,
                                                            Map<String, String> environment) {
        return new LocalTicketTransport(script, queueRoot, environment);
    }

    /// The real `ops/bb/bb-tickets`, one queue root for every host.
    public static LocalTicketTransport localTicketTransport(Path queueRoot) {
        return localTicketTransport(BB_TICKETS, _ -> queueRoot, Map.of());
    }

    @Override
    public Promise<String> read(String host) {
        return CommandTransport.commandTransport(RUNNER, this::command, false).read(host);
    }

    private Result<Command> command(String host) {
        var env = new HashMap<>(environment);

        env.put("BB_TEST_QUEUE_ROOT",
                queueRoot.apply(host).toString());

        return CommandTransport.inheriting(List.of("bash", script.toString()),
                                           env);
    }
}
