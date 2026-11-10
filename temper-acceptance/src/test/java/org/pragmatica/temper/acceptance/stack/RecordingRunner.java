package org.pragmatica.temper.acceptance.stack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

/// The real process runner, keeping the controller's `ssh … run <ver>` sessions so a host-tier test can kill one
/// (A11 case 1: "kill the ssh pid").
public final class RecordingRunner implements ProcessRunner {
    private final ProcessRunner real;
    public final List<Spawned> sessions = new CopyOnWriteArrayList<>();

    public RecordingRunner(ProcessRunner real) {
        this.real = real;
    }

    @Override
    public Promise<Completed> run(Command command) {
        return real.run(command);
    }

    @Override
    public Result<Spawned> spawn(Command command) {
        var spawned = real.spawn(command);
        var remote = LocalSsh.remoteArgs(command.argv());
        if (remote.map(args -> args.getFirst().equals("run")).or(false)) {
            spawned.onSuccess(sessions::add);
        }
        return spawned;
    }
}
