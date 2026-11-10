package org.pragmatica.kiln.service.scan;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.CheckoutStore;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassOutcome;
import org.pragmatica.kiln.service.store.PassRecord;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// In-memory fakes for the scan pipeline's unit tests: a [CheckoutStore] and a [SourceStore] that record every call, and
/// a [ScanTransport] that answers from a script of answers and counts the sessions open at once.
public final class ScanFakes {
    public static final HostName HOST = HostName.hostName("bigboy").unwrap();
    public static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");

    private ScanFakes() {}

    /// A clock that always reads [#T0].
    public static Instant t0() {
        return T0;
    }

    public record Applied(HostName host, PassKind kind, List<String> roots, ScanOutcome outcome, Instant at) {}

    public record Failed(HostName host, PassKind kind, String reason, Instant at) {}

    public record Progress(HostName host, PassKind kind, int k, int n) {}

    public record Derived(HostName host, List<DerivedRow> rows) {}

    public record Size(HostName host, CheckoutPath path, long kb, Instant at) {}

    /// Records every write; `rows` and `hot` are what the reads answer.
    public static class RecordingStore implements CheckoutStore {
        public final List<Applied> applied = new CopyOnWriteArrayList<>();
        public final List<Failed> failed = new CopyOnWriteArrayList<>();
        public final List<Progress> progress = new CopyOnWriteArrayList<>();
        public final List<Size> sizes = new CopyOnWriteArrayList<>();
        /// The derived rows written for [ScanFakes#HOST]; every host's in [#derivedCalls].
        public final List<List<DerivedRow>> derived = new CopyOnWriteArrayList<>();
        public final List<Derived> derivedCalls = new CopyOnWriteArrayList<>();
        public final List<String> calls = new CopyOnWriteArrayList<>();
        public volatile List<CheckoutRow> rows = List.of();
        public volatile List<CheckoutPath> hot = List.of();
        /// Every read and write answers this, when set.
        public volatile Option<Supplier<Promise<Unit>>> gate = none();

        private <T> Promise<T> gated(Supplier<Promise<T>> answer) {
            return gate.map(g -> g.get()
                                  .flatMap(_ -> answer.get()))
                       .or(answer);
        }

        @Override
        public Promise<PassRecord> applyPass(HostName host, PassKind kind, List<String> roots, ScanOutcome outcome, Instant at) {
            calls.add("applyPass");
            applied.add(new Applied(host, kind, List.copyOf(roots), outcome, at));
            var record = switch (outcome) {
                case ScanOutcome.Complete complete -> new PassRecord(PassOutcome.COMPLETE, complete.rows().size());
                case ScanOutcome.Incomplete incomplete -> new PassRecord(PassOutcome.INCOMPLETE, incomplete.rows().size());
            };
            return gated(() -> Promise.success(record));
        }

        @Override
        public Promise<Unit> passFailed(HostName host, PassKind kind, String reason, Instant at) {
            calls.add("passFailed");
            failed.add(new Failed(host, kind, reason, at));
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> progress(HostName host, PassKind kind, int k, int n) {
            calls.add("progress");
            progress.add(new Progress(host, kind, k, n));
            return Promise.unitPromise();
        }

        @Override
        public Promise<List<CheckoutRow>> rows(HostName host) {
            calls.add("rows");
            return gated(() -> Promise.success(host.equals(HOST)
                                               ? rows
                                               : List.of()));
        }

        @Override
        public Promise<List<CheckoutPath>> hotSet(HostName host, Instant now, Duration window) {
            calls.add("hotSet");
            return gated(() -> Promise.success(hot));
        }

        @Override
        public Promise<Unit> derived(HostName host, List<DerivedRow> rows) {
            calls.add("derived");
            derivedCalls.add(new Derived(host, List.copyOf(rows)));
            if (host.equals(HOST)) {
                derived.add(List.copyOf(rows));
            }
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> size(HostName host, CheckoutPath path, long kb, Instant at) {
            calls.add("size");
            sizes.add(new Size(host, path, kb, at));
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<CheckoutPath>> nextToSize(HostName host) {
            throw new UnsupportedOperationException("the size pass picks its own path");
        }
    }

    public record Recorded(Source source, SourceState state) {}

    public static final class RecordingSources implements SourceStore {
        public final List<Recorded> recorded = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> record(Source source, SourceState state) {
            recorded.add(new Recorded(source, state));
            return Promise.unitPromise();
        }

        @Override
        public Promise<List<SourceStatus>> all() {
            return Promise.success(List.of());
        }

        public SourceState last() {
            return recorded.getLast().state();
        }
    }

    public record Call(String verb, List<String> words, List<CheckoutPath> full) {}

    /// Answers each call with the next scripted answer (the last one repeats); counts the sessions open at once.
    public static final class ScriptedTransport implements ScanTransport {
        public final List<Call> calls = new CopyOnWriteArrayList<>();
        public final AtomicInteger open = new AtomicInteger();
        public final AtomicInteger maxOpen = new AtomicInteger();
        private final Deque<Supplier<Promise<List<String>>>> answers = new ArrayDeque<>();
        private Supplier<Promise<List<String>>> lastAnswer = () -> Promise.success(List.of());

        public synchronized ScriptedTransport then(Supplier<Promise<List<String>>> answer) {
            answers.add(answer);
            return this;
        }

        public ScriptedTransport thenLines(List<String> lines) {
            return then(() -> Promise.success(lines));
        }

        private synchronized Supplier<Promise<List<String>>> next() {
            if (!answers.isEmpty()) {
                lastAnswer = answers.poll();
            }
            return lastAnswer;
        }

        private Promise<List<String>> answer(Call call) {
            calls.add(call);
            maxOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
            return next().get().withResult(_ -> open.decrementAndGet());
        }

        @Override
        public Promise<List<String>> scan(List<String> roots, List<CheckoutPath> full) {
            return answer(new Call("scan", List.copyOf(roots), List.copyOf(full)));
        }

        @Override
        public Promise<List<String>> reach(Set<Sha> shas) {
            return answer(new Call("reach", shas.stream().map(Sha::value).sorted().toList(), List.of()));
        }

        @Override
        public Promise<List<String>> size(String root, CheckoutPath path) {
            return answer(new Call("size", List.of(root, path.value()), List.of()));
        }

        public List<Call> calls(String verb) {
            return calls.stream().filter(call -> call.verb().equals(verb)).toList();
        }
    }

    public static CheckoutPath path(String raw) {
        return CheckoutPath.checkoutPath(raw).unwrap();
    }

    /// A stub row (no facts) at `path`, sized at `sizeAt` when given.
    public static CheckoutRow row(String path, Option<Instant> sizeAt) {
        return new CheckoutRow(HOST,
                               path(path),
                               path + "/.git",
                               T0,
                               none(),
                               none(),
                               none(),
                               T0,
                               sizeAt.map(_ -> 1L),
                               sizeAt);
    }

    public static CheckoutRow row(String path) {
        return row(path, none());
    }

    public static CheckoutRow factsRow(CheckoutFacts facts, Instant factsAt) {
        return new CheckoutRow(HOST,
                               facts.path(),
                               facts.commonDir(),
                               facts.activityAt(),
                               some(facts),
                               some(PassKind.HOT),
                               some(factsAt),
                               factsAt,
                               none(),
                               none());
    }

    /// A stub line and a trailer: a complete listing of `paths`, with `home` in the trailer when given.
    public static List<String> listing(Option<String> home, String... paths) {
        var lines = new ArrayList<String>();
        for (var path : paths) {
            lines.add("{\"kind\":\"stub\",\"path\":\"" + path + "\",\"activityAt\":1761820000,\"commonDir\":\"" + path + "/.git\"}");
        }
        lines.add("{\"end\":true,\"count\":" + paths.length + ",\"nonGit\":0,\"nonGitBytes\":null"
                  + home.map(dir -> ",\"home\":\"" + dir + "\"").or("") + "}");
        return lines;
    }
}
