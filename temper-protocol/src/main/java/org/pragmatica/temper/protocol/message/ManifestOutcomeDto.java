package org.pragmatica.temper.protocol.message;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.manifest.ManifestOutcome;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Wire form of [ManifestOutcome] (protocol v2, `UnitManifest.outcome`, m1-design §2.5):
/// `{"computed": {"entries", "optional", "notReached", "unresolvedAnnotations"}}` or
/// `{"unsupported": {"reason"}}`. `optional` are the classes that may or may not report (neither missing nor
/// drift, [ManifestOutcome.Computed#optional]); without them on the wire every such class that reports would be
/// counted as drift.
///
/// **Chunking.** A computed manifest can hold tens of thousands of entries, so it travels as [#chunks]: at most
/// `Message.MAX_ROWS_PER_CHUNK` entries each; `optional`, `notReached` and `unresolvedAnnotations` on the final
/// chunk only (they are small: optional classes are the rare factory and template ones); an unsupported outcome is
/// one chunk. The receiver folds the chunks in order with [#merge]; folding the
/// chunks of an outcome gives that outcome back. Entries are sorted (module, class, runner) and the
/// other lists by name, so the chunks of a manifest are deterministic.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes({@JsonSubTypes.Type(value = ManifestOutcomeDto.Computed.class, name = "computed"), @JsonSubTypes.Type(value = ManifestOutcomeDto.Unsupported.class, name = "unsupported")})
public sealed interface ManifestOutcomeDto {
    Result<ManifestOutcome> toDomain();
    /// This outcome followed by `next`: the entries and lists of two computed chunks concatenated; an unsupported
    /// outcome on either side wins, because no partial manifest may stand in for a refused one.
    ManifestOutcomeDto merge(ManifestOutcomeDto next);
    /// This outcome as chunks of at most `Message.MAX_ROWS_PER_CHUNK` entries; always at least one.
    List<ManifestOutcomeDto> chunks();

    record Computed(List<ManifestEntryDto> entries,
                    List<ManifestEntryDto> optional,
                    List<String> notReached,
                    List<String> unresolvedAnnotations) implements ManifestOutcomeDto {
        public Computed {
            entries = List.copyOf(option(entries).or(List.of()));
            optional = List.copyOf(option(optional).or(List.of()));
            notReached = List.copyOf(option(notReached).or(List.of()));
            unresolvedAnnotations = List.copyOf(option(unresolvedAnnotations).or(List.of()));
        }

        @Override
        public Result<ManifestOutcome> toDomain() {
            return Result.all(parsedEntries(entries), parsedEntries(optional)).map((required, maybe) -> ManifestOutcome.Computed.computed(required,
                                                                                                                                          maybe,
                                                                                                                                          notReached,
                                                                                                                                          Set.copyOf(unresolvedAnnotations)));
        }

        private static Result<Set<ManifestEntry>> parsedEntries(List<ManifestEntryDto> dtos) {
            return Result.allOf(dtos.stream().map(ManifestEntryDto::toDomain).toList()).map(Set::copyOf);
        }

        @Override
        public ManifestOutcomeDto merge(ManifestOutcomeDto next) {
            return switch (next) {
                case Computed more -> new Computed(concat(entries, more.entries()),
                                                   concat(optional, more.optional()),
                                                   concat(notReached, more.notReached()),
                                                   concat(unresolvedAnnotations, more.unresolvedAnnotations()));
                case Unsupported unsupported -> unsupported;
            };
        }

        @Override
        public List<ManifestOutcomeDto> chunks() {
            var count = Math.max(1, (entries.size() + Message.MAX_ROWS_PER_CHUNK - 1) / Message.MAX_ROWS_PER_CHUNK);

            return IntStream.range(0, count)
                            .<ManifestOutcomeDto> mapToObj(index -> chunk(index, count))
                            .toList();
        }

        private Computed chunk(int index, int count) {
            var from = index * Message.MAX_ROWS_PER_CHUNK;
            var to = Math.min(entries.size(), from + Message.MAX_ROWS_PER_CHUNK);
            var isLast = index == count - 1;

            return new Computed(entries.subList(from, to),
                                onLast(isLast, optional),
                                onLast(isLast, notReached),
                                onLast(isLast, unresolvedAnnotations));
        }

        private static <T> List<T> onLast(boolean isLast, List<T> values) {
            return isLast
                   ? values
                   : List.of();
        }

        private static <T> List<T> concat(List<T> first, List<T> second) {
            return Stream.concat(first.stream(),
                                 second.stream())
                         .toList();
        }
    }

    /// The configuration cannot be judged; `reason` is the text of `ERROR(manifest: <reason>)`.
    record Unsupported(String reason) implements ManifestOutcomeDto {
        /// Wire input: the reason must be present. The worker's typed cause does not travel; its text does, which is
        /// all `ERROR(manifest: <reason>)` renders.
        @Override
        public Result<ManifestOutcome> toDomain() {
            return Verify.ensure(reason, Verify.Is::present, invalid("manifest.reason")).map(present -> ManifestOutcome.Unsupported.unsupported(cause(present)));
        }

        @Override
        public ManifestOutcomeDto merge(ManifestOutcomeDto next) {
            return this;
        }

        @Override
        public List<ManifestOutcomeDto> chunks() {
            return List.of(this);
        }
    }

    Comparator<ManifestEntryDto> ENTRY_ORDER = Comparator.comparing(ManifestEntryDto::module)
                                                         .thenComparing(ManifestEntryDto::className)
                                                         .thenComparing(ManifestEntryDto::runner);

    private static List<ManifestEntryDto> sortedEntries(Set<ManifestEntry> entries) {
        return entries.stream()
                      .map(ManifestEntryDto::manifestEntryDto)
                      .sorted(ENTRY_ORDER)
                      .toList();
    }

    static ManifestOutcomeDto manifestOutcomeDto(ManifestOutcome outcome) {
        return switch (outcome) {
            case ManifestOutcome.Computed computed -> new Computed(sortedEntries(computed.entries()),
                                                                   sortedEntries(computed.optional()),
                                                                   computed.notReached().stream().sorted().toList(),
                                                                   computed.unresolvedAnnotations().stream().sorted().toList());
            case ManifestOutcome.Unsupported unsupported -> new Unsupported(unsupported.reason());
        };
    }
}
