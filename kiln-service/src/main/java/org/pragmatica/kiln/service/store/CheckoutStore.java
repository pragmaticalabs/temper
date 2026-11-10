package org.pragmatica.kiln.service.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// `checkout`, `stash`, `checkout_derived`, `non_git` and `host_pass`.
public interface CheckoutStore {
    /// Upserts the pass's rows. Deletes rows absent from it **only** for a [ScanOutcome.Complete] outcome, and only under
    /// the pass's ABSOLUTE roots (§3.4, K-4): a root that still starts with `~` deletes nothing, since the store does not
    /// know the host's home; the caller (WP8) expands `~` from the scan trailer's `home` (WP4) first.
    ///
    /// Precondition (lead ruling, WP5 verification): one pass per host at a time (the per-host `Serial`, §3.1). Two racing
    /// COMPLETE passes of one host may deadlock and roll back, which loses no row but fails the pass.
    Promise<PassRecord> applyPass(HostName host, PassKind kind, List<String> roots, ScanOutcome outcome, Instant at);

    /// Records a failed pass; touches no row (K-5).
    Promise<Unit> passFailed(HostName host, PassKind kind, String reason, Instant at);
    /// Sets the `k of n` of the host's last pass of `kind` (`host_pass.progress_k`, `progress_n`; the full pass's chunks,
    /// §3.1), and nothing else; [#applyPass] and [#passFailed] clear it.
    Promise<Unit> progress(HostName host, PassKind kind, int k, int n);
    /// The host's rows, ordered by path.
    Promise<List<CheckoutRow>> rows(HostName host);
    /// The checkouts the next hot pass gives full facts (§3.2, Q-12), ordered by path.
    Promise<List<CheckoutPath>> hotSet(HostName host, Instant now, Duration window);
    Promise<Unit> derived(HostName host, List<DerivedRow> rows);
    Promise<Unit> size(HostName host, CheckoutPath path, long kb, Instant at);
    /// Null sizes first, then the oldest.
    Promise<Option<CheckoutPath>> nextToSize(HostName host);
}
