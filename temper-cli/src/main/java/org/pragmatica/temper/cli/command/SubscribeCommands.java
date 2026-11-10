package org.pragmatica.temper.cli.command;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.SubscriberView;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `subscribe|unsubscribe <job|sha|ref> --subscriber <name>` (m2-design §2.4): one `PUT …/selectors` each, one line,
/// exit 0. An unknown job (404), a bad name or selector (400) and a short SHA (refused before any call) are 64.
///
/// Every line ends with the resulting scope, `selectors=all` when the set is empty, so the first `subscribe` of an
/// all-jobs subscriber visibly narrows it, and removing its last selector visibly widens it back (F4, Q-M2-9 ruled
/// R-M2-9):
/// - `subscribe cto ADDED job:4711 state=RUNNING selectors=job:4711`
/// - `unsubscribe cto REMOVED job:4711 selectors=all`
///
/// `subscribe <job>` reads the job's state AFTER the change commits and prints it, so a job that is already terminal
/// says so (`state=FAILED`): its `finished` is delivered only while the subscriber's cursor is below it (§2.1). If that
/// read fails, the change has committed anyway and the command exits with the read's failure; adding again changes
/// nothing, so a rerun is safe.
/// `subscribe sha:<S>` and `subscribe ref:<R>` list the jobs the selector matches now, newest first, as
/// `temper jobs` does: the [#SHOWN] newest, then `+K more: temper jobs --sha S` when there are more (R-M2-12: a selector
/// added later catches only events after the cursor, so these lines are what it says about jobs that already ran).
/// `subscribe ref:<name>` notes that a job submitted by `--sha` carries no ref and never matches. `ADDED` and `REMOVED`
/// name the operation; whether the selector was already there (or absent) shows only in the scope [G].
public sealed interface SubscribeCommands {
    /// How many matching jobs a SHA or ref `subscribe` lists before its `+K more` line [G]. The count is exact because
    /// every matching job is read ([JobsCommand#all], one `GET /jobs` per 200): a cost accepted for a command a person or
    /// an agent runs by hand.
    int SHOWN = 5;

    static Promise<Outcome> selectors(Controller api, CliCommand.SelectorCommand command) {
        return switch (command) {
            case CliCommand.Subscribe subscribe -> subscribe(api, subscribe);
            case CliCommand.Unsubscribe unsubscribe -> unsubscribe(api, unsubscribe);
        };
    }

    private static Promise<Outcome> subscribe(Controller api, CliCommand.Subscribe command) {
        return api.selectors(command.subscriber(),
                             List.of(command.selector().wire()),
                             List.of())
                  .flatMap(view -> subscribed(api,
                                              command.selector(),
                                              view));
    }

    private static Promise<Outcome> unsubscribe(Controller api, CliCommand.Unsubscribe command) {
        return api.selectors(command.subscriber(),
                             List.of(),
                             List.of(command.selector().wire()))
                  .map(view -> shown(unsubscribed(view,
                                                  command.selector())));
    }

    /// A job selector's line carries the job's state; a SHA or ref selector's lists the jobs it matches now, with
    /// their states (R-M2-12): both read after the change.
    private static Promise<Outcome> subscribed(Controller api, Selector selector, SubscriberView view) {
        return switch (selector) {
            case Selector.JobSelector job -> api.job(job.job().value()).map(shown -> shown(subscribed(view,
                                                                                                      selector,
                                                                                                      " state=" + shown.state())));
            case Selector.ShaSelector sha -> matching(api,
                                                      view,
                                                      selector,
                                                      some(sha.sha().value()),
                                                      none(),
                                                      "--sha " + sha.sha());
            case Selector.RefSelector ref -> matching(api,
                                                      view,
                                                      selector,
                                                      none(),
                                                      some(ref.ref().value()),
                                                      "--ref " + ref.ref());
        };
    }

    private static Promise<Outcome> matching(Controller api,
                                             SubscriberView view,
                                             Selector selector,
                                             Option<String> sha,
                                             Option<String> ref,
                                             String filter) {
        return JobsCommand.all(api, sha, ref).map(jobs -> shown(subscribed(view, selector, " matching=" + jobs.size()) + matchingLines(jobs,
                                                                                                                                       filter)));
    }

    /// The [#SHOWN] newest jobs, one [JobsCommand#line] each, then `+K more: temper jobs --sha S` naming the command that
    /// lists every one, so a truncated reply never hides a job's state.
    static String matchingLines(List<JobView> jobs, String filter) {
        return Stream.concat(jobs.stream().limit(SHOWN).map(JobsCommand::line),
                             more(jobs.size(),
                                  filter))
                     .map(line -> "\n" + line)
                     .collect(Collectors.joining());
    }

    private static Stream<String> more(int total, String filter) {
        return total > SHOWN
               ? Stream.of("+" + (total - SHOWN) + " more: temper jobs " + filter)
               : Stream.empty();
    }

    private static Outcome shown(String line) {
        return Outcome.outcome(line, ExitCode.ACCEPTED);
    }

    /// `subscribe cto ADDED job:4711 state=RUNNING selectors=job:4711`, or `… ADDED sha:… matching=3 selectors=…`; a ref
    /// selector ends with its `note=`.
    static String subscribed(SubscriberView view, Selector selector, String detail) {
        return "subscribe " + Lines.value(view.name())
             + " ADDED " + Lines.value(selector.wire()) + detail
             + " selectors=" + scope(view) + refNote(selector);
    }

    /// `unsubscribe cto REMOVED job:4711 selectors=all`.
    static String unsubscribed(SubscriberView view, Selector selector) {
        return "unsubscribe " + Lines.value(view.name())
             + " REMOVED " + Lines.value(selector.wire())
             + " selectors=" + scope(view);
    }

    /// `all` for the empty set (every job, spec §8.3), otherwise the selectors in the controller's order.
    static String scope(SubscriberView view) {
        return view.selectors()
                   .isEmpty()
               ? "all"
               : Lines.value(String.join(",", view.selectors()));
    }

    private static String refNote(Selector selector) {
        return switch (selector) {
            case Selector.RefSelector ref -> " note=" + Lines.quoted("a job submitted by --sha has no ref and never matches " + ref.wire());
            case Selector.JobSelector _, Selector.ShaSelector _ -> "";
        };
    }

    record unused() implements SubscribeCommands {}
}
