package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.host.UnitAttempt;
import org.pragmatica.temper.domain.diff.DiffEntry;
import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.diff.DiffOutcome;
import org.pragmatica.temper.domain.diff.DiffSide;
import org.pragmatica.temper.domain.diff.SkipChange;
import org.pragmatica.temper.domain.diff.TestDiff;
import org.pragmatica.temper.domain.gate.GateCandidate;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.protocol.api.DiffAnswer;
import org.pragmatica.temper.protocol.api.DiffRefusal;
import org.pragmatica.temper.protocol.api.DiffView;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.StoredSide;
import org.pragmatica.temper.store.api.StoredSides;
import org.pragmatica.temper.store.api.UnitRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The diff's sides → [DiffOutcome] → `DiffAnswer` (m2-design §3.2, §3.3). A side's state is the one a job view shows
/// ([Views#jobState]); its counts come from its result record, absent until it is terminal.
public sealed interface DiffViews {
    static DiffAnswer answer(StoredSides sides,
                             Option<JobId> base,
                             Instant now,
                             Duration startBound,
                             Set<UnitAttempt> unrecorded) {
        var a = side(sides.a(), now, startBound, unrecorded);
        var b = side(sides.b(), now, startBound, unrecorded);

        return switch (DiffOutcome.decide(a, b)) {
            case DiffOutcome.Compared compared -> diffView(view(sides.a(), a), view(sides.b(), b), base, compared.diff());
            case DiffOutcome.Refused refused -> refusal(refused, a, b);
        };
    }

    /// Two sides not compared: the obstacle and the sides it is about, each with its shown state.
    private static DiffAnswer refusal(DiffOutcome.Refused refused, DiffSide a, DiffSide b) {
        return DiffRefusal.diffRefusal(refused.obstacle(),
                                       some(a.job().value()),
                                       b.job().value(),
                                       refused.sides().stream().map(label -> sideState(label, a, b)).toList(),
                                       none(),
                                       List.of());
    }

    /// `--base <sha>` named no Gate: `sha` is the SHA it resolved to, or the asked prefix.
    static DiffAnswer noBase(JobId job, String sha) {
        return DiffRefusal.diffRefusal(DiffObstacle.NO_BASE, none(), job.value(), List.of(), some(sha), List.of());
    }

    /// `--base <sha>` named a CONFLICT (ruling R-M2-9 on Q-M2-26): every vote, newest first, and no diff.
    static DiffAnswer baseConflict(JobId job, String sha, List<GateCandidate> votes) {
        return DiffRefusal.diffRefusal(DiffObstacle.BASE_CONFLICT,
                                       none(),
                                       job.value(),
                                       List.of(),
                                       some(sha),
                                       GateViews.votes(votes));
    }

    private static DiffView diffView(DiffView.Side a, DiffView.Side b, Option<JobId> base, TestDiff diff) {
        return DiffView.diffView(a,
                                 b,
                                 base.map(JobId::value),
                                 entries(diff.added()),
                                 entries(diff.removed()),
                                 entries(diff.newlyFailing()),
                                 entries(diff.newlyPassing()),
                                 diff.skipChanged().stream().map(DiffViews::skip).toList(),
                                 counts(diff));
    }

    private static List<DiffView.Entry> entries(List<DiffEntry> entries) {
        return entries.stream()
                      .map(DiffViews::entry)
                      .toList();
    }

    private static DiffView.Entry entry(DiffEntry entry) {
        return DiffView.Entry.entry(entry.className(), entry.name(), entry.outcome());
    }

    private static DiffView.Skip skip(SkipChange change) {
        return DiffView.Skip.skip(change.className(), change.name(), change.a(), change.b());
    }

    private static DiffView.Counts counts(TestDiff diff) {
        return DiffView.Counts.counts(diff.added().size(),
                                      diff.addedFailing(),
                                      diff.removed().size(),
                                      diff.newlyFailing().size(),
                                      diff.newlyPassing().size(),
                                      diff.skipChanged().size(),
                                      diff.duplicates());
    }

    private static DiffSide side(StoredSide stored, Instant now, Duration startBound, Set<UnitAttempt> unrecorded) {
        return DiffSide.diffSide(stored.job().id(),
                                 Views.jobState(stored.job(), now, startBound, unrecorded),
                                 stored.record().map(ResultRecord::counts),
                                 stored.testcases(),
                                 rerunUnits(stored));
    }

    /// The units `Verdicts` leaves out of the verdict, so their rows are left out of the diff too.
    private static Set<UnitName> rerunUnits(StoredSide stored) {
        return stored.job()
                     .units()
                     .stream()
                     .filter(UnitRow::rerun)
                     .map(UnitRow::name)
                     .collect(Collectors.toSet());
    }

    /// A compared side is terminal and judged ([DiffSide#obstacle]): its shown state is its verdict.
    private static DiffView.Side view(StoredSide stored, DiffSide side) {
        return DiffView.Side.side(side.job().value(),
                                  side.state(),
                                  stored.job().recipe().kind(),
                                  side.primaryRows().size());
    }

    private static DiffRefusal.SideState sideState(DiffOutcome.Side label, DiffSide a, DiffSide b) {
        var side = switch (label) {
            case A -> a;
            case B -> b;
        };

        return DiffRefusal.SideState.sideState(label,
                                               side.job().value(),
                                               side.state());
    }

    record unused() implements DiffViews {}
}
