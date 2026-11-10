package org.pragmatica.kiln.service.api;

import java.util.List;

import org.pragmatica.kiln.core.pipeline.PrMeasure;
import org.pragmatica.kiln.core.pipeline.Removal;
import org.pragmatica.kiln.core.pipeline.WeekRow;
import org.pragmatica.kiln.service.pipeline.PipelineView;


/// `GET /api/v1/pipeline/baseline` (#102): the scope, the state (`kind`, `reason`, `since`, `lastOk`; a read that failed
/// is never shown as OK), "measured of total", the weekly rows (ISO weeks, UTC, by merge time; oldest first) and the
/// per-PR rows (newest merge first). Machine-hours have two decimals. `unknownNames` lists every `<workflow> / <job>` no
/// family rule knew, filed under `other`. `syncMerges` hours are the PR runs triggered by a base sync (a merge commit whose
/// headline names the base). A PR given up with runs that never completed has `incompleteRuns` > 0 and `state` INCOMPLETE
/// (else COMPLETE, or IN_PROGRESS while it is still read); a week counts those PRs in `incomplete`. Only complete measurements are summed: both INCOMPLETE kinds (a timeline longer than one page, with no hours; runs given
/// up on, with the hours read) are left out of the week sums, each with `incompleteKind` and `incompleteReason`; `excluded`
/// counts them in the whole and per week, and `excludedPrs` lists each with its kind and reason.
/// A removal's `kind` is FLAKE, REAL_FAILURE, CONFLICT, MANUAL (removed by hand: neither a flake nor a failure) or
/// UNCLASSIFIED, and a week counts each kind apart.
public record PipelineJson(Scope scope,
                           State state,
                           int measured,
                           int total,
                           int excluded,
                           List<ExcludedPr> excludedPrs,
                           List<Week> weeks,
                           List<Pr> prs,
                           List<String> unknownNames) {
    public record Scope(String repo, String base, String since) {}

    /// An INCOMPLETE PR left out of the sums: its `kind` ([PrMeasure#KIND_TIMELINE] or [PrMeasure#KIND_RUNS]) and `reason`.
    public record ExcludedPr(long number, String kind, String reason) {}

    public record State(String kind, String reason, String since, String lastOk) {}

    public record Hours(double total,
                        double prRuns,
                        double queueFirstEntry,
                        double afterRemoval,
                        double fullReactor,
                        double ember,
                        double forge,
                        double consensus,
                        double other,
                        double syncMerges) {}

    public record Week(String week,
                       int prs,
                       int measured,
                       int complete,
                       int incomplete,
                       int excluded,
                       Hours hours,
                       long removals,
                       long flakes,
                       long realFailures,
                       long conflicts,
                       long manual,
                       long unclassified,
                       long baseSyncs,
                       long otherMerges) {}

    public record RemovalJson(String at, String reason, String kind, String detail) {}

    public record Pr(long number,
                     String title,
                     String mergedAt,
                     String week,
                     boolean complete,
                     String state,
                     int incompleteRuns,
                     String incompleteKind,
                     String incompleteReason,
                     int runs,
                     int attempts,
                     Hours hours,
                     List<RemovalJson> removals,
                     int baseSyncs,
                     int otherMerges,
                     List<String> unknownNames,
                     boolean commitsTruncated) {}

    public static PipelineJson pipelineJson(PipelineView view) {
        var scope = view.scope();
        var status = view.status();

        return new PipelineJson(new Scope(scope.repo(),
                                          scope.base(),
                                          scope.since().toString()),
                                new State(status.kind().name(),
                                          status.reason(),
                                          status.since().toString(),
                                          status.lastOk().map(Object::toString).or("never")),
                                view.measured(),
                                view.total(),
                                view.excluded(),
                                view.measures()
                                    .stream()
                                    .filter(PrMeasure::excluded)
                                    .map(measure -> new ExcludedPr(measure.number(),
                                                                   measure.excludedKind(),
                                                                   measure.why()))
                                    .toList(),
                                view.weeks().stream().map(PipelineJson::week).toList(),
                                view.measures().stream().map(PipelineJson::pr).toList(),
                                view.unknown());
    }

    private static Week week(WeekRow row) {
        return new Week(row.week(),
                        row.prs(),
                        row.measured(),
                        row.complete(),
                        row.incomplete(),
                        row.excluded(),
                        new Hours(hours(row.totalSeconds()),
                                  hours(row.prSeconds()),
                                  hours(row.queueSeconds()),
                                  hours(row.rerunSeconds()),
                                  hours(row.fullReactorSeconds()),
                                  hours(row.emberSeconds()),
                                  hours(row.forgeSeconds()),
                                  hours(row.consensusSeconds()),
                                  hours(row.otherSeconds()),
                                  hours(row.syncSeconds())),
                        row.removals(),
                        row.flakes(),
                        row.realFailures(),
                        row.conflicts(),
                        row.manual(),
                        row.unclassified(),
                        row.baseSyncs(),
                        row.otherMerges());
    }

    private static Pr pr(PrMeasure measure) {
        return new Pr(measure.number(),
                      measure.title(),
                      measure.mergedAt().toString(),
                      measure.week(),
                      measure.complete(),
                      state(measure),
                      measure.incompleteRuns(),
                      measure.excludedKind(),
                      measure.why(),
                      measure.runs(),
                      measure.attempts(),
                      new Hours(hours(measure.totalSeconds()),
                                hours(measure.prSeconds()),
                                hours(measure.queueSeconds()),
                                hours(measure.rerunSeconds()),
                                hours(measure.fullReactorSeconds()),
                                hours(measure.emberSeconds()),
                                hours(measure.forgeSeconds()),
                                hours(measure.consensusSeconds()),
                                hours(measure.otherSeconds()),
                                hours(measure.syncSeconds())),
                      measure.removals().stream().map(PipelineJson::removal).toList(),
                      measure.baseSyncs(),
                      measure.otherMerges(),
                      measure.unknown(),
                      measure.truncated());
    }

    static String state(PrMeasure measure) {
        return measure.incomplete()
               ? "INCOMPLETE"
               : measure.complete()
                 ? "COMPLETE"
                 : "IN_PROGRESS";
    }

    private static RemovalJson removal(Removal removal) {
        return new RemovalJson(removal.at().toString(),
                               removal.reason(),
                               removal.kind().name(),
                               removal.detail());
    }

    static double hours(long seconds) {
        return Math.round(seconds / 36.0) / 100.0;
    }
}
