package org.pragmatica.kiln.service.pipeline;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.pipeline.PrMeasure;
import org.pragmatica.kiln.core.pipeline.WeekRow;
import org.pragmatica.kiln.service.store.PipelineStore;


/// What the Pipeline tab and `GET /api/v1/pipeline/baseline` show (#102): the scope, the state, and every stored PR.
public record PipelineView(PipelineScope scope, PipelineStatus status, List<PipelineStore.Row> rows) {
    public PipelineView {
        rows = List.copyOf(rows);
    }

    /// No pipeline read wired (a view built without one): the scope's repository is unnamed, and nothing was read.
    public static final PipelineView NONE = new PipelineView(PipelineScope.pipelineScope("-"),
                                                             PipelineStatus.never(Instant.EPOCH),
                                                             List.of());

    public List<WeekRow> weeks() {
        return WeekRow.weeks(rows.stream()
                                 .map(row -> new WeekRow.Entry(row.number(),
                                                               row.mergedAt(),
                                                               row.measure()))
                                 .toList());
    }

    /// The measurements, newest merge first.
    public List<PrMeasure> measures() {
        return rows.reversed()
                   .stream()
                   .flatMap(row -> row.measure()
                                      .stream())
                   .toList();
    }

    public int total() {
        return rows.size();
    }

    /// PRs with a complete (final) measurement.
    public int measured() {
        return (int) rows.stream()
                         .filter(PipelineStore.Row::complete)
                         .count();
    }

    /// PRs that could not be measured ([PrMeasure#excluded]): INCOMPLETE, and left out of the week sums.
    public int excluded() {
        return (int) measures().stream()
                             .filter(PrMeasure::excluded)
                             .count();
    }

    /// Every `<workflow> / <job>` no family rule knew, over all measured PRs.
    public List<String> unknown() {
        return measures().stream()
                       .flatMap(measure -> measure.unknown()
                                                  .stream())
                       .distinct()
                       .sorted()
                       .toList();
    }
}
