package org.pragmatica.temper.domain.retention;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;


/// What retention knows about one job: whether it is pinned (`keep`) or still active (`terminal = false`),
/// when it finished, its evidence not yet deleted, the hosts that may still hold its checkout, and whether
/// any of its `testcases` rows remain.
public record JobRetention(JobId job,
                           boolean keep,
                           boolean terminal,
                           Option<Instant> finishedAt,
                           List<EvidenceItem> items,
                           List<String> hostsWithCheckout,
                           boolean hasTestcases) {
    public JobRetention {
        items = List.copyOf(items);
        hostsWithCheckout = List.copyOf(hostsWithCheckout);
    }

    public static JobRetention jobRetention(JobId job,
                                            boolean keep,
                                            boolean terminal,
                                            Option<Instant> finishedAt,
                                            List<EvidenceItem> items,
                                            List<String> hostsWithCheckout,
                                            boolean hasTestcases) {
        return new JobRetention(job, keep, terminal, finishedAt, items, hostsWithCheckout, hasTestcases);
    }
}
