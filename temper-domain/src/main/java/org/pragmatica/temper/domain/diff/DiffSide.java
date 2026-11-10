package org.pragmatica.temper.domain.diff;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Counts;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One side of a diff as one read snapshot holds it (m2-design §3.1, §3.2): the job, its state as shown, its record's
/// counts once it is terminal (a `results` row exists), every testcase row stored for it, and its rerun units.
///
/// Only the **primary** rows count: those of a unit that is not a rerun unit (`units.rerun`). That is the source
/// `Verdicts` uses (`primaryUnits` drops rerun units, and a unit's rows are the rows attributed to it), so the rows
/// compared are the rows the verdict judged and `counts.testcases` counted, and the pruned check compares like with
/// like. M2 writes no rerun unit (F9), so today every row is primary.
public record DiffSide(JobId job,
                       JobState state,
                       Option<Counts> counts,
                       List<TestcaseRow> rows,
                       Set<UnitName> rerunUnits) {
    public DiffSide {
        rows = List.copyOf(rows);
        rerunUnits = Set.copyOf(rerunUnits);
    }

    public static DiffSide diffSide(JobId job,
                                    JobState state,
                                    Option<Counts> counts,
                                    List<TestcaseRow> rows,
                                    Set<UnitName> rerunUnits) {
        return new DiffSide(job, state, counts, rows, rerunUnits);
    }

    /// A side without rerun units.
    public static DiffSide diffSide(JobId job, JobState state, Option<Counts> counts, List<TestcaseRow> rows) {
        return new DiffSide(job, state, counts, rows, Set.of());
    }

    public List<TestcaseRow> primaryRows() {
        return rows.stream()
                   .filter(row -> !rerunUnits.contains(row.unit()))
                   .toList();
    }

    /// What keeps this side out of a diff, if anything: NOT_JUDGED (VOID, ERROR, CANCELLED, R-M2-13; or REFUSED, never
    /// ran), NOT_TERMINAL (no record yet), or PRUNED (fewer primary rows than the record counts: retention deleted some,
    /// F16; the write-once completing transaction rules out "never stored").
    public Option<DiffObstacle> obstacle() {
        return isNotJudged()
               ? some(DiffObstacle.NOT_JUDGED)
               : counts.map(this::storedObstacle)
                       .or(some(DiffObstacle.NOT_TERMINAL));
    }

    private boolean isNotJudged() {
        return switch (state) {
            case VOID, ERROR, CANCELLED, REFUSED -> true;
            case PASSED, FAILED, EMPTY, QUEUED, UNKNOWN, RUNNING, CANCELLING, SUPERSEDED -> false;
        };
    }

    private Option<DiffObstacle> storedObstacle(Counts recorded) {
        return primaryRows().size() < recorded.testcases()
               ? some(DiffObstacle.PRUNED)
               : none();
    }
}
