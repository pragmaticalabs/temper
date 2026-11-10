package org.pragmatica.kiln.service.github;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// What kiln knows of its GitHub budget: the sample of the `kiln-app` identity, the credential
/// mode, and the drift reads. No field holds a token.
public record GitHubBudget(Option<Sample> sample, Credential.Mode mode, GitHubWatch.Drift drift) {
    /// `{identity, limit, remaining, used, reset, observedAt, source}` of §7.1; `source` names where it was read.
    public record Sample(String identity,
                         int limit,
                         int remaining,
                         int used,
                         Instant reset,
                         Instant observedAt,
                         String source) {
        public static final String HEADER = "header";
        public static final String RATE_LIMIT = "rate_limit";
    }
}
