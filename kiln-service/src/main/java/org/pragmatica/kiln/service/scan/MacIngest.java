package org.pragmatica.kiln.service.scan;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.core.scan.ScanOutcome.Complete;
import org.pragmatica.kiln.core.scan.ScanOutcome.Incomplete;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.flow.Serial;
import org.pragmatica.kiln.service.flow.SingleFlight;
import org.pragmatica.kiln.service.store.CheckoutStore;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassRecord;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// The Mac's pushed scan (kiln-f1-design §3.1, spec §5.4): the lines `kiln-scan scan` printed on the Mac, judged by
/// [ScanOutcome#of] and applied as a host's pass, with host [HostName#MAC] and pass kind [PassKind#PUSH]. There is no
/// `reach` (a Mac tip the mirror lacks reads `UNKNOWN`) and no `size`. Only a [Complete] push deletes, and only under
/// `roots` (the Mac push's own roots, from WP9's configuration) expanded with the push's trailer `home` ([ScanRoots]).
/// Pushes are applied one at a time (one pass per host, §3.1). The HTTP route (WP9) answers [#answer].
public final class MacIngest {
    /// The largest body applied, as `kiln-ingest` caps it.
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    /// A push still being applied this long frees the next one.
    public static final Duration DEADLINE = Duration.ofMinutes(5);
    static final Cause TOO_LARGE = Causes.cause("the Mac push is over 8 MiB");

    private final CheckoutStore checkouts;
    private final SourceStore sources;
    private final List<String> roots;
    private final Supplier<Instant> clock;
    private final Runnable passed;
    private final Serial serial = new Serial();

    private MacIngest(CheckoutStore checkouts,
                      SourceStore sources,
                      List<String> roots,
                      Supplier<Instant> clock,
                      Runnable passed) {
        this.checkouts = checkouts;
        this.sources = sources;
        this.roots = List.copyOf(roots);
        this.clock = clock;
        this.passed = passed;
    }

    /// `passed` runs after every applied push (the derivation's trigger, §3.1).
    public static MacIngest macIngest(CheckoutStore checkouts,
                                      SourceStore sources,
                                      List<String> roots,
                                      Supplier<Instant> clock,
                                      Runnable passed) {
        return new MacIngest(checkouts, sources, roots, clock, passed);
    }

    /// Applies one push: the store's record of it, after the `mac` source state was written.
    public Promise<PassRecord> ingest(String body) {
        return Verify.ensure(body,
                             text -> text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES,
                             TOO_LARGE)
                     .async()
                     .flatMap(text -> serial.run(() -> SingleFlight.bounded(() -> applied(ScanOutcome.of(text.lines()
                                                                                                             .toList()),
                                                                                          clock.get()),
                                                                            DEADLINE)));
    }

    /// `{"outcome":"COMPLETE"|"INCOMPLETE","rows":N}`.
    public static String answer(PassRecord record) {
        return "{\"outcome\":\"" + record.outcome()
                                         .name() + "\",\"rows\":" + record.rows() + "}";
    }

    private Promise<PassRecord> applied(ScanOutcome outcome, Instant at) {
        return checkouts.applyPass(HostName.MAC,
                                   PassKind.PUSH,
                                   rootsOf(outcome),
                                   outcome,
                                   at)
                        .flatMap(record -> sources.record(Source.MAC,
                                                          state(outcome, at))
                                                  .map(_ -> record))
                        .withSuccess(_ -> passed.run());
    }

    private List<String> rootsOf(ScanOutcome outcome) {
        return switch (outcome) {
            case Complete complete -> ScanRoots.expanded(roots, complete.home());
            case Incomplete _ -> roots;
        };
    }

    private static SourceState state(ScanOutcome outcome, Instant at) {
        return switch (outcome) {
            case Complete _ -> new SourceState.Ok(at);
            case Incomplete incomplete -> new SourceState.Incomplete(incomplete.reason(), at);
        };
    }
}
