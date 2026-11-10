package org.pragmatica.kiln.core.source;

import java.time.Instant;


/// A source's current state (spec §5.6, kiln-f1-design §1.1). Only [Ok] is a success; every other state is shown with
/// the time it began, so a failing source always shows how long it has failed.
public sealed interface SourceState {
    /// The last read succeeded at `at`.
    record Ok(Instant at) implements SourceState {}

    /// A host whose ssh or scan failed (`UNREACHABLE(<reason>) since <t>`).
    record Unreachable(String reason, Instant since) implements SourceState {}

    /// A host scan that answered without a matching trailer: its rows were kept, none deleted (spec §5.2).
    record Incomplete(String reason, Instant at) implements SourceState {}

    /// GitHub's rate limit is spent, or the budget left is below a cycle's need, until `reset`.
    record RateLimited(Instant reset, Instant since) implements SourceState {}

    /// GitHub answered a failure other than the rate limit (`GITHUB-DOWN`), with the HTTP status or the reason.
    record GithubDown(String status, Instant since) implements SourceState {}

    /// temper refused or failed: connect failure, 5xx, 401 or 403 (`TEMPER-DOWN`).
    record TemperDown(String reason, Instant since) implements SourceState {}

    /// temper answered, but without a field kiln requires (`TEMPER-SCHEMA(<field>)`, spec §4.5, E-1).
    record TemperSchema(String field, Instant since) implements SourceState {}

    /// `next`, with the `since` of `previous` kept when the source goes on failing in the same way, so a failing source
    /// shows when it began to fail, not when it was last read. A failure of another kind, or one after [Ok], starts at
    /// its own time; [Ok] and [Incomplete] carry the time of the read itself.
    static SourceState continued(SourceState previous, SourceState next) {
        return switch (next) {
            case RateLimited(var reset, var _) when previous instanceof RateLimited(var _, var since) -> new RateLimited(reset,
                                                                                                                         since);
            case GithubDown(var status, var _) when previous instanceof GithubDown(var _, var since) -> new GithubDown(status,
                                                                                                                       since);
            case Unreachable(var reason, var _) when previous instanceof Unreachable(var _, var since) -> new Unreachable(reason,
                                                                                                                          since);
            case TemperDown(var reason, var _) when previous instanceof TemperDown(var _, var since) -> new TemperDown(reason,
                                                                                                                       since);
            case TemperSchema(var field, var _) when previous instanceof TemperSchema(var _, var since) -> new TemperSchema(field,
                                                                                                                            since);
            default -> next;
        };
    }
}
