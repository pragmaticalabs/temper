package org.pragmatica.kiln.service.store;

import java.util.List;
import java.util.Set;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// The pages' queries. Counts and pages are queries over the tables, never over "the last pass" (§3.4).
public interface ViewStore {
    Promise<List<PrView>> openPrs();
    Promise<List<HostCounts>> counts();

    Promise<Page<CheckoutView>> checkouts(Option<CheckoutStatus> status, Option<HostName> host, int page);

    Promise<BaseView> bases();
    Promise<List<NonGitView>> nonGit(HostName host);
    /// temper's digest text, verbatim, with the time it was read (every panel has an `as of` line, spec §6).
    Promise<Option<Observed<String>>> digest();
    /// The last pass of each kind on each host, the Mac's push included (`host_pass`), by host then kind (WP9).
    Promise<List<PassView>> passes();
    /// Every row filed under one of `statuses`, on `host` or on every host, unpaged, in the drill-down's idle order: the
    /// dashboard lists these in full (WP9: the listed statuses of every host, and every row of the Mac).
    Promise<List<CheckoutView>> listed(Set<CheckoutStatus> statuses, Option<HostName> host);
}
