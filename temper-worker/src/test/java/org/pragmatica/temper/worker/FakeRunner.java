package org.pragmatica.temper.worker;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.pragmatica.lang.Unit.unit;

/// A scripted [ProcessRunner]: every call is recorded in order; the first rule whose argv prefix matches
/// answers it; an unmatched call succeeds with rc 0 and empty output. Rules added later take precedence.
public final class FakeRunner implements ProcessRunner {
    private final List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<Command> commands = Collections.synchronizedList(new ArrayList<>());
    private final List<Rule> rules = new CopyOnWriteArrayList<>();

    public record Rule(List<String> prefix, Function<List<String>, Result<Completed>> answer) {
        boolean matches(List<String> argv) {
            return argv.size() >= prefix.size() && argv.subList(0, prefix.size())
                                                       .equals(prefix);
        }
    }

    public static Completed completed(int rc, String stdout, String stderr) {
        return Completed.completed("fake",
                                   rc,
                                   stdout.getBytes(StandardCharsets.UTF_8),
                                   stderr.getBytes(StandardCharsets.UTF_8),
                                   false);
    }

    /// Answers calls starting with `prefix` with `rc`, `stdout`, `stderr`.
    public FakeRunner on(List<String> prefix, int rc, String stdout, String stderr) {
        return on(prefix, _ -> Result.success(completed(rc, stdout, stderr)));
    }

    /// Answers calls starting with `prefix` with a computed result (runs at call time, in call order).
    public FakeRunner on(List<String> prefix, Function<List<String>, Result<Completed>> answer) {
        rules.addFirst(new Rule(List.copyOf(prefix), answer));
        return this;
    }

    /// Answers calls starting with `prefix` with `rc`, `stdout`, `stderr` only after `delay`, on the answering virtual
    /// thread: the caller's promise is certainly unresolved when its continuations are attached, so an `on*` callback
    /// on it runs asynchronously, after the dependents, instead of inline.
    public FakeRunner delayed(List<String> prefix, Duration delay, int rc, String stdout, String stderr) {
        return on(prefix, _ -> answeredAfter(delay, completed(rc, stdout, stderr)));
    }

    private static Result<Completed> answeredAfter(Duration delay, Completed completed) {
        Fixtures.pause(delay);

        return Result.success(completed);
    }

    /// Fails calls starting with `prefix` with `cause` (as a failed start or a timeout would).
    public FakeRunner failing(List<String> prefix, Cause cause) {
        return on(prefix, _ -> cause.result());
    }

    public List<List<String>> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public List<Command> commands() {
        synchronized (commands) {
            return List.copyOf(commands);
        }
    }

    /// Calls whose argv starts with `prefix`.
    public List<List<String>> callsStartingWith(List<String> prefix) {
        return calls().stream()
                      .filter(argv -> new Rule(prefix, _ -> Result.success(completed(0, "", ""))).matches(argv))
                      .toList();
    }

    /// The index of the first call starting with `prefix`, or -1.
    public int indexOf(List<String> prefix) {
        var all = calls();

        for (var index = 0; index < all.size(); index++) {
            if (new Rule(prefix, _ -> Result.success(completed(0, "", ""))).matches(all.get(index))) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public Promise<Completed> run(Command command) {
        calls.add(command.argv());
        commands.add(command);

        // Answered on a virtual thread, so a scripted answer may block (a `docker wait`) without blocking the caller.
        return Promise.promise(() -> answer(command.argv()));
    }

    @Override
    public Result<Spawned> spawn(Command command) {
        calls.add(command.argv());
        commands.add(command);

        return answer(command.argv()).map(FakeSpawned::new);
    }

    private Result<Completed> answer(List<String> argv) {
        return rules.stream()
                    .filter(rule -> rule.matches(argv))
                    .findFirst()
                    .map(rule -> rule.answer()
                                     .apply(argv))
                    .orElseGet(() -> Result.success(completed(0, "", "")));
    }

    /// A finished process whose streams replay a [Completed].
    record FakeSpawned(Completed completed) implements Spawned {
        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(completed.stdout());
        }

        @Override
        public OutputStream stdin() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(completed.stderr());
        }

        @Override
        public Promise<Integer> exit() {
            return Promise.success(completed.rc());
        }

        @Override
        public Unit kill() {
            return unit();
        }

        @Override
        public long pid() {
            return 1;
        }
    }
}
