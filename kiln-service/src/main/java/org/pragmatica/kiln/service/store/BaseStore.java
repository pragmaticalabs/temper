package org.pragmatica.kiln.service.store;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// `base_run` and `gh_issue` (spec §6 panel 3), added in WP5: §2 names the tables, and §3.5 had no writer for them. Each
/// write is called only after a successful read and replaces its set; a failed read calls nothing, so the old set stays
/// with its age. Each row carries its own `observedAt`.
public interface BaseStore {
    /// Replaces the runs of `scope` (a base, or `schedule`).
    Promise<Unit> runs(String scope, List<BaseView.Run> runs);
    /// Replaces the open `github-actions` issues.
    Promise<Unit> issues(List<BaseView.Issue> issues);
}
