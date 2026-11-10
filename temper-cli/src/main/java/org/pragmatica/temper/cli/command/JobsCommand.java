package org.pragmatica.temper.cli.command;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.api.JobView;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `jobs (--sha S | --ref R) [--json]` (WP-C, R-M2-12): every job at that SHA, or submitted with that ref, newest first,
/// one [#line] each (`job 4711 state=FAILED recipe=gate sha=0cf5631`), or the `JobView`s as one JSON array; exit 0, and
/// none prints nothing. It reads `GET /jobs` page by page (`before`) until a short page, so no job is left out: it is the
/// command a truncated `subscribe` reply names for the rest.
public sealed interface JobsCommand {
    int PAGE = 200;

    static Promise<Outcome> jobs(Controller api, JsonMapper json, CliCommand.Jobs command) {
        return all(api, command.sha(), command.ref()).flatMap(views -> shown(json, command, views));
    }

    /// Every job the filter matches, newest first.
    static Promise<List<JobView>> all(Controller api, Option<String> sha, Option<String> ref) {
        return pages(api, sha, ref, none(), List.of());
    }

    /// `job 4711 state=FAILED recipe=gate sha=0cf5631`: the state as every job view shows it, then the user's `-D`
    /// properties ([Lines#userProperties]).
    static String line(JobView view) {
        return "job " + view.job()
             + " state=" + view.state()
             + " recipe=" + SubmitCommand.recipe(view.recipe())
             + " sha=" + SubmitCommand.shortSha(view) + Lines.userProperties(view.userProperties());
    }

    private static Promise<List<JobView>> pages(Controller api,
                                                Option<String> sha,
                                                Option<String> ref,
                                                Option<Long> before,
                                                List<JobView> found) {
        return api.jobs(sha, ref, PAGE, before)
                  .flatMap(page -> more(api, sha, ref, found, page));
    }

    private static Promise<List<JobView>> more(Controller api,
                                               Option<String> sha,
                                               Option<String> ref,
                                               List<JobView> found,
                                               List<JobView> page) {
        var all = Stream.concat(found.stream(), page.stream()).toList();

        return page.size() < PAGE
               ? Promise.success(all)
               : pages(api,
                       sha,
                       ref,
                       some(page.getLast().job()),
                       all);
    }

    private static Promise<Outcome> shown(JsonMapper json, CliCommand.Jobs command, List<JobView> views) {
        return command.json()
               ? json.writeAsString(views)
                     .mapError(cause -> CliError.BadPayload.FACTORY.apply("/jobs", cause))
                     .map(text -> Outcome.outcome(text, ExitCode.ACCEPTED))
                     .async()
               : Promise.success(listed(views));
    }

    private static Outcome listed(List<JobView> views) {
        return views.isEmpty()
               ? Outcome.outcome(ExitCode.ACCEPTED)
               : Outcome.outcome(String.join("\n",
                                             views.stream().map(JobsCommand::line).toList()),
                                 ExitCode.ACCEPTED);
    }

    record unused() implements JobsCommand {}
}
