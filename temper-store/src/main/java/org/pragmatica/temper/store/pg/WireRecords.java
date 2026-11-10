package org.pragmatica.temper.store.pg;

import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.api.EventKind;
import org.pragmatica.temper.protocol.api.LostAttemptDto;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.CountsDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.LostAttempt;
import org.pragmatica.temper.store.api.ResultRecord;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// The store port's models to and from their wire forms (ruling R-10): the result record, its counts and the event
/// kinds. Both directions are member-for-member copies, so a record read from `results.record` and written out again
/// is the same JSON, and the jsonb and wire shapes stay defined by the protocol DTOs alone. The controller's HTTP and
/// event adapters use the outbound direction.
///
/// Reading fails, rather than carrying a `null`, when a stored record lacks `source`, `recipe`, `counts`,
/// `evidence` or `timings`, and reads an absent `tcLeftovers.containers` as empty (as `TcLeftoversDto.toDomain`
/// does): the store never writes such a record (`ResultRecords` sets every member).
public sealed interface WireRecords {
    // ---- result record, wire to model ----------------------------------------------------------------------------
    static Result<ResultRecord> resultRecord(ResultRecordDto dto) {
        return Result.all(option(dto.source()).toResult(invalid("source").apply("missing")),
                          option(dto.recipe()).toResult(invalid("recipe").apply("missing")),
                          option(dto.counts()).toResult(invalid("counts").apply("missing")),
                          option(dto.evidence()).toResult(invalid("evidence").apply("missing")),
                          option(dto.timings()).toResult(invalid("timings").apply("missing")))
                     .map((source, recipe, counts, evidence, timings) -> resultRecord(dto,
                                                                                      source,
                                                                                      recipe,
                                                                                      counts,
                                                                                      evidence,
                                                                                      timings));
    }

    private static ResultRecord resultRecord(ResultRecordDto dto,
                                             SourceView source,
                                             RecipeDto recipe,
                                             CountsDto counts,
                                             ResultRecordDto.EvidenceDto evidence,
                                             ResultRecordDto.TimingsDto timings) {
        return ResultRecord.resultRecord(dto.job(),
                                         dto.verdict(),
                                         dto.reason(),
                                         dto.reasonDetail(),
                                         ResultRecord.Source.source(source.ref(), source.sha()),
                                         recipe(recipe),
                                         dto.annotations(),
                                         counts(counts),
                                         dto.failed().stream().map(WireRecords::failed).toList(),
                                         dto.observedFailures(),
                                         dto.units().stream().map(WireRecords::unitResult).toList(),
                                         dto.manifest().map(WireRecords::manifest),
                                         ResultRecord.Evidence.evidence(evidence.stored(),
                                                                        evidence.path(),
                                                                        evidence.bytes()),
                                         ResultRecord.Timings.timings(timings.queued(), timings.run()),
                                         dto.judge());
    }

    static Counts counts(CountsDto dto) {
        return Counts.counts(dto.testcases(),
                             dto.passed(),
                             dto.failed(),
                             dto.error(),
                             dto.skipped(),
                             dto.testsAttr(),
                             dto.classes(),
                             dto.manifest(),
                             dto.missing(),
                             dto.drift());
    }

    private static ResultRecord.Recipe recipe(RecipeDto dto) {
        return switch (dto) {
            case RecipeDto.Mvn mvn -> ResultRecord.Recipe.Mvn.mvn(mvn.args());
            case RecipeDto.ModuleTest moduleTest -> ResultRecord.Recipe.ModuleTest.moduleTest(moduleTest.pl(),
                                                                                              moduleTest.test(),
                                                                                              moduleTest.itTest());
            case RecipeDto.Gate _ -> ResultRecord.Recipe.Gate.gate();
            case RecipeDto.Mutate mutate -> ResultRecord.Recipe.Mutate.mutate(mutate.spec());
            case RecipeDto.Rerun rerun -> ResultRecord.Recipe.Rerun.rerun(rerun.of(),
                                                                          option(rerun.classes()).or(List.of()).stream().map(WireRecords::target).toList(),
                                                                          rerun.original().map(WireRecords::original),
                                                                          rerun.bound().map(WireRecords::bound));
        };
    }

    private static ResultRecord.Recipe.Rerun.Bound bound(RecipeDto.Rerun.BoundDto dto) {
        return ResultRecord.Recipe.Rerun.Bound.bound(dto.millis(), dto.derivation());
    }

    private static RecipeDto.Rerun.BoundDto boundDto(ResultRecord.Recipe.Rerun.Bound bound) {
        return RecipeDto.Rerun.BoundDto.boundDto(bound.millis(), bound.derivation());
    }

    private static ResultRecord.Recipe.Rerun.Target target(RecipeDto.Rerun.TargetDto dto) {
        return ResultRecord.Recipe.Rerun.Target.target(dto.className(), dto.module(), dto.runner());
    }

    private static ResultRecord.Recipe.Rerun.Original original(RecipeDto.Rerun.OriginalDto dto) {
        return ResultRecord.Recipe.Rerun.Original.original(dto.job(),
                                                           dto.verdict(),
                                                           dto.reason(),
                                                           option(dto.failed()).or(List.of())
                                                                 .stream()
                                                                 .map(WireRecords::failed)
                                                                 .toList());
    }

    private static ResultRecord.Failed failed(ResultRecordDto.FailedDto dto) {
        return ResultRecord.Failed.failed(dto.className(), dto.name(), dto.known());
    }

    private static ResultRecord.UnitResult unitResult(ResultRecordDto.UnitResultDto dto) {
        return ResultRecord.UnitResult.unitResult(dto.id(),
                                                  dto.rc(),
                                                  dto.host(),
                                                  dto.image(),
                                                  dto.start(),
                                                  dto.end(),
                                                  dto.tcLeftovers().map(WireRecords::tcLeftovers),
                                                  dto.portGroup().map(WireRecords::portGroup),
                                                  dto.attempt(),
                                                  dto.sharedCpu(),
                                                  dto.lostAttempts().stream().map(WireRecords::lostAttempt).toList());
    }

    private static ResultRecord.LostRun lostAttempt(LostAttemptDto dto) {
        return ResultRecord.LostRun.lostRun(dto.attempt(), dto.host(), dto.grantedAt(), dto.startedAt(), dto.endedAt());
    }

    private static ResultRecord.TcLeftovers tcLeftovers(TcLeftoversDto dto) {
        return ResultRecord.TcLeftovers.tcLeftovers(option(dto.containers()).or(List.of())
                                                          .stream()
                                                          .map(WireRecords::container)
                                                          .toList(),
                                                    dto.unverified());
    }

    private static ResultRecord.Container container(TcLeftoversDto.ContainerDto dto) {
        return ResultRecord.Container.container(dto.id(), dto.session());
    }

    private static ResultRecord.PortGroup portGroup(ResultRecordDto.PortGroupDto dto) {
        return ResultRecord.PortGroup.portGroup(dto.ticket(), dto.heldFrom());
    }

    private static ResultRecord.Manifest manifest(ResultRecordDto.ManifestDto dto) {
        return ResultRecord.Manifest.manifest(dto.computed(),
                                              dto.missing(),
                                              dto.drift(),
                                              dto.notReached(),
                                              dto.unresolvedAnnotations());
    }

    // ---- result record, model to wire ----------------------------------------------------------------------------
    static ResultRecordDto resultRecordDto(ResultRecord record) {
        return ResultRecordDto.resultRecordDto(record.job(),
                                               record.verdict(),
                                               record.reason(),
                                               record.reasonDetail(),
                                               new SourceView(record.source().ref(),
                                                              record.source().sha()),
                                               recipeDto(record.recipe()),
                                               record.annotations(),
                                               CountsDto.countsDto(record.counts()),
                                               record.failed().stream().map(WireRecords::failedDto).toList(),
                                               record.observedFailures(),
                                               record.units().stream().map(WireRecords::unitResultDto).toList(),
                                               record.manifest().map(WireRecords::manifestDto),
                                               ResultRecordDto.EvidenceDto.evidenceDto(record.evidence().stored(),
                                                                                       record.evidence().path(),
                                                                                       record.evidence().bytes()),
                                               ResultRecordDto.TimingsDto.timingsDto(record.timings().queued(),
                                                                                     record.timings().run()),
                                               record.judge());
    }

    private static RecipeDto recipeDto(ResultRecord.Recipe recipe) {
        return switch (recipe) {
            case ResultRecord.Recipe.Mvn mvn -> new RecipeDto.Mvn(mvn.args());
            case ResultRecord.Recipe.ModuleTest moduleTest -> new RecipeDto.ModuleTest(moduleTest.pl(),
                                                                                       moduleTest.test(),
                                                                                       moduleTest.itTest());
            case ResultRecord.Recipe.Gate _ -> new RecipeDto.Gate();
            case ResultRecord.Recipe.Mutate mutate -> new RecipeDto.Mutate(mutate.spec());
            case ResultRecord.Recipe.Rerun rerun -> new RecipeDto.Rerun(rerun.of(),
                                                                        rerun.classes().stream().map(WireRecords::targetDto).toList(),
                                                                        rerun.original().map(WireRecords::originalDto),
                                                                        rerun.bound().map(WireRecords::boundDto));
        };
    }

    private static RecipeDto.Rerun.TargetDto targetDto(ResultRecord.Recipe.Rerun.Target target) {
        return new RecipeDto.Rerun.TargetDto(target.className(), target.module(), target.runner());
    }

    private static RecipeDto.Rerun.OriginalDto originalDto(ResultRecord.Recipe.Rerun.Original original) {
        return new RecipeDto.Rerun.OriginalDto(original.job(),
                                               original.verdict(),
                                               original.reason(),
                                               original.failed().stream().map(WireRecords::failedDto).toList());
    }

    private static ResultRecordDto.FailedDto failedDto(ResultRecord.Failed failed) {
        return new ResultRecordDto.FailedDto(failed.className(), failed.name(), failed.known());
    }

    private static ResultRecordDto.UnitResultDto unitResultDto(ResultRecord.UnitResult unit) {
        return ResultRecordDto.UnitResultDto.unitResultDto(unit.id(),
                                                           unit.rc(),
                                                           unit.host(),
                                                           unit.image(),
                                                           unit.start(),
                                                           unit.end(),
                                                           unit.tcLeftovers().map(WireRecords::tcLeftoversDto),
                                                           unit.portGroup().map(WireRecords::portGroupDto),
                                                           unit.attempt(),
                                                           unit.sharedCpu(),
                                                           unit.lostAttempts()
                                                               .stream()
                                                               .map(WireRecords::lostAttemptDto)
                                                               .toList());
    }

    private static LostAttemptDto lostAttemptDto(ResultRecord.LostRun attempt) {
        return LostAttemptDto.lostAttemptDto(attempt.attempt(),
                                             attempt.host(),
                                             attempt.grantedAt(),
                                             attempt.startedAt(),
                                             attempt.endedAt());
    }

    /// A stored lost attempt ([LostAttempt], a `unit_attempts` row) in its wire form: the job view's and the result
    /// record's `lostAttempts` (m4-design §11).
    static LostAttemptDto storedAttemptDto(LostAttempt attempt) {
        return LostAttemptDto.lostAttemptDto(attempt.attempt(),
                                             attempt.host().value(),
                                             attempt.grantedAt(),
                                             attempt.startedAt(),
                                             attempt.endedAt());
    }

    private static TcLeftoversDto tcLeftoversDto(ResultRecord.TcLeftovers leftovers) {
        return new TcLeftoversDto(leftovers.containers().stream().map(WireRecords::containerDto).toList(),
                                  leftovers.unverified());
    }

    private static TcLeftoversDto.ContainerDto containerDto(ResultRecord.Container container) {
        return new TcLeftoversDto.ContainerDto(container.id(), container.session());
    }

    private static ResultRecordDto.PortGroupDto portGroupDto(ResultRecord.PortGroup portGroup) {
        return ResultRecordDto.PortGroupDto.portGroupDto(portGroup.ticket(), portGroup.heldFrom());
    }

    private static ResultRecordDto.ManifestDto manifestDto(ResultRecord.Manifest manifest) {
        return new ResultRecordDto.ManifestDto(manifest.computed(),
                                               manifest.missing(),
                                               manifest.drift(),
                                               manifest.notReached(),
                                               manifest.unresolvedAnnotations());
    }

    // ---- event kinds ---------------------------------------------------------------------------------------------
    static EventKind eventKind(EventRow.Kind kind) {
        return switch (kind) {
            case QUEUED -> EventKind.QUEUED;
            case STARTED -> EventKind.STARTED;
            case START_UNKNOWN -> EventKind.START_UNKNOWN;
            case UNIT_FINISHED -> EventKind.UNIT_FINISHED;
            case FINISHED -> EventKind.FINISHED;
            case CANCELLED -> EventKind.CANCELLED;
            case SUPERSEDED -> EventKind.SUPERSEDED;
            case UNIT_LOST -> EventKind.UNIT_LOST;
        };
    }

    static EventRow.Kind kind(EventKind wire) {
        return switch (wire) {
            case QUEUED -> EventRow.Kind.QUEUED;
            case STARTED -> EventRow.Kind.STARTED;
            case START_UNKNOWN -> EventRow.Kind.START_UNKNOWN;
            case UNIT_FINISHED -> EventRow.Kind.UNIT_FINISHED;
            case FINISHED -> EventRow.Kind.FINISHED;
            case CANCELLED -> EventRow.Kind.CANCELLED;
            case SUPERSEDED -> EventRow.Kind.SUPERSEDED;
            case UNIT_LOST -> EventRow.Kind.UNIT_LOST;
        };
    }

    record unused() implements WireRecords {}
}
