package org.pragmatica.temper.cli.command;

import java.nio.file.Path;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.config.DigestCursor;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.api.DigestText;
import org.pragmatica.temper.protocol.api.DigestView;

import static org.pragmatica.lang.Option.some;


/// `digest [--since C] [--max N] [--json]` (dashboard design §2): one `GET /digest`, printed as [DigestText] (the same
/// lines the controller answers for `format=text`, ending with a more-waiting line when the controller stopped at
/// `max`) or, with `--json`, as the `DigestView`. It reads once and never loops: `more` is the caller's cue.
///
/// Without `--since` the cursor is [DigestCursor]'s file: read it (none: start at the head), fetch, print, and only
/// then write the new cursor. The order is the guarantee: a failed fetch prints nothing and leaves the file as it was,
/// and so does a print that did not reach stdout, so a digest is never recorded as read unless it was shown (at least
/// once; a failed write after a print shows it again next time). A 409 to the file's cursor (`CURSOR_FOREIGN`) is
/// [CliError.ForeignCursor], exit 2, with the file left as it is. With `--since C` the file is neither read nor
/// written [G]: an explicit cursor is a one-off look, and must not move the running one. Exit 0 on success.
public sealed interface DigestCommand {
    static Promise<Outcome> digest(Controller api, JsonMapper json, CliCommand.Digest command, CliContext context) {
        return command.since()
                      .map(since -> explicit(api, json, command, context, since))
                      .or(() -> tracked(api,
                                        json,
                                        command,
                                        context,
                                        DigestCursor.file(context)));
    }

    private static Promise<Outcome> explicit(Controller api,
                                             JsonMapper json,
                                             CliCommand.Digest command,
                                             CliContext context,
                                             String since) {
        return shown(api, json, command, context, some(since)).map(_ -> done());
    }

    private static Promise<Outcome> tracked(Controller api,
                                            JsonMapper json,
                                            CliCommand.Digest command,
                                            CliContext context,
                                            Path file) {
        return DigestCursor.read(file)
                           .async()
                           .flatMap(since -> shown(api, json, command, context, since))
                           .mapError(cause -> foreign(file, cause))
                           .flatMap(view -> DigestCursor.write(file,
                                                               view.cursor())
                                                        .async())
                           .map(_ -> done());
    }

    /// Fetched and printed; the view is what the cursor is taken from.
    private static Promise<DigestView> shown(Controller api,
                                             JsonMapper json,
                                             CliCommand.Digest command,
                                             CliContext context,
                                             Option<String> since) {
        return api.digest(since,
                          command.max())
                  .flatMap(view -> printed(json, command, context, view).async());
    }

    private static Result<DigestView> printed(JsonMapper json,
                                              CliCommand.Digest command,
                                              CliContext context,
                                              DigestView view) {
        return rendered(json, command, view).flatMap(context.console()::line)
                       .map(_ -> view);
    }

    private static Result<String> rendered(JsonMapper json, CliCommand.Digest command, DigestView view) {
        return command.json()
               ? json.writeAsString(view)
                     .mapError(cause -> CliError.BadPayload.FACTORY.apply("/digest", cause))
               : Result.success(DigestText.render(view));
    }

    /// A 409 to the file's cursor names the file and how to get going again; every other failure is as it is.
    private static Cause foreign(Path file, Cause cause) {
        return cause instanceof CliError.Conflict
               ? CliError.ForeignCursor.FACTORY.apply(file.toString(), cause)
               : cause;
    }

    /// Everything was printed already.
    private static Outcome done() {
        return Outcome.outcome(ExitCode.ACCEPTED);
    }

    record unused() implements DigestCommand {}
}
