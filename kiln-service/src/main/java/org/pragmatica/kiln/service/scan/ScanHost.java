package org.pragmatica.kiln.service.scan;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.service.flow.Serial;
import org.pragmatica.kiln.service.flow.SingleFlight;
import org.pragmatica.lang.Promise;


/// One scanned host: its name, its configured roots (the words of `scan`, unexpanded), its transport, and the one
/// [Serial] every kiln ssh session of it goes through (kiln-f1-design §3.1): the hot, full and size passes of
/// [HostScanner] and the `reach` calls of the derivation share it, so the host never sees two kiln ssh sessions at once,
/// and the store sees one pass of the host at a time ([org.pragmatica.kiln.service.store.CheckoutStore#applyPass]).
public record ScanHost(HostName name, List<String> roots, ScanTransport transport, Serial serial) {
    public ScanHost {
        roots = List.copyOf(roots);
    }

    public static ScanHost scanHost(HostName name, List<String> roots, ScanTransport transport) {
        return new ScanHost(name, roots, transport, new Serial());
    }

    /// `operation` alone on this host, once the previous one settled. It is [SingleFlight#bounded], so a throw or a
    /// promise that never settles still frees the next operation past `deadline`. Residual: past the deadline the next
    /// operation may start while the late one's process (killed at the transport's own, shorter, timeout) or store write
    /// is still finishing.
    public <T> Promise<T> exclusive(Supplier<Promise<T>> operation, Duration deadline) {
        return serial.run(() -> SingleFlight.bounded(operation, deadline));
    }
}
