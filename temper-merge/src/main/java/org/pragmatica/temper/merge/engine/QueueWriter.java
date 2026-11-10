package org.pragmatica.temper.merge.engine;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// The only two GitHub writes the runner makes. The write-capable identity (a GitHub App) is the owner's pending
/// decision (#101), so this module ships no implementation that sends a mutation: production wiring uses [#pending],
/// tests use fakes.
///
/// A failure means the write was not confirmed: GitHub refused it, or no answer came. The runner reports it (`FAILED`)
/// and never retries a write.
public interface QueueWriter {
    /// `markPullRequestReadyForReview`, sent only when the submit said `--ready`.
    Promise<Unit> markReady(String repo, long pr);
    /// `enqueuePullRequest(pullRequestId, expectedHeadOid)`: GitHub itself refuses it when the head is not
    /// `expectedHeadOid`.
    Promise<Unit> enqueue(String repo, long pr, String expectedHeadOid);

    /// Until the owner decides the write identity: every write fails, sending nothing.
    static QueueWriter pending() {
        var why = Causes.cause("no GitHub write identity: the write-capable App is the owner's pending decision (#101)");

        return new QueueWriter() {
            @Override
            public Promise<Unit> markReady(String repo, long pr) {
                return why.promise();
            }

            @Override
            public Promise<Unit> enqueue(String repo, long pr, String expectedHeadOid) {
                return why.promise();
            }
        };
    }
}
