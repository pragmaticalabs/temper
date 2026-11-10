package org.pragmatica.temper.cli.merge;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;

import static org.pragmatica.lang.Option.some;


/// A verdict report on the Mac: at most 1 MiB of UTF-8; its sha256 is over the file's
/// bytes as they are on disk (ruling 11-03 item 3: what `shasum -a 256` prints); lines are split on LF after CRLF→LF,
/// so a lone CR stays inside its line (never `String.lines()`, which splits on it). The **last**
/// line starting `VERDICT: ` decides (R2-5).
///
/// The same reading serves `verdict --report` (registration), G6-Mac at gate time and step 2(d) of `temper merge`
/// (lead ruling 11-03), so the three cannot disagree about what was hashed.
sealed interface ReportCheck {
    int LIMIT = 1024 * 1024;
    String PREFIX = "VERDICT: ";
    String CHANGED = "report-changed";
    String MISMATCH = "report-mismatch";

    Pattern LINE = Pattern.compile("^VERDICT: (ACCEPT|FIX|REJECT) ([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)#([1-9][0-9]*)@([0-9a-f]{40}) base=(\\S+)\\s*$");

    /// The report's digest and its deciding line, when it has one.
    record Report(String sha256, Option<String> verdictLine) {}

    /// The fields of a `VERDICT:` line; the PR stays text (the line's `[1-9][0-9]*` is unbounded, so a number too large
    /// for a long is simply another PR, never a parse failure).
    record VerdictLine(String verdict, String repo, String pr, String sha, String base) {}

    static Result<Report> read(Path path) {
        return Result.lift(thrown -> CliError.Unreadable.FACTORY.apply(path.toString(),
                                                                       Causes.fromThrowable(thrown)),
                           () -> Files.size(path))
                     .flatMap(size -> Verify.ensure(size,
                                                    Verify.Is::lessThanOrEqualTo,
                                                    (long) LIMIT,
                                                    CliError.Usage.FACTORY.apply("report larger than 1 MiB: " + path)))
                     .flatMap(_ -> Result.lift(thrown -> CliError.Unreadable.FACTORY.apply(path.toString(),
                                                                                           Causes.fromThrowable(thrown)),
                                               () -> Files.readAllBytes(path)))
                     .flatMap(bytes -> reportOf(path, bytes));
    }

    private static Result<Report> reportOf(Path path, byte[] bytes) {
        return Result.all(sha256(bytes), text(path, bytes)).map((digest, text) -> new Report(digest,
                                                                                             lastVerdictLine(text)));
    }

    static Result<String> sha256(byte[] bytes) {
        return Result.lift(Causes::fromThrowable,
                           () -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private static Result<String> text(Path path, byte[] bytes) {
        return Result.lift(thrown -> CliError.BadSetting.FACTORY.apply("report (UTF-8)",
                                                                       path.toString(),
                                                                       Causes.fromThrowable(thrown)),
                           () -> StandardCharsets.UTF_8.newDecoder()
                                                       .onMalformedInput(CodingErrorAction.REPORT)
                                                       .onUnmappableCharacter(CodingErrorAction.REPORT)
                                                       .decode(ByteBuffer.wrap(bytes))
                                                       .toString())
                     .map(decoded -> decoded.replace("\r\n", "\n"));
    }

    /// The last line starting `VERDICT: `, whatever follows it.
    private static Option<String> lastVerdictLine(String text) {
        return Option.from(Arrays.stream(text.split("\n", -1))
                                 .filter(line -> line.startsWith(PREFIX))
                                 .reduce((_, second) -> second));
    }

    static Option<VerdictLine> parsed(String line) {
        return some(LINE.matcher(line)).filter(Matcher::matches)
                   .map(ReportCheck::fieldsOf);
    }

    private static VerdictLine fieldsOf(Matcher matcher) {
        return new VerdictLine(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), matcher.group(5));
    }

    /// The report's deciding line names exactly `expected`.
    static boolean names(Report report, VerdictLine expected) {
        return report.verdictLine()
                     .flatMap(ReportCheck::parsed)
                     .filter(expected::equals)
                     .isPresent();
    }

    /// G6-Mac, ACCEPT only: unreadable, missing or another sha256 is `report-changed`; a deciding line that is absent,
    /// unparsable or names anything but (verdict, repo, pr, `carriedFrom` if set else sha, base) is `report-mismatch`.
    static List<Reason> judged(MergeWire.VerdictView verdict, String repo, long pr, String sha) {
        var expected = new VerdictLine(verdict.verdict(),
                                       repo,
                                       Long.toString(pr),
                                       verdict.carriedFrom().or(sha),
                                       verdict.base());

        return registered(verdict.reportPath(), verdict.reportSha256()).fold(_ -> List.of(Reason.reason(Reason.Gate.G6,
                                                                                                        CHANGED)),
                                                                             report -> matched(report, expected));
    }

    private static List<Reason> matched(Report report, VerdictLine expected) {
        return names(report, expected)
               ? List.of()
               : List.of(Reason.reason(Reason.Gate.G6, MISMATCH));
    }

    /// Step 2(d) of `temper merge`: the report the GO rests on still hashes to its registered sha256.
    static boolean isUnchanged(String path, String sha256) {
        return registered(path, sha256).isSuccess();
    }

    /// The report at `path` whose sha256 is `sha256`; anything else (an unusable path, an unreadable file, another
    /// digest) is a failure, which both callers read as `report-changed`.
    private static Result<Report> registered(String path, String sha256) {
        return Result.lift(thrown -> CliError.BadSetting.FACTORY.apply("report path",
                                                                       path,
                                                                       Causes.fromThrowable(thrown)),
                           () -> Path.of(path))
                     .flatMap(ReportCheck::read)
                     .filter(GateFault.General.REPORT_CHANGED,
                             report -> report.sha256()
                                             .equals(sha256));
    }

    record unused() implements ReportCheck {}
}
