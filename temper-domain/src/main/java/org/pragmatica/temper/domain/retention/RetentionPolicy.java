package org.pragmatica.temper.domain.retention;

import java.time.Duration;


/// How long each kind of data lives (m1-design §4.1, Q-M1-11). Every clock but `events` and `pgDumps` starts
/// when the job finishes: `evidence` covers the small tarball and the job's `testcases`, `manifests` and
/// `unit_facts` rows; `full` the full tarball; `checkout` the job directory on the worker host. `events` ages
/// by the event's own time, `pgDumps` by the dump's.
public record RetentionPolicy(Duration evidence, Duration full, Duration checkout, Duration events, Duration pgDumps) {
    /// 90 d, 14 d, 24 h, 30 d [G], 14 d.
    public static final RetentionPolicy DEFAULT = new RetentionPolicy(Duration.ofDays(90),
                                                                      Duration.ofDays(14),
                                                                      Duration.ofHours(24),
                                                                      Duration.ofDays(30),
                                                                      Duration.ofDays(14));

    public static RetentionPolicy retentionPolicy(Duration evidence,
                                                  Duration full,
                                                  Duration checkout,
                                                  Duration events,
                                                  Duration pgDumps) {
        return new RetentionPolicy(evidence, full, checkout, events, pgDumps);
    }
}
