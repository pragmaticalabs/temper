package org.pragmatica.temper.protocol.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;

import com.fasterxml.jackson.annotation.JsonProperty;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// The immutable result record (spec §5.4): `GET /jobs/{id}/result` and `results.record`. `manifest` (M1,
/// m1-design §2.5) is present when a unit of the job had a manifest spec. `reason` is the verdict's token
/// (`manifest`); `reasonDetail` is the free text a VOID / ERROR reason carried after it (`effective-pom rc=1`). `judge`
/// (M2, m2-design §1.7) is the build of the controller that wrote the record; a record written before the judge existed
/// has none. `userProperties` (M4, maintainer ruling Q-M4-11c) are the `-D` properties the user wrote, those that skip or narrow
/// the tests marked ([UserPropertyDto]): derived from `recipe` by [#resultRecordDto], which builds every record the
/// controller writes or serves, and for a JSON without the field, so a record written before it shows them too.
public record ResultRecordDto(long job,
                              String verdict,
                              Option<String> reason,
                              Option<String> reasonDetail,
                              SourceView source,
                              RecipeDto recipe,
                              List<String> annotations,
                              CountsDto counts,
                              List<FailedDto> failed,
                              int observedFailures,
                              List<UnitResultDto> units,
                              Option<ManifestDto> manifest,
                              EvidenceDto evidence,
                              TimingsDto timings,
                              Option<String> judge,
                              List<UserPropertyDto> userProperties) {
    public static ResultRecordDto resultRecordDto(long job,
                                                  String verdict,
                                                  Option<String> reason,
                                                  Option<String> reasonDetail,
                                                  SourceView source,
                                                  RecipeDto recipe,
                                                  List<String> annotations,
                                                  CountsDto counts,
                                                  List<FailedDto> failed,
                                                  int observedFailures,
                                                  List<UnitResultDto> units,
                                                  Option<ManifestDto> manifest,
                                                  EvidenceDto evidence,
                                                  TimingsDto timings,
                                                  Option<String> judge) {
        return new ResultRecordDto(job,
                                   verdict,
                                   reason,
                                   reasonDetail,
                                   source,
                                   recipe,
                                   annotations,
                                   counts,
                                   failed,
                                   observedFailures,
                                   units,
                                   manifest,
                                   evidence,
                                   timings,
                                   judge,
                                   UserPropertyDto.of(recipe));
    }

    public ResultRecordDto {
        annotations = List.copyOf(annotations);
        userProperties = option(userProperties).map(List::copyOf).or(() -> UserPropertyDto.of(recipe));
        failed = List.copyOf(failed);
        units = List.copyOf(units);
    }

    /// `counts`; `manifest`, `missing` and `drift` are `null` without a manifest, and are the uncapped sizes of
    /// the [ManifestDto] lists otherwise.
    public record CountsDto(int testcases,
                            int passed,
                            int failed,
                            int error,
                            int skipped,
                            long testsAttr,
                            int classes,
                            Option<Integer> manifest,
                            Option<Integer> missing,
                            Option<Integer> drift) {
        public static CountsDto countsDto(Counts totals) {
            return new CountsDto(totals.testcases(),
                                 totals.passed(),
                                 totals.failed(),
                                 totals.error(),
                                 totals.skipped(),
                                 totals.testsAttr(),
                                 totals.classes(),
                                 totals.manifest(),
                                 totals.missing(),
                                 totals.drift());
        }
    }

    /// One failed or error testcase; `known` is the KNOWN registry issue (M2, m2-design §4.3): `#<issue>` when an entry
    /// active in the completing transaction covered it.
    public record FailedDto(@JsonProperty("class") String className, String name, Option<String> known) {
        public static FailedDto failedDto(TestcaseRow row, Option<String> known) {
            return new FailedDto(row.className(), row.name(), known);
        }
    }

    /// One unit: `id` is the unit name, `image` the runner image id, `tcLeftovers` the Testcontainers reap of its
    /// end facts (protocol v1 addition, m0-design §12.7; absent for a unit that ended without end facts),
    /// `portGroup` the bb-* ticket it held and since when (M1; absent for a unit that never held the group). M4
    /// (m4-design §9.4, §11): `attempt` is the attempt whose result this is (absent for a unit never granted);
    /// `sharedCpu` whether it ran on a shared-CPU host, so its wall times and `memory.peak` are not comparable with a
    /// dedicated host's (R-M4-9); `lostAttempts` the earlier attempts lost with their host, oldest first (§2.3). A record
    /// written before M4 has neither attempt nor `sharedCpu`, and no lost attempts.
    public record UnitResultDto(String id,
                                Option<Integer> rc,
                                Option<String> host,
                                Option<String> image,
                                Option<Instant> start,
                                Option<Instant> end,
                                Option<TcLeftoversDto> tcLeftovers,
                                Option<PortGroupDto> portGroup,
                                Option<Integer> attempt,
                                Option<Boolean> sharedCpu,
                                List<LostAttemptDto> lostAttempts) {
        public UnitResultDto {
            lostAttempts = option(lostAttempts).map(List::copyOf).or(List.of());
        }

        /// A unit as a record before M4 has it: no attempt, CPU class or lost attempts.
        public static UnitResultDto unitResultDto(String id,
                                                  Option<Integer> rc,
                                                  Option<String> host,
                                                  Option<String> image,
                                                  Option<Instant> start,
                                                  Option<Instant> end,
                                                  Option<TcLeftoversDto> tcLeftovers,
                                                  Option<PortGroupDto> portGroup) {
            return unitResultDto(id, rc, host, image, start, end, tcLeftovers, portGroup, none(), none(), List.of());
        }

        public static UnitResultDto unitResultDto(String id,
                                                  Option<Integer> rc,
                                                  Option<String> host,
                                                  Option<String> image,
                                                  Option<Instant> start,
                                                  Option<Instant> end,
                                                  Option<TcLeftoversDto> tcLeftovers,
                                                  Option<PortGroupDto> portGroup,
                                                  Option<Integer> attempt,
                                                  Option<Boolean> sharedCpu,
                                                  List<LostAttemptDto> lostAttempts) {
            return new UnitResultDto(id,
                                     rc,
                                     host,
                                     image,
                                     start,
                                     end,
                                     tcLeftovers,
                                     portGroup,
                                     attempt,
                                     sharedCpu,
                                     lostAttempts);
        }
    }

    /// The port-group hold of a unit: the bb-* ticket and when it was granted.
    public record PortGroupDto(String ticket, Instant heldFrom) {
        public static PortGroupDto portGroupDto(String ticket, Instant heldFrom) {
            return new PortGroupDto(ticket, heldFrom);
        }
    }

    /// `manifest` (m1-design §2.5): `computed` is true when every unit with a manifest spec delivered a computed
    /// manifest; `missing` (expected, not reported) and `drift` (reported, not expected) are sorted and capped at
    /// [#MAX_LISTED] entries each, their full sizes being `counts.missing` and `counts.drift`; `notReached`
    /// (modules whose tests never compiled) is sorted and NOT capped, because it lists modules, not classes, and
    /// has no count of its own; `unresolvedAnnotations` counts the annotation types the rules could not resolve.
    public record ManifestDto(boolean computed,
                              List<String> missing,
                              List<String> drift,
                              List<String> notReached,
                              int unresolvedAnnotations) {
        public static final int MAX_LISTED = 500;

        public ManifestDto {
            missing = List.copyOf(missing);
            drift = List.copyOf(drift);
            notReached = List.copyOf(notReached);
        }

        public static ManifestDto manifestDto(boolean computed,
                                              Collection<String> missing,
                                              Collection<String> drift,
                                              Collection<String> notReached,
                                              int unresolvedAnnotations) {
            return new ManifestDto(computed,
                                   listed(missing),
                                   listed(drift),
                                   notReached.stream().sorted().toList(),
                                   unresolvedAnnotations);
        }

        private static List<String> listed(Collection<String> names) {
            return names.stream()
                        .sorted()
                        .limit(MAX_LISTED)
                        .toList();
        }
    }

    /// Evidence location (m1-design §3.2): `{"stored": true, "path": "<root>/jobs/<job>", "bytes": <Σ small>}` when
    /// every unit with a marker has its small tarball on the controller; otherwise [#NOT_STORED], and the record
    /// carries the `evidence=missing` annotation.
    public record EvidenceDto(boolean stored, Option<String> path, Option<Long> bytes) {
        /// The annotation of a record whose evidence is not stored (design §6, R3).
        public static final String MISSING_ANNOTATION = "evidence=missing";
        public static final EvidenceDto NOT_STORED = new EvidenceDto(false, none(), none());

        public static EvidenceDto evidenceDto(boolean stored, Option<String> path, Option<Long> bytes) {
            return new EvidenceDto(stored, path, bytes);
        }
    }

    /// `queued` = first start − submit; `run` = last end − first start. Absent when no unit started.
    public record TimingsDto(Option<Duration> queued, Option<Duration> run) {
        public static TimingsDto timingsDto(Option<Duration> queued, Option<Duration> run) {
            return new TimingsDto(queued, run);
        }
    }
}
