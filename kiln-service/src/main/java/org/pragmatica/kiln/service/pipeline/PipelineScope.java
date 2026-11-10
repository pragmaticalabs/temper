package org.pragmatica.kiln.service.pipeline;

import java.time.Duration;
import java.time.Instant;


/// What the pipeline baseline measures (#102): PRs of `repo` merged into `base` from `since` on. Each cycle sends at most
/// `slice` requests through the shared GitHub budget.
///
/// [guess: the scope is fixed by the maintainer's brief (pragmatica, `release-1.0.0-rc5`, 2026-10-28T00:00Z) and is not a
/// configuration key; the slice is 40 requests per 10-minute cycle, at most 240 an hour, about 15% of the configured
/// `hourlyCap` of 1,572, which the poller's own pacing gives up in proportion.]
public record PipelineScope(String repo, String base, Instant since, int slice, Duration period) {
    public static final String BASE = "release-1.0.0-rc5";
    public static final Instant SINCE = Instant.parse("2026-10-28T00:00:00Z");
    public static final int SLICE = 40;
    public static final Duration PERIOD = Duration.ofMinutes(10);

    public static PipelineScope pipelineScope(String repo) {
        return new PipelineScope(repo, BASE, SINCE, SLICE, PERIOD);
    }
}
