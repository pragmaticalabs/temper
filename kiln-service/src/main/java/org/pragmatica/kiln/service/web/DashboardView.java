package org.pragmatica.kiln.service.web;

import java.util.List;
import java.util.Map;

import org.pragmatica.kiln.core.github.PullsPage;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.temper.HostsRead;
import org.pragmatica.kiln.service.conflict.ConflictBoard;
import org.pragmatica.kiln.service.pipeline.PipelineView;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.NonGitView;
import org.pragmatica.kiln.service.store.PassView;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.WorkBoard;
import org.pragmatica.lang.Option;


/// Everything the dashboard shows (spec §6), gathered by the caller (WP9) from `ViewStore`, `SourceStore`, the
/// configuration and the GitHub poller; [Dashboard] only renders it.
///
/// - `bases`: the configured bases, in configured order; a PR on any other base is stacked.
/// - `hosts`: the configured hosts; a host that still has rows or a state is shown too.
/// - `budget`, `skipped`: the GitHub poller's last rate budget and the PRs its last page skipped (`skipped #N`).
/// - `passes`: the last pass of each kind per host, and the Mac's push.
/// - `listed`: the rows the dashboard lists (`ACTIVE`, `IN-USE`, `NEEDS-SALVAGE`, `UNSCANNED`) of every host; others
///   are filtered out. `mac`: every Mac row, all statuses (the Mac section is shown in full).
/// - `nonGit`: each host's non-git directories, counted per root.
/// - `work`: the work ledger's board, the Work tab's only source.
/// - `pipeline`: the pipeline baseline (#102): its state and every stored merged PR, the Pipeline tab's only source.
/// - `fleet`: the last fleet-alarms reply that decoded and when it was read, held in memory only (G-S1-8); absent until
///   the first.
/// - `conflicts`: the merge-tree conflict early warning's board (temper #100), held in memory only; none while the watch
///   is off.
public record DashboardView(Sources sources,
                            List<BranchName> bases,
                            List<HostName> hosts,
                            Option<Observed<RateBudget>> budget,
                            List<Observed<PullsPage.Skipped>> skipped,
                            List<PassView> passes,
                            List<PrView> prs,
                            BaseView runs,
                            List<HostCounts> counts,
                            List<CheckoutView> listed,
                            List<CheckoutView> mac,
                            Map<HostName, List<NonGitView>> nonGit,
                            Option<Observed<String>> digest,
                            WorkBoard work,
                            Option<Observed<HostsRead.Answered>> fleet,
                            PipelineView pipeline,
                            Option<ConflictBoard> conflicts) {
    public DashboardView {
        bases = List.copyOf(bases);
        hosts = List.copyOf(hosts);
        skipped = List.copyOf(skipped);
        passes = List.copyOf(passes);
        prs = List.copyOf(prs);
        counts = List.copyOf(counts);
        listed = List.copyOf(listed);
        mac = List.copyOf(mac);
        nonGit = Map.copyOf(nonGit);
    }

    /// A view without the pipeline baseline read ([PipelineView#NONE]) or the conflict watch's board.
    public DashboardView(Sources sources,
                         List<BranchName> bases,
                         List<HostName> hosts,
                         Option<Observed<RateBudget>> budget,
                         List<Observed<PullsPage.Skipped>> skipped,
                         List<PassView> passes,
                         List<PrView> prs,
                         BaseView runs,
                         List<HostCounts> counts,
                         List<CheckoutView> listed,
                         List<CheckoutView> mac,
                         Map<HostName, List<NonGitView>> nonGit,
                         Option<Observed<String>> digest,
                         WorkBoard work,
                         Option<Observed<HostsRead.Answered>> fleet) {
        this(sources,
             bases,
             hosts,
             budget,
             skipped,
             passes,
             prs,
             runs,
             counts,
             listed,
             mac,
             nonGit,
             digest,
             work,
             fleet,
             PipelineView.NONE,
             Option.none());
    }

    /// This view with the pipeline baseline `read`.
    public DashboardView withPipeline(PipelineView read) {
        return new DashboardView(sources,
                                 bases,
                                 hosts,
                                 budget,
                                 skipped,
                                 passes,
                                 prs,
                                 runs,
                                 counts,
                                 listed,
                                 mac,
                                 nonGit,
                                 digest,
                                 work,
                                 fleet,
                                 read,
                                 conflicts);
    }

    /// A view with no fleet-alarms reply read yet.
    public DashboardView(Sources sources,
                         List<BranchName> bases,
                         List<HostName> hosts,
                         Option<Observed<RateBudget>> budget,
                         List<Observed<PullsPage.Skipped>> skipped,
                         List<PassView> passes,
                         List<PrView> prs,
                         BaseView runs,
                         List<HostCounts> counts,
                         List<CheckoutView> listed,
                         List<CheckoutView> mac,
                         Map<HostName, List<NonGitView>> nonGit,
                         Option<Observed<String>> digest,
                         WorkBoard work) {
        this(sources,
             bases,
             hosts,
             budget,
             skipped,
             passes,
             prs,
             runs,
             counts,
             listed,
             mac,
             nonGit,
             digest,
             work,
             Option.none(),
             PipelineView.NONE,
             Option.none());
    }
}
