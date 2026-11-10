package org.pragmatica.kiln.service.scan;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.pragmatica.kiln.service.scan.ScanTransportError.Failed;
import org.pragmatica.kiln.service.scan.ScanTransportError.NoLines;
import org.pragmatica.kiln.service.scan.ScanTransportError.NonZero;
import org.pragmatica.kiln.service.scan.ScanTransportError.TooLarge;
import org.pragmatica.kiln.service.scan.ScanTransportError.Uncarried;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;


/// One `kiln-scan` verb as a process, for [LocalScanTransport] and [SshScanTransport]: stdin is fed while stdout and
/// stderr are drained, so no pipe can fill; past `timeout` the process and its descendants are killed and the call
/// fails. rc 0 with at least one line is a listing; anything else fails the call (§3.4: a transport failure is never an
/// empty listing). Through ssh, the rc is the script's, or 255 for ssh's own failure.
sealed interface ScanExec {
    /// Characters of stderr, from its end, kept in [NonZero].
    int STDERR_TAIL_CHARS = 4096;
    /// The most bytes kept of each stream (WP8 fix round): more is a failed call, never a listing.
    int MAX_BYTES = 64 * 1024 * 1024;
    /// What kiln-scan's request split (single spaces, `split_request`) cannot carry inside one word.
    Pattern UNCARRIED = Pattern.compile("\\s");

    static Promise<List<String>> run(ProcessRunner runner,
                                     Command command,
                                     String verb,
                                     String input,
                                     TimeSpan timeout) {
        return run(runner, command, verb, input, timeout, MAX_BYTES);
    }

    static Promise<List<String>> run(ProcessRunner runner,
                                     Command command,
                                     String verb,
                                     String input,
                                     TimeSpan timeout,
                                     int maxBytes) {
        var over = new AtomicBoolean();

        return runner.spawn(command)
                     .async()
                     .flatMap(spawned -> answered(spawned, input, timeout, maxBytes, over))
                     .mapError(origin -> failed(verb, origin, maxBytes, over))
                     .flatMap(answer -> lines(verb, answer, maxBytes).async());
    }

    /// A stream past the cap is the reason, whatever else the kill it caused made fail (the exit, a feed).
    private static Cause failed(String verb, Cause origin, int maxBytes, AtomicBoolean over) {
        return over.get()
               ? TooLarge.FACTORY.apply(verb, maxBytes)
               : Failed.FACTORY.apply(verb, origin);
    }

    /// The request's words, when each is one word of kiln-scan's single-space split: a word holding whitespace (a
    /// checkout path with a space) would arrive as several words and be refused (64), so it is never sent.
    static Result<List<String>> carried(String verb, List<String> words) {
        return Result.firstFailureOf(words.stream()
                                          .map(word -> Verify.ensure(word,
                                                                     ScanExec::oneWord,
                                                                     Uncarried.FACTORY.apply(verb, word)))
                                          .toList());
    }

    private static boolean oneWord(String word) {
        return ! UNCARRIED.matcher(word).find();
    }

    /// The four parts at once: feeding stdin, both drains and the exit. A failed or late part kills the process, the
    /// only way to end the other parts' waits; the kill's own outcome is not observed, as nothing is left to wait for. A
    /// stream past `maxBytes` kills it too.
    private static Promise<Answer> answered(Spawned spawned,
                                            String input,
                                            TimeSpan timeout,
                                            int maxBytes,
                                            AtomicBoolean over) {
        return Promise.all(fed(spawned.stdin(),
                               input),
                           drained(spawned,
                                   spawned.stdout(),
                                   maxBytes,
                                   over),
                           drained(spawned,
                                   spawned.stderr(),
                                   maxBytes,
                                   over),
                           spawned.exit())
                      .map(Answer::answer)
                      .timeout(timeout)
                      .onFailure(_ -> spawned.kill());
    }

    private static Promise<Unit> fed(OutputStream stdin, String input) {
        return Promise.lift(Causes::fromThrowable,
                            () -> stdin.write(input.getBytes(StandardCharsets.UTF_8)))
                      .flatMap(_ -> Promise.lift(Causes::fromThrowable, stdin::close));
    }

    /// At most `maxBytes + 1` bytes: one more than the cap shows the stream is over it, and the process is killed.
    private static Promise<byte[]> drained(Spawned spawned, InputStream stream, int maxBytes, AtomicBoolean over) {
        return Promise.lift(Causes::fromThrowable,
                            () -> stream.readNBytes(maxBytes + 1))
                      .withSuccess(bytes -> killedPast(spawned, bytes, maxBytes, over));
    }

    private static void killedPast(Spawned spawned, byte[] bytes, int maxBytes, AtomicBoolean over) {
        if (bytes.length > maxBytes) {
            over.set(true);
            spawned.kill();
        }
    }

    /// A stream over the cap fails the call whatever the rc; then rc 0 with at least one line is a listing; a non-zero
    /// rc or no line at all fails the call.
    private static Result<List<String>> lines(String verb, Answer answer, int maxBytes) {
        return answer.over(maxBytes)
               ? TooLarge.FACTORY.apply(verb, maxBytes).result()
               : listing(verb, answer);
    }

    private static Result<List<String>> listing(String verb, Answer answer) {
        return answer.rc() == 0
               ? Verify.ensure(answer.stdout().lines().toList(),
                               Predicate.not(List::isEmpty),
                               NoLines.FACTORY.apply(verb))
               : NonZero.FACTORY.apply(verb,
                                       answer.rc(),
                                       answer.stderrTail())
                                .result();
    }

    /// What the process answered: its exit code and both streams, decoded as UTF-8.
    record Answer(int rc, String stdout, String stderr, int longest) {
        static Answer answer(Unit fed, byte[] stdout, byte[] stderr, Integer rc) {
            return new Answer(rc,
                              new String(stdout, StandardCharsets.UTF_8),
                              new String(stderr, StandardCharsets.UTF_8),
                              Math.max(stdout.length, stderr.length));
        }

        boolean over(int maxBytes) {
            return longest > maxBytes;
        }

        String stderrTail() {
            return stderr.substring(Math.max(0, stderr.length() - STDERR_TAIL_CHARS));
        }
    }

    record unused() implements ScanExec {}
}
