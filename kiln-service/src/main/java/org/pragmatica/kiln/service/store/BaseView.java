package org.pragmatica.kiln.service.store;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;


/// The base branches panel (spec §6 panel 3): the latest runs per configured base and the scheduled runs (`base_run`),
/// and the open `github-actions` issues (`gh_issue`). The shape is WP1's; WP5 and WP10 may extend it.
public record BaseView(List<Run> runs, List<Issue> issues) {
    public BaseView {
        runs = List.copyOf(runs);
        issues = List.copyOf(issues);
    }

    /// One workflow run; `scope` is a base or `schedule`.
    public record Run(long id,
                      String scope,
                      String name,
                      String status,
                      Option<String> conclusion,
                      String headSha,
                      String htmlUrl,
                      Instant createdAt,
                      Instant observedAt) {}

    public record Issue(long number, String title, String htmlUrl, Instant createdAt, Instant observedAt) {}
}
