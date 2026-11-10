package org.pragmatica.temper.cli.merge;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.command.CliCommand;
import org.pragmatica.temper.domain.exit.ExitCode;


/// `temper pr-author` and `temper review`. A write's `404`/`409`/`422` on
/// `pr-author` is `refused <code>` (2) as the queue edits; `review` has its own mapping ([MergeLines#reviewFailed]).
/// A body file's text is sent, never printed: no line here carries it, a token path or a token.
sealed interface ReviewRuns {
    int BODY_LIMIT = 32 * 1024;

    /// `<repo>#<PR> <lane> added_by=<x> at=<ISO>` per author, the set after the call.
    static Promise<Outcome> addAuthors(CliCommand.PrAuthorAdd command, MergeApi api, String owner) {
        return api.addAuthors(new MergeWire.AuthorsAdd(command.repo(),
                                                       command.pr(),
                                                       command.lanes(),
                                                       owner),
                              Budget.CALL)
                  .fold(result -> authored(result, MergeApi.PR_AUTHORS));
    }

    static Promise<Outcome> authors(CliCommand.PrAuthorList command, MergeApi api) {
        return api.authors(command.repo(),
                           command.pr(),
                           Budget.CALL)
                  .fold(result -> authored(result, MergeApi.PR_AUTHORS));
    }

    private static Promise<Outcome> authored(Result<MergeWire.Authors> result, String path) {
        return result.fold(cause -> MergeLines.refusedOrFailed(cause, path),
                           set -> Promise.success(MergeLines.printed(set.authors()
                                                                        .stream()
                                                                        .map(row -> set.repo()
                                                                                   + "#" + set.pr()
                                                                                   + " " + row.lane()
                                                                                   + " added_by=" + row.addedBy()
                                                                                   + " at=" + row.addedAt())
                                                                        .toList(),
                                                                     ExitCode.ACCEPTED)));
    }

    /// `review <github-id> <repo>#<PR>@<sha12> <EVENT> as=<v> verdict=<id>[ already-posted]`.
    static Promise<Outcome> review(CliCommand.Review command, MergeApi api, Path home) {
        return body(command, home).async()
                   .flatMap(body -> api.review(body, MergeApi.REVIEW_CALL))
                   .fold(result -> result.fold(cause -> MergeLines.reviewFailed(cause, MergeApi.REVIEWS),
                                               reviewed -> Promise.success(Outcome.outcome(line(reviewed),
                                                                                           ExitCode.ACCEPTED))));
    }

    private static String line(MergeWire.Reviewed reviewed) {
        return "review " + reviewed.review()
             + " " + reviewed.repo()
             + "#" + reviewed.pr()
             + "@" + MergeLines.sha12(reviewed.sha())
             + " " + reviewed.event()
             + " as=" + reviewed.as()
             + " verdict=" + reviewed.verdict() + (reviewed.alreadyPosted()
                                                   ? " already-posted"
                                                   : "");
    }

    /// The request body: with `--body-file` its text (UTF-8, at most 32 KiB, CRLF → LF), else none.
    private static Result<Object> body(CliCommand.Review command, Path home) {
        return command.bodyFile()
                      .map(raw -> bodyText(raw, home).<Object> map(text -> new MergeWire.ReviewPostWithBody(command.repo(),
                                                                                                            command.pr(),
                                                                                                            command.sha(),
                                                                                                            command.as(),
                                                                                                            text)))
                      .or(() -> Result.success(new MergeWire.ReviewPost(command.repo(),
                                                                        command.pr(),
                                                                        command.sha(),
                                                                        command.as())));
    }

    private static Result<String> bodyText(String raw, Path home) {
        var path = raw.startsWith("~/")
                   ? home.resolve(raw.substring(2))
                   : Path.of(raw);

        return Result.lift(_ -> CliError.Usage.FACTORY.apply("cannot read --body-file"),
                           () -> Files.readAllBytes(path))
                     .flatMap(ReviewRuns::decoded);
    }

    private static Result<String> decoded(byte[] bytes) {
        return bytes.length > BODY_LIMIT
               ? CliError.Usage.FACTORY.apply("--body-file is over " + BODY_LIMIT + " bytes").result()
               : Result.lift(_ -> CliError.Usage.FACTORY.apply("--body-file is not UTF-8"),
                             () -> StandardCharsets.UTF_8.newDecoder()
                                                         .onMalformedInput(CodingErrorAction.REPORT)
                                                         .onUnmappableCharacter(CodingErrorAction.REPORT)
                                                         .decode(ByteBuffer.wrap(bytes))
                                                         .toString())
                       .map(text -> text.replace("\r\n", "\n"));
    }

    record unused() implements ReviewRuns {}
}
