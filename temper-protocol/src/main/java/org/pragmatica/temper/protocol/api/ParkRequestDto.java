package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.task.ParkRequest;
import org.pragmatica.temper.domain.task.RulingKind;
import org.pragmatica.temper.domain.task.TaskId;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// A NEEDS-RULING request on the wire and in `task_rulings.request` (m3-design §5.5): `{kind, question, options: [{id,
/// text}], recommendation: {option, why, temperDefault?}, blocked: {tasks, text}, evidence?, items?}`. [#requested]
/// parses what a worker or the maintainer sends (`POST tasks/{id}/park`), which may not use a temper-raised kind;
/// [#toDomain] reads back a stored request of any origin.
public record ParkRequestDto(String kind,
                             String question,
                             List<OptionDto> options,
                             RecommendationDto recommendation,
                             BlockedDto blocked,
                             Option<List<Long>> evidence,
                             Option<List<String>> items) {
    public record OptionDto(String id, String text) {
        Result<ParkRequest.Choice> toDomain() {
            return ParkRequest.Choice.choice(id, text);
        }
    }

    public record RecommendationDto(String option, String why, Option<Boolean> temperDefault) {
        Result<ParkRequest.Recommendation> toDomain() {
            return ParkRequest.Recommendation.recommendation(option, why, temperDefault.or(false));
        }
    }

    public record BlockedDto(List<Long> tasks, String text) {
        Result<ParkRequest.Blocked> toDomain() {
            return Result.allOf(Option.option(tasks).or(List.of()).stream().map(TaskId::taskId).toList()).flatMap(ids -> ParkRequest.Blocked.blocked(ids,
                                                                                                                                                     text));
        }
    }

    public static ParkRequestDto parkRequestDto(ParkRequest request) {
        return new ParkRequestDto(request.kind().wire(),
                                  request.question(),
                                  request.options()
                                         .stream()
                                         .map(choice -> new OptionDto(choice.id(),
                                                                      choice.text()))
                                         .toList(),
                                  new RecommendationDto(request.recommendation().option(),
                                                        request.recommendation().why(),
                                                        some(request.recommendation().temperDefault())),
                                  new BlockedDto(request.blocked().tasks().stream().map(TaskId::value).toList(),
                                                 request.blocked().text()),
                                  some(request.evidence()),
                                  some(request.items()));
    }

    /// A worker's or the maintainer's request (T10): no temper-raised kind, no `temperDefault`, no facts of temper's.
    public Result<ParkRequest> requested() {
        return parts().flatMap(parts -> ParkRequest.requested(parts.kind(),
                                                              question,
                                                              parts.options(),
                                                              parts.recommendation(),
                                                              parts.blocked()));
    }

    /// A stored request, any origin.
    public Result<ParkRequest> toDomain() {
        return parts().flatMap(parts -> ParkRequest.parkRequest(parts.kind(),
                                                                question,
                                                                parts.options(),
                                                                parts.recommendation(),
                                                                parts.blocked(),
                                                                evidence.or(List.of()),
                                                                items.or(List.of())));
    }

    /// The parsed parts; every field's failure is collected.
    record Parts(RulingKind kind,
                 List<ParkRequest.Choice> options,
                 ParkRequest.Recommendation recommendation,
                 ParkRequest.Blocked blocked) {}

    private Result<Parts> parts() {
        return Result.all(RulingKind.rulingKind(kind),
                          choices(),
                          Option.option(recommendation)
                                .toResult(missing("recommendation"))
                                .flatMap(RecommendationDto::toDomain),
                          Option.option(blocked).toResult(missing("blocked")).flatMap(BlockedDto::toDomain))
                     .map(Parts::new);
    }

    private Result<List<ParkRequest.Choice>> choices() {
        return Result.allOf(Option.option(options).or(List.of()).stream().map(OptionDto::toDomain).toList());
    }

    private static Cause missing(String field) {
        return invalid(field).apply("missing");
    }
}
