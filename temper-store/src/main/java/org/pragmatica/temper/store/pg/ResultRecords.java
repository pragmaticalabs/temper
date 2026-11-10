package org.pragmatica.temper.store.pg;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.verdict.Evaluation;
import org.pragmatica.temper.domain.verdict.Verdict;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.CountsDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.EvidenceDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.FailedDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.ManifestDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.PortGroupDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.TimingsDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.UnitResultDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.UnitRow;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// The §5.4 result record built from the job's rows and its evaluation (m0-design §6.6, m1-design §2.5, §3.2):
/// `timings.queued` = first start − submit, `timings.run` = last end − first start.
///
/// - `reason` is the verdict's token (`ERROR(manifest)`); `reasonDetail` is what the unit reported after it
///   (`effective-pom rc=1`), for a VOID / ERROR reason that carries one.
/// - `manifest` is present when a unit of the job has a manifest spec: `computed`, `notReached` and
///   `unresolvedAnnotations` from the stored manifest, `missing`/`drift` from the evaluation (capped by
///   [ManifestDto]).
/// - `evidence` is `{"stored": true, "path": "<root>/jobs/<job>", "bytes": <Σ small>}` when every unit with a marker
///   has a live small tarball; otherwise `{"stored": false}` with the annotation `evidence=missing`. A job none of
///   whose units has a marker never ran anything that could leave evidence: `{"stored": false}` without the
///   annotation [G], since nothing is missing.
/// - a unit's `portGroup` is the bb-* ticket it held and since when, for a unit that held the group with a known
///   ticket.
/// - `judge` is the finishing controller's (m2-design §1.7); a job a unit of which was granted under another judge was
///   judged across a deploy and carries `judge-changed=<that grant judge7>` (ruling R-M2-11, the first such grant). A
///   job never granted has no grant judge.
/// - each `failed[]` row an active known-failure entry covers has `known = "#<issue>"`, and the record carries one
///   `KNOWN(#n)` per distinct issue, sorted, after the evaluation's own annotations [G] (m2-design §4.3). Every verdict
///   is marked, VOID and ERROR included, since their `failed` lists the observed reds; the verdict itself is unchanged.
/// - a rerun job's record (m2-design §5.3) carries `rerun-of=<parent>` and one `rerun(<class>)=…` per target after the
///   KNOWN ones, and its recipe carries `original`, the parent's outcome copied at submit. The parent's record is never
///   changed.
sealed interface ResultRecords {
    static ResultRecordDto record(JobRow job,
                                  Evaluation evaluation,
                                  Option<JobManifest> manifest,
                                  EvidenceRows.Summary evidence,
                                  Judge judge,
                                  RecordMarks marks) {
        return ResultRecordDto.resultRecordDto(job.id().value(),
                                               evaluation.verdict().kind(),
                                               evaluation.verdict().detail(),
                                               evaluation.verdict().fullReason().flatMap(Verdict::explanation),
                                               new SourceView(job.ref().map(Ref::value),
                                                              job.sha().map(Sha::value)),
                                               RecipeDto.from(job.recipe()),
                                               annotations(evaluation, evidence, judge, marks),
                                               CountsDto.countsDto(evaluation.counts()),
                                               evaluation.failed()
                                                         .stream()
                                                         .map(row -> FailedDto.failedDto(row,
                                                                                         marks.known().mark(row)))
                                                         .toList(),
                                               evaluation.observedFailures(),
                                               job.units().stream().map(ResultRecords::unitResult).toList(),
                                               manifest.map(stored -> manifestDto(stored, evaluation)),
                                               evidenceDto(evidence),
                                               timings(job),
                                               some(judge.value()));
    }

    private static List<String> annotations(Evaluation evaluation,
                                            EvidenceRows.Summary evidence,
                                            Judge judge,
                                            RecordMarks marks) {
        return Stream.of(evaluation.annotations().stream(),
                         marks.knownAnnotations().stream(),
                         marks.rerunAnnotations().stream(),
                         missingAnnotation(evidence),
                         changedAnnotation(judge,
                                           marks.grantJudges()))
                     .flatMap(annotations -> annotations)
                     .toList();
    }

    private static Stream<String> changedAnnotation(Judge judge, List<Judge> grantJudges) {
        return grantJudges.stream()
                          .filter(granted -> !granted.equals(judge))
                          .findFirst()
                          .map(Judge::changedAnnotation)
                          .stream();
    }

    private static Stream<String> missingAnnotation(EvidenceRows.Summary evidence) {
        return isMissing(evidence)
               ? Stream.of(EvidenceDto.MISSING_ANNOTATION)
               : Stream.empty();
    }

    private static ManifestDto manifestDto(JobManifest manifest, Evaluation evaluation) {
        return ManifestDto.manifestDto(manifest.computed(),
                                       evaluation.missing(),
                                       evaluation.drift(),
                                       manifest.notReached(),
                                       manifest.unresolvedAnnotations());
    }

    private static boolean isStored(EvidenceRows.Summary evidence) {
        return evidence.marked() > 0 && evidence.stored() == evidence.marked();
    }

    private static boolean isMissing(EvidenceRows.Summary evidence) {
        return evidence.stored() < evidence.marked();
    }

    private static EvidenceDto evidenceDto(EvidenceRows.Summary evidence) {
        return isStored(evidence)
               ? EvidenceDto.evidenceDto(true,
                                         evidence.tarball().map(ResultRecords::jobDirectory),
                                         some(evidence.bytes()))
               : EvidenceDto.NOT_STORED;
    }

    /// `<root>/jobs/<job>` of a tarball at `<root>/jobs/<job>/units/<unit>/a<n>/<file>` (m4-design §4.6), or at
    /// `<root>/jobs/<job>/units/<unit>/<file>` for a tarball stored before M4 (m1-design §3.1); the tarball's own
    /// directory when the path is shallower than its layout.
    static String jobDirectory(Path tarball) {
        return ancestor(tarball,
                        levels(tarball)).orElse(() -> ancestor(tarball, 1))
                       .or(tarball)
                       .toString();
    }

    /// Four levels up when the third is `units` (an attempt's directory sits below the unit's), three otherwise. Read
    /// from the layout's fixed `units` segment, never from a directory name a unit could also have (`a1`).
    private static int levels(Path tarball) {
        return ancestor(tarball, 3).flatMap(third -> option(third.getFileName()))
                       .filter(name -> name.toString()
                                           .equals("units"))
                       .map(_ -> 4)
                       .or(3);
    }

    private static Option<Path> ancestor(Path path, int levels) {
        return levels == 0
               ? some(path)
               : option(path.getParent()).flatMap(parent -> ancestor(parent, levels - 1));
    }

    private static UnitResultDto unitResult(UnitRow unit) {
        return UnitResultDto.unitResultDto(unit.name().value(),
                                           unit.rc(),
                                           unit.host().map(HostName::value),
                                           unit.imageId(),
                                           unit.startedAt(),
                                           unit.endedAt(),
                                           unit.tcLeftovers().map(TcLeftoversDto::tcLeftoversDto),
                                           portGroup(unit),
                                           attempt(unit),
                                           unit.sharedCpu(),
                                           unit.lostAttempts().stream().map(WireRecords::storedAttemptDto).toList());
    }

    /// The attempt whose result the record holds; none for a unit never granted (attempt 0, m4-design §4.1).
    private static Option<Integer> attempt(UnitRow unit) {
        return Option.some(unit.attempt()).filter(attempt -> attempt > 0);
    }

    /// Only a unit that held the group, with a known ticket, has a hold to report.
    private static Option<PortGroupDto> portGroup(UnitRow unit) {
        return unit.portGroup()
                   .filter(state -> state == PortGroup.HELD)
                   .flatMap(_ -> heldWith(unit));
    }

    private static Option<PortGroupDto> heldWith(UnitRow unit) {
        return Option.all(unit.ticket(),
                          unit.portGroupSince())
                     .map(PortGroupDto::portGroupDto);
    }

    private static TimingsDto timings(JobRow job) {
        var firstStart = earliest(job.units().stream().map(UnitRow::startedAt));
        var lastEnd = latest(job.units().stream().map(UnitRow::endedAt));

        return TimingsDto.timingsDto(firstStart.map(start -> Duration.between(job.submittedAt(), start)),
                                     Option.all(firstStart, lastEnd).map(Duration::between));
    }

    private static Option<Instant> earliest(Stream<Option<Instant>> instants) {
        return Option.from(instants.flatMap(Option::stream).min(Comparator.naturalOrder()));
    }

    private static Option<Instant> latest(Stream<Option<Instant>> instants) {
        return Option.from(instants.flatMap(Option::stream).max(Comparator.naturalOrder()));
    }

    record unused() implements ResultRecords {}
}
