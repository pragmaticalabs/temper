package org.pragmatica.temper.controller.fake;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.message.Message;

/// A `ProcessRunner` standing in for ssh: every `run <ver>` spawn connects a fresh [FakeWorker] answering through
/// the current responder; an `install` spawn reads the jar and exits 0. Each worker is also queued by the address its
/// argv names ([#nextWorker(String)]), for harnesses with more than one host. A [#blackholed] address answers every
/// spawn, `install` included, with an exit 255 at once (ssh that cannot connect), so its link never comes back.
public final class FakeSsh implements ProcessRunner {
    public final List<List<String>> spawns = new CopyOnWriteArrayList<>();
    public final LinkedBlockingQueue<FakeWorker> workers = new LinkedBlockingQueue<>();
    public final Map<String, LinkedBlockingQueue<FakeWorker>> byAddress = new ConcurrentHashMap<>();
    public final Set<String> blackholed = ConcurrentHashMap.newKeySet();
    public volatile Function<Message, List<Message>> responder;

    public FakeSsh(Function<Message, List<Message>> responder) {
        this.responder = responder;
    }

    /// The next worker connected to `address`.
    public FakeWorker nextWorker(String address) throws InterruptedException {
        var worker = queue(address).poll(10, TimeUnit.SECONDS);
        if (worker == null) {
            throw new AssertionError("no ssh session to " + address + " within 10 s; spawns " + spawns);
        }
        workers.remove(worker);
        return worker;
    }

    private LinkedBlockingQueue<FakeWorker> queue(String address) {
        return byAddress.computeIfAbsent(address, _ -> new LinkedBlockingQueue<>());
    }

    /// The `user@address` argument of an ssh argv, as its address.
    private static String address(List<String> argv) {
        return argv.stream().filter(arg -> arg.contains("@")).findFirst().map(arg -> arg.substring(arg.indexOf('@') + 1)).orElse("");
    }

    public FakeWorker nextWorker() throws InterruptedException {
        var worker = workers.poll(10, TimeUnit.SECONDS);
        if (worker == null) {
            throw new AssertionError("no ssh session within 10 s; spawns " + spawns);
        }
        byAddress.values().forEach(queue -> queue.remove(worker));
        return worker;
    }

    @Override
    public Promise<Completed> run(Command command) {
        return Causes.cause("run is not used by host links").promise();
    }

    @Override
    public Result<Spawned> spawn(Command command) {
        spawns.add(command.argv());
        var process = new PipeProcess();
        var address = address(command.argv());
        if (blackholed.contains(address)) {
            process.workerExits(255);
        } else if (command.argv().contains("install")) {
            Thread.ofVirtual().start(() -> install(process));
        } else if (command.argv().contains("evidence")) {
            Thread.ofVirtual().start(() -> evidence(command, process));
        } else {
            var worker = new FakeWorker(process, message -> responder.apply(message));
            workers.add(worker);
            queue(address).add(worker);
        }
        return Result.success(process);
    }

    /// `worker-launch evidence`: a tiny gzip stream on the redirected stdout, exit 0.
    private static void evidence(Command command, PipeProcess process) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(bytes)) {
                gzip.write("evidence".getBytes(StandardCharsets.UTF_8));
            }
            Files.write(command.stdoutFile().unwrap(), bytes.toByteArray());
            process.workerExits(0);
        } catch (IOException e) {
            process.workerExits(1);
        }
    }

    private static void install(PipeProcess process) {
        try {
            process.workerIn().readAllBytes();
        } catch (IOException ignored) {
            // the jar content is not inspected here
        }
        process.workerExits(0);
    }
}
