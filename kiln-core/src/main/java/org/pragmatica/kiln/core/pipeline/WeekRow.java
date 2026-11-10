package org.pragmatica.kiln.core.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;


/// One ISO week (UTC, by `mergedAt`) of the pipeline baseline (#102): `prs` merged that week, `measured` of them with
/// a measurement (of which `complete` final, and of those `incomplete` given up with runs that never completed or
/// `excluded`), and the sums over the complete ones: an INCOMPLETE PR (`incomplete` = `excluded`: no measurement possible, or runs given up
/// on) is counted, never summed. A week whose PRs are not all measured is a partial sum, and says so by
/// `measured < prs`.
public record WeekRow(String week,
                      int prs,
                      int measured,
                      int complete,
                      int incomplete,
                      int excluded,
                      long totalSeconds,
                      long prSeconds,
                      long queueSeconds,
                      long rerunSeconds,
                      long fullReactorSeconds,
                      long emberSeconds,
                      long forgeSeconds,
                      long consensusSeconds,
                      long otherSeconds,
                      long removals,
                      long flakes,
                      long realFailures,
                      long conflicts,
                      long manual,
                      long unclassified,
                      long baseSyncs,
                      long otherMerges,
                      long syncSeconds) {
    /// One listed PR: its merge time, and its measurement once there is one.
    public record Entry(long number, Instant mergedAt, Option<PrMeasure> measure) {}

    /// One row per week with a merged PR, oldest first.
    public static List<WeekRow> weeks(List<Entry> entries) {
        return entries.stream()
                      .collect(Collectors.groupingBy(entry -> PrMeasure.week(entry.mergedAt()),
                                                     TreeMap::new,
                                                     Collectors.toList()))
                      .entrySet()
                      .stream()
                      .map(WeekRow::row)
                      .toList();
    }

    private static WeekRow row(Map.Entry<String, List<Entry>> week) {
        var recorded = week.getValue().stream().flatMap(entry -> entry.measure()
                                                                      .stream()).toList();
        var measures = recorded.stream().filter(measure -> !measure.excluded()).toList();

        return new WeekRow(week.getKey(),
                           week.getValue().size(),
                           recorded.size(),
                           (int) recorded.stream().filter(measure -> measure.complete() && !measure.excluded()).count(),
                           (int) recorded.stream().filter(PrMeasure::incomplete).count(),
                           (int) recorded.stream().filter(PrMeasure::excluded).count(),
                           sum(measures, PrMeasure::totalSeconds),
                           sum(measures, PrMeasure::prSeconds),
                           sum(measures, PrMeasure::queueSeconds),
                           sum(measures, PrMeasure::rerunSeconds),
                           sum(measures, PrMeasure::fullReactorSeconds),
                           sum(measures, PrMeasure::emberSeconds),
                           sum(measures, PrMeasure::forgeSeconds),
                           sum(measures, PrMeasure::consensusSeconds),
                           sum(measures, PrMeasure::otherSeconds),
                           sum(measures,
                               measure -> measure.removals()
                                                 .size()),
                           sum(measures, measure -> measure.removalCount(Removal.Kind.FLAKE)),
                           sum(measures, measure -> measure.removalCount(Removal.Kind.REAL_FAILURE)),
                           sum(measures, measure -> measure.removalCount(Removal.Kind.CONFLICT)),
                           sum(measures, measure -> measure.removalCount(Removal.Kind.MANUAL)),
                           sum(measures, measure -> measure.removalCount(Removal.Kind.UNCLASSIFIED)),
                           sum(measures, PrMeasure::baseSyncs),
                           sum(measures, PrMeasure::otherMerges),
                           sum(measures, PrMeasure::syncSeconds));
    }

    private static long sum(List<PrMeasure> measures, ToLongFunction<PrMeasure> field) {
        return measures.stream()
                       .mapToLong(field)
                       .sum();
    }
}
