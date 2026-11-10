package org.pragmatica.kiln.core.scan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.scan.ScanError.BadLine;
import org.pragmatica.kiln.core.scan.ScanError.DuplicatePath;
import org.pragmatica.kiln.core.scan.ScanError.General;
import org.pragmatica.kiln.core.scan.ScanError.Miscount;
import org.pragmatica.kiln.core.scan.ScanError.NonGitMismatch;
import org.pragmatica.kiln.core.scan.ScanLine.CheckoutLine;
import org.pragmatica.kiln.core.scan.ScanLine.NonGitLine;
import org.pragmatica.kiln.core.scan.ScanLine.StubLine;
import org.pragmatica.kiln.core.scan.ScanLine.Trailer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// A whole scan's lines, judged by the trailer (spec §5.2). Only [Complete] may delete a row (K-4).
public sealed interface ScanOutcome {
    /// Every line decoded, and the trailer's `count` matched: `rows` are the checkout, stub and non-git lines; `home` is
    /// the trailer's (the scanned host's canonical home, the expansion of a `~` root), none from an older script.
    record Complete(List<ScanLine> rows, int nonGit, Option<String> home) implements ScanOutcome {
        public Complete {
            rows = List.copyOf(rows);
        }
    }

    /// The rows that did decode (the trailer excluded), and why the scan cannot vouch for what it did not list. Decoding
    /// stops at the first line that does not decode: only the rows before it are kept, so the work is bounded by the good
    /// rows, never by the bad lines (an 8 MiB push of blank lines is one failed decode, not 8M); fewer rows upserted,
    /// nothing deleted.
    record Incomplete(List<ScanLine> rows, String reason) implements ScanOutcome {
        public Incomplete {
            rows = List.copyOf(rows);
        }
    }

    /// [Complete] only when the last line is a trailer whose `count` equals the number of checkout and stub lines and
    /// whose `nonGit` equals the number of non-git lines, and no two rows share a path; a missing
    /// trailer, a trailer before the last line, either mismatch, a duplicate path or an undecodable line gives
    /// [Incomplete]. Lines after the first undecodable one are never decoded.
    static ScanOutcome of(List<String> lines) {
        var decoded = decodedToFirstFailure(lines);
        var rows = decoded.stream().flatMap(line -> line.option()
                                                        .stream()).filter(ScanOutcome::isRow).toList();

        return judged(decoded, rows).fold(cause -> new Incomplete(rows, cause.message()),
                                          trailer -> new Complete(rows, trailer.nonGit(), trailer.home()));
    }

    /// The lines decoded in order up to and including the first that fails; none after it is decoded.
    private static List<Result<ScanLine>> decodedToFirstFailure(List<String> lines) {
        var good = IntStream.range(0,
                                   lines.size())
                            .mapToObj(index -> decodeAt(index, lines))
                            .takeWhile(Result::isSuccess)
                            .toList();

        return good.size() == lines.size()
               ? good
               : Stream.concat(good.stream(),
                               Stream.of(decodeAt(good.size(),
                                                  lines)))
                       .toList();
    }

    private static Result<ScanLine> decodeAt(int index, List<String> lines) {
        return ScanLine.decode(lines.get(index)).mapError(cause -> BadLine.FACTORY.apply(index + 1, cause));
    }

    /// The trailer that vouches for the rows: every line decoded, it is the only trailer and the last line, and its
    /// counts are the rows': `count` the checkout and stub lines, `nonGit` the non-git lines (a Complete scan replaces
    /// the non-git list, so a short one must not). A path listed twice fails first: with another path omitted, the
    /// count would still match (K-4).
    private static Result<Trailer> judged(List<Result<ScanLine>> decoded, List<ScanLine> rows) {
        return Result.firstFailureOf(decoded)
                     .flatMap(ScanOutcome::lastTrailer)
                     .flatMap2(ScanOutcome::distinctPaths, rows)
                     .filter(trailer -> Miscount.FACTORY.apply(trailer.count(),
                                                               listed(rows)),
                             trailer -> trailer.count() == listed(rows))
                     .filter(trailer -> NonGitMismatch.FACTORY.apply(trailer.nonGit(),
                                                                     nonGit(rows)),
                             trailer -> trailer.nonGit() == nonGit(rows));
    }

    private static Result<Trailer> lastTrailer(List<ScanLine> lines) {
        return Option.from(lines.reversed().stream().findFirst())
                     .filter(Trailer.class::isInstance)
                     .map(Trailer.class::cast)
                     .toResult(General.NO_TRAILER)
                     .flatMap2(ScanOutcome::onlyTrailer, lines);
    }

    private static Result<Trailer> onlyTrailer(Trailer trailer, List<ScanLine> lines) {
        return lines.stream()
                    .filter(Trailer.class::isInstance)
                    .count() == 1
               ? success(trailer)
               : General.EARLY_TRAILER.result();
    }

    private static Result<Trailer> distinctPaths(Trailer trailer, List<ScanLine> rows) {
        return firstDuplicate(rows).map(DuplicatePath.FACTORY)
                             .map(DuplicatePath::<Trailer> result)
                             .or(() -> success(trailer));
    }

    /// The first path that a later checkout, stub or non-git line lists again.
    private static Option<CheckoutPath> firstDuplicate(List<ScanLine> rows) {
        return Option.from(rows.stream()
                               .flatMap(row -> pathOf(row).stream())
                               .collect(Collectors.groupingBy(Function.identity(),
                                                              LinkedHashMap::new,
                                                              Collectors.counting()))
                               .entrySet()
                               .stream()
                               .filter(entry -> entry.getValue() > 1)
                               .map(Map.Entry::getKey)
                               .findFirst());
    }

    /// The path a row names; the trailer names none.
    private static Option<CheckoutPath> pathOf(ScanLine row) {
        return switch (row) {
            case CheckoutLine checkout -> some(checkout.facts().path());
            case StubLine stub -> some(stub.path());
            case NonGitLine nonGit -> some(nonGit.path());
            case Trailer _ -> none();
        };
    }

    private static boolean isRow(ScanLine line) {
        return ! (line instanceof Trailer);
    }

    private static long nonGit(List<ScanLine> rows) {
        return rows.stream()
                   .filter(NonGitLine.class::isInstance)
                   .count();
    }

    private static long listed(List<ScanLine> rows) {
        return rows.stream()
                   .filter(row -> row instanceof CheckoutLine || row instanceof StubLine)
                   .count();
    }
}
