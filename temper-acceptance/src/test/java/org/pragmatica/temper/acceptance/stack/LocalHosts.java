package org.pragmatica.temper.acceptance.stack;

import java.util.Map;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.pragmatica.lang.Option.option;

/// Several [LocalSsh] hosts behind the controller's one process runner: an ssh argv goes to the host whose address
/// its `user@address` element names; every other command runs as given (m4-design §12.3, `MultiHostLostIT`).
public final class LocalHosts implements ProcessRunner {
    private final ProcessRunner real = ProcessRunner.processRunner();
    private final Map<String, LocalSsh> byAddress;

    public LocalHosts(Map<String, LocalSsh> byAddress) {
        this.byAddress = Map.copyOf(byAddress);
    }

    @Override
    public Promise<Completed> run(Command command) {
        return real.run(command);
    }

    @Override
    public Result<Spawned> spawn(Command command) {
        return LocalSsh.destination(command.argv())
                       .flatMap(address -> option(byAddress.get(address)))
                       .map(host -> host.spawn(command))
                       .or(() -> real.spawn(command));
    }
}
