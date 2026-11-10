package org.pragmatica.temper.merge.engine;

import java.time.Duration;
import java.util.stream.Stream;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// How often the runner looks and how long each phase may last. A phase's deadline counts from when the job entered
/// it; past it the job is `TIMEOUT`, naming the phase and its last observation.
///
/// Built only through [#policy], which refuses a value that cannot work (a poll below [#MIN_POLL], a deadline not above
/// the poll). The hand-rolled chain this replaces took an invalid poll argument, exited at once and printed TIMEOUT; here
/// an invalid setting refuses the start and never becomes a job state.
public record Policy(Duration poll,
                     Duration submitted,
                     Duration checks,
                     Duration enqueuing,
                     Duration queue,
                     Duration run) {
    public static final Duration MIN_POLL = Duration.ofSeconds(10);

    /// Guessed defaults (#101 PR body): a pragmatica required set takes 40-90 min, a queue pass about as long again.
    public static final Policy DEFAULT = new Policy(Duration.ofSeconds(60),
                                                    Duration.ofMinutes(15),
                                                    Duration.ofHours(3),
                                                    Duration.ofMinutes(15),
                                                    Duration.ofHours(4),
                                                    Duration.ofHours(3));

    public static Result<Policy> policy(Duration poll,
                                        Duration submitted,
                                        Duration checks,
                                        Duration enqueuing,
                                        Duration queue,
                                        Duration run) {
        if (poll.compareTo(MIN_POLL) < 0) {
            return Causes.cause("poll must be at least " + MIN_POLL.toSeconds() + "s: " + poll.toSeconds() + "s").result();
        }

        var shortest = Stream.of(submitted, checks, enqueuing, queue, run)
                             .min(Duration::compareTo)
                             .orElse(Duration.ZERO);

        if (shortest.compareTo(poll) <= 0) {
            return Causes.cause("every deadline must be above the poll (" + poll.toSeconds()
                               + "s): " + shortest.toSeconds()
                               + "s").result();
        }

        return Result.success(new Policy(poll, submitted, checks, enqueuing, queue, run));
    }

    /// The deadline of a non-terminal state's phase; a terminal state has none (`Duration.ZERO`, never consulted).
    public Duration deadline(JobState state) {
        return switch (state) {
            case SUBMITTED -> submitted;
            case WAITING_CHECKS -> checks;
            case ENQUEUING -> enqueuing;
            case QUEUED -> queue;
            case AWAITING_RUN -> run;
            case MERGED, REMOVED, REFUSED, TIMEOUT, FAILED -> Duration.ZERO;
        };
    }
}
