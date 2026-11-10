package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.cli.config.TokenFile;
import org.pragmatica.temper.cli.transport.FleetPosition;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.SourceDto;


/// A parsed command line (spec §8.2, the subset temper runs so far). Values are raw where the controller's own domain parse
/// judges them (submit fields go through `SubmitRequest.toDomain()` before sending) and typed where only the
/// CLI uses them (job ids, wait timeout).
public sealed interface CliCommand {
    /// The commands that are one or two plain HTTP exchanges with the controller.
    sealed interface HttpCommand extends CliCommand {}

    /// `submit gate|module-test|mvn …`; `task` is `--task <id>` (m3-design §6.4): the job is submitted under that task's
    /// lease and linked to it.
    record Submit(SourceDto source,
                  RecipeDto recipe,
                  Option<Integer> priority,
                  boolean keep,
                  boolean fresh,
                  Option<String> unitTimeout,
                  Option<String> owner,
                  boolean json,
                  Option<Long> task,
                  boolean dedicatedCpu) implements HttpCommand {}

    /// `submit mutate`, `--bundle`, `--patch`: valid spec §8.2 forms that temper does not run yet.
    record NotInM0(String verb) implements CliCommand {}

    /// `wait <job> [--timeout 6h]`.
    record Wait(long job, Duration timeout, boolean json) implements HttpCommand {}

    /// `result <job> [--json]`.
    record ShowResult(long job, boolean json) implements HttpCommand {}

    /// `watch [--terminal] --subscriber <name>`.
    record Watch(String subscriber, boolean terminal) implements CliCommand {}

    /// `cancel <job>`.
    record Cancel(long job, boolean json) implements HttpCommand {}

    /// `log <job> [--unit U] [--tail N]`.
    record Log(long job, Option<String> unit, Option<Integer> tail) implements HttpCommand {}

    /// `digest [--since C] [--max N] [--json]`: without `--since`, the cursor comes from and goes back to the cursor
    /// file. A cursor is opaque (`<seq>.<token>`, `DigestView.CURSOR`); `max` is the controller's 1..1000.
    record Digest(Option<String> since, Option<Integer> max, boolean json) implements HttpCommand {}

    /// `gate <sha> [--wait] [--timeout 6h] [--json]` (m2-design §1.5): `sha` as the controller parses it (7 to 40 hex);
    /// `waiting` is `--wait`, and `timeout` bounds it only.
    record Gate(ShaPrefix sha, boolean waiting, Duration timeout, boolean json) implements HttpCommand {}

    /// `diff <jobA> <jobB> [--json]` or `diff <job> --base <sha> [--json]` (m2-design §3.4): side B is `job` (the second
    /// job of the first form); side A is the job or the base Gate `against` names.
    record Diff(Against against, long job, boolean json) implements HttpCommand {
        /// Side A: a job, or the Gate `gate <sha>` answers about.
        sealed interface Against {
            record Job(long job) implements Against {}

            record Base(ShaPrefix sha) implements Against {}
        }
    }

    /// `rerun <job> [--prio N] [--fresh] [--unit-timeout PT90M] [--owner O] [--json]` (m2-design §5.5): `priority`,
    /// `unitTimeout` and `owner` as given (the parent's priority, the derived bound and the configured owner apply
    /// without them).
    record Rerun(long job,
                 Option<Integer> priority,
                 boolean fresh,
                 Option<String> unitTimeout,
                 Option<String> owner,
                 boolean json,
                 boolean dedicatedCpu) implements HttpCommand {}

    /// `events …`: the fleet event stream.
    sealed interface EventsCommand extends CliCommand {}

    /// `events --until <kind,…> [--match k=v …] [--after <epoch>:<seq>] [--include-catch-up] [--timeout <dur>]`: the
    /// agent form, ended by the first match or by one of the reasons its exit code names. Without `after` the wait starts
    /// at the heartbeat's head; without `timeout` it is bounded only by the heartbeat clock and the sources.
    record EventsUntil(List<String> kinds,
                       List<EventMatch> match,
                       Option<FleetPosition> after,
                       boolean includeCatchUp,
                       Option<Duration> timeout) implements EventsCommand {}

    /// `events --subscriber <name> --follow [--format text]`: the long-running form; `text` asks for the text lines.
    record EventsFollow(String subscriber, boolean text) implements EventsCommand {}

    /// `events --create <name> [--start head|seq:<n>] [--bind-to caller|read]`: `start` as given, `head` without it;
    /// `bindTo` the token the subscriber is bound to, `caller` without it.
    record EventsCreate(String name, String start, String bindTo) implements EventsCommand {}

    /// `tickets [--host H] [--done N] [--json]`: the bb queue tickets with their owner liveness.
    record Tickets(Option<String> host, Option<Integer> done, boolean json) implements CliCommand {}

    /// `alarm-check`: the maintenance alarm's one stateless check (config `[alarm]`).
    record AlarmCheck() implements CliCommand {}

    /// `refusal list|add|expire` (m1-design §6).
    sealed interface RefusalCommand extends CliCommand {}

    /// `refusal list [--all]`: active refusals, or every one with `--all`.
    record RefusalList(boolean all) implements RefusalCommand {}

    /// `refusal add <code> --matcher <file.json> --reason "…" [--issue N] [--until "…"]`; `matcher` is the file's
    /// path as given.
    record RefusalAdd(String code, String matcher, Option<Integer> issue, String reason, Option<String> until) implements RefusalCommand {}

    /// `refusal expire <code>`.
    record RefusalExpire(String code) implements RefusalCommand {}

    /// `jobs (--sha S | --ref R) [--json]` (WP-C, R-M2-12): every job at that SHA or requested ref, newest first.
    record Jobs(Option<String> sha, Option<String> ref, boolean json) implements CliCommand {}

    /// `subscribe|unsubscribe <job|sha|ref> --subscriber <name>` (m2-design §2.4): the selector as the CLI classified it.
    sealed interface SelectorCommand extends CliCommand {}

    /// `subscribe <job|sha|ref> --subscriber <name>`.
    record Subscribe(String subscriber, Selector selector) implements SelectorCommand {}

    /// `unsubscribe <job|sha|ref> --subscriber <name>`.
    record Unsubscribe(String subscriber, Selector selector) implements SelectorCommand {}

    /// `known list|add|expire` (m2-design §4.4).
    sealed interface KnownCommand extends CliCommand {}

    /// `known list [--all] [--json]`: active entries, or every one with `--all`; `--json` prints the `KnownDto` array
    /// (an unreadable row with its `unreadable` reason, ruling R-M2-15).
    record KnownList(boolean all, boolean json) implements KnownCommand {}

    /// `known add <testId> --issue N --reason "…" --until "<condition>" [--owner O]`; `owner` is `--owner` as given
    /// (the configured owner applies without it).
    record KnownAdd(String testId, int issue, String reason, String until, Option<String> owner) implements KnownCommand {}

    /// `known expire <id>`.
    record KnownExpire(long id) implements KnownCommand {}

    /// The `task` group (m3-design §11.2). Values are raw where the controller's domain parse judges them (stage names,
    /// reasons, the park request) and typed where only the CLI uses them (ids, the checkout directory).
    sealed interface TaskCommand extends CliCommand {
        /// `task create --slug S --title T --repo R --base B --branch BR --brief PATH[@SHA] [--pr N] [--issue N,…]
        /// [--design] [--critical] [--prio N] [--after ID,…] [--round-cap N] [--owner O] [--json]`.
        record Create(String slug,
                      String title,
                      String repo,
                      String base,
                      String branch,
                      String brief,
                      Option<Integer> pr,
                      List<Integer> issues,
                      boolean design,
                      boolean critical,
                      Option<Integer> priority,
                      List<Long> after,
                      Option<Integer> roundCap,
                      Option<String> owner,
                      boolean json) implements TaskCommand {}

        /// `task list [--stage S] [--status ST] [--json]`.
        record ListTasks(Option<String> stage, Option<String> status, boolean json) implements TaskCommand {}

        /// `task show <id> [--json]`.
        record Show(long task, boolean json) implements TaskCommand {}

        /// `task events <id> [--json]`.
        record Events(long task, boolean json) implements TaskCommand {}

        /// `task next [--stage S] [--json]`.
        record Next(Option<String> stage, boolean json) implements TaskCommand {}

        /// `task claim (<id> | --stage S) [--worker NAME] [--checkout DIR] [--json]`.
        record Claim(Option<Long> task,
                     Option<String> stage,
                     Option<String> worker,
                     Option<String> checkout,
                     boolean json) implements TaskCommand {}

        /// `task heartbeat <id>`.
        record Heartbeat(long task) implements TaskCommand {}

        /// `task wait <id> (--job J,… | --ci)`.
        record Wait(long task, List<Long> jobs, boolean ci) implements TaskCommand {}

        /// `task release <id> --reason R`.
        record Release(long task, String reason) implements TaskCommand {}

        /// `task park <id> <request.json> [--lease]`: as the maintainer (the bearer token), or under the task's lease with `--lease`.
        record Park(long task, String request, boolean lease) implements TaskCommand {}

        /// `task rule <id> --option X --text "…" [--know SHA] [--extra-rounds K]`.
        record Rule(long task, String option, String text, Option<String> know, Option<Integer> extraRounds) implements TaskCommand {}

        /// `task close <id> --merged|--abandoned`.
        record Close(long task, boolean merged) implements TaskCommand {}

        /// `task bounce <id> --item "…"`.
        record Bounce(long task, String item) implements TaskCommand {}
    }

    /// The T2 verbs. Values are validated here (repo pattern, positive PR, 40-hex SHA lower-cased,
    /// a UUID go id), so a bad one is 64 without a call.
    sealed interface MergeCommand extends CliCommand {}

    /// A VERDICT verb: its only token is its `--token-file`, which no output names ([TokenFile#toString]).
    sealed interface VerdictScoped extends MergeCommand {
        TokenFile tokenFile();
    }

    /// `merge-gate <repo> <PR> <SHA>`.
    record MergeGate(String repo, long pr, String sha) implements MergeCommand {}

    /// `merge-gate --queue [--repo <r>] [--base <b>]`.
    record MergeQueueView(Option<String> repo, Option<String> base) implements MergeCommand {}

    /// `merge-gate --queue add <repo> <PR> [--base <b>] [--owner <o>] --token-file <p>`.
    record MergeQueueAdd(String repo, long pr, Option<String> base, Option<String> owner, TokenFile tokenFile) implements VerdictScoped {}

    /// `merge-gate --queue remove <repo> <PR> [--base <b>] --token-file <p>`.
    record MergeQueueRemove(String repo, long pr, Option<String> base, TokenFile tokenFile) implements VerdictScoped {}

    /// `merge-gate --queue move <repo> <PR> --to <n> [--base <b>] --token-file <p>`.
    record MergeQueueMove(String repo, long pr, int to, Option<String> base, TokenFile tokenFile) implements VerdictScoped {}

    /// `merge-gate --control-paths list --repo <r>`.
    record ControlPathsList(String repo) implements MergeCommand {}

    /// `merge-gate --control-paths add --repo <r> <glob> --token-file <p>`.
    record ControlPathsAdd(String repo, String glob, TokenFile tokenFile) implements VerdictScoped {}

    /// `merge-gate --control-paths remove --repo <r> <glob> --token-file <p>`.
    record ControlPathsRemove(String repo, String glob, TokenFile tokenFile) implements VerdictScoped {}

    /// `merge <go-id> --token-file <p>`.
    record MergeGo(String go, TokenFile tokenFile) implements VerdictScoped {}

    /// `verdict <repo> <PR> <SHA> ACCEPT|FIX|REJECT --base <b> --by <x> (--report <path> | --carried-from <oldSha>)
    /// --token-file <p>`: exactly one of `report` and `carriedFrom`; `carriedFrom` only with ACCEPT.
    record RegisterVerdict(String repo,
                           long pr,
                           String sha,
                           String verdict,
                           String base,
                           String by,
                           Option<String> report,
                           Option<String> carriedFrom,
                           TokenFile tokenFile) implements VerdictScoped {}

    /// `pr-author <repo> <PR>` (READ): the PR's author set.
    record PrAuthorList(String repo, long pr) implements MergeCommand {}

    /// `pr-author <repo> <PR> add <lane>… --token-file <p>`: append lanes to the PR's author set.
    record PrAuthorAdd(String repo, long pr, java.util.List<String> lanes, TokenFile tokenFile) implements VerdictScoped {}

    /// `review <repo> <PR> <SHA> --as <verifier> [--body-file <p>] --token-file <p>`: the review event is
    /// never an argument, the controller derives it from the verdict registry.
    record Review(String repo, long pr, String sha, String as, Option<String> bodyFile, TokenFile tokenFile) implements VerdictScoped {}
}
