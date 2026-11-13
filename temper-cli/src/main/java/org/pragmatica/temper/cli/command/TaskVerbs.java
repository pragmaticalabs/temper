package org.pragmatica.temper.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.config.CliConfig;
import org.pragmatica.temper.cli.config.LeaseFiles;
import org.pragmatica.temper.cli.transport.TaskApi;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.task.ReadPointStamp;
import org.pragmatica.temper.protocol.api.CheckpointDto;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.LeaseCall;
import org.pragmatica.temper.protocol.api.ObserveCall;
import org.pragmatica.temper.protocol.api.ParkCall;
import org.pragmatica.temper.protocol.api.ParkRequestDto;
import org.pragmatica.temper.protocol.api.TaskBriefDto;
import org.pragmatica.temper.protocol.api.TaskCreateRequest;
import org.pragmatica.temper.protocol.api.TaskDecision;
import org.pragmatica.temper.protocol.api.TaskEventDto;
import org.pragmatica.temper.protocol.api.TaskView;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The `task` group (m3-design §11.2): one request each, one line per task or event, `--json` for the DTOs. Exit codes:
/// 0 done; 2 refused (`422`); 8 nothing ready (a head claim's `204`, an empty `next`); 9 not ready or not held (`409` to
/// a claim or a maintainer command); 12 lease lost (`409` to a lease verb); 64 usage or an unknown task; 69 unreachable; 5 a
/// `--checkout` clone that failed after the claim (the lease is held; release it).
///
/// `claim` writes the lease token to its lease file ([LeaseFiles]) and never prints it; the lease verbs read it back.
public sealed interface TaskVerbs {
    static Promise<Outcome> task(TaskApi api,
                                 JsonMapper json,
                                 CliCommand.TaskCommand command,
                                 CliConfig config,
                                 CliContext context,
                                 ProcessRunner runner) {
        return switch (command) {
            case CliCommand.TaskCommand.Create create -> created(api, json, create, config);
            case CliCommand.TaskCommand.ListTasks list -> listed(api.list(list.stage(), list.status()),
                                                                 json,
                                                                 list.json(),
                                                                 ExitCode.ACCEPTED);
            case CliCommand.TaskCommand.Show show -> api.show(show.task()).flatMap(view -> shown(view, json, show.json()));
            case CliCommand.TaskCommand.Events events -> api.events(events.task()).flatMap(rows -> eventLines(rows,
                                                                                                              json,
                                                                                                              events.json()));
            case CliCommand.TaskCommand.Next next -> listed(api.next(next.stage()),
                                                            json,
                                                            next.json(),
                                                            ExitCode.NONE);
            case CliCommand.TaskCommand.Claim claim -> claimed(api, json, claim, context, runner);
            case CliCommand.TaskCommand.Heartbeat heartbeat -> heartbeat(api, heartbeat, context);
            case CliCommand.TaskCommand.Hb hb -> beat(api, hb, context, runner);
            case CliCommand.TaskCommand.Checkpoint checkpoint -> checkpointed(api, json, checkpoint, context);
            case CliCommand.TaskCommand.Observe observe -> api.observe(observe.task(),
                                                                       new ObserveCall(observe.ctxK(), observe.turns())).map(_ -> Outcome.outcome(TaskLines.observed(observe.task(),
                                                                                                                                                                     observe.ctxK(),
                                                                                                                                                                     observe.turns()),
                                                                                                                                                  ExitCode.ACCEPTED));
            case CliCommand.TaskCommand.Wait wait -> waited(api, wait, context);
            case CliCommand.TaskCommand.Release release -> released(api, release, context);
            case CliCommand.TaskCommand.Park park -> parked(api, json, park, context);
            case CliCommand.TaskCommand.Rule rule -> api.rule(rule.task(),
                                                              new TaskDecision.Rule(rule.option(),
                                                                                    rule.text(),
                                                                                    rule.know(),
                                                                                    rule.extraRounds())).map(TaskVerbs::viewLine);
            case CliCommand.TaskCommand.Close close -> api.close(close.task(),
                                                                 new TaskDecision.Close(close.merged()
                                                                                        ? "merged"
                                                                                        : "abandoned")).map(TaskVerbs::viewLine);
            case CliCommand.TaskCommand.Bounce bounce -> api.bounce(bounce.task(),
                                                                    new TaskDecision.Bounce(bounce.item())).map(TaskVerbs::viewLine);
        };
    }

    // ---- create and read ---------------------------------------------------------------------------------------------
    /// `--brief PATH[@SHA]`: the SHA after the last `@`, if any; the owner is `createdBy`.
    private static Promise<Outcome> created(TaskApi api,
                                            JsonMapper json,
                                            CliCommand.TaskCommand.Create create,
                                            CliConfig config) {
        return config.ownerOr(create.owner())
                     .toResult(CliError.Usage.FACTORY.apply("task create needs an owner: --owner, TEMPER_OWNER or BB_OWNER"))
                     .async()
                     .map(owner -> createRequest(create, owner))
                     .flatMap(api::create)
                     .flatMap(view -> shown(view,
                                            json,
                                            create.json()));
    }

    private static TaskCreateRequest createRequest(CliCommand.TaskCommand.Create create, String owner) {
        return new TaskCreateRequest(create.slug(),
                                     create.title(),
                                     some(create.issues()),
                                     brief(create.brief(), create.design()),
                                     create.repo(),
                                     create.base(),
                                     create.branch(),
                                     create.pr(),
                                     some(create.critical()),
                                     create.priority(),
                                     some(create.after()),
                                     create.roundCap(),
                                     owner);
    }

    private static TaskBriefDto brief(String raw, boolean design) {
        var at = raw.lastIndexOf('@');

        return at > 0
               ? new TaskBriefDto(raw.substring(0, at), some(raw.substring(at + 1)), some(design), none())
               : new TaskBriefDto(raw, none(), some(design), none());
    }

    private static Promise<Outcome> shown(TaskView view, JsonMapper json, boolean asJson) {
        return Rendered.text(view,
                             TaskLines.task(view),
                             asJson,
                             json)
                       .map(text -> Outcome.outcome(text, ExitCode.ACCEPTED))
                       .async();
    }

    /// One line per task; none at all exits `empty` (8 for `next`: nothing ready; 0 for `list`).
    private static Promise<Outcome> listed(Promise<List<TaskView>> views,
                                           JsonMapper json,
                                           boolean asJson,
                                           ExitCode empty) {
        return views.flatMap(found -> lines(found, json, asJson, empty).async());
    }

    private static Result<Outcome> lines(List<TaskView> views, JsonMapper json, boolean asJson, ExitCode empty) {
        return asJson
               ? Rendered.text(views, "", true, json).map(text -> Outcome.outcome(text, exit(views, empty)))
               : Result.success(textLines(views.stream().map(TaskLines::task).toList(),
                                          exit(views, empty)));
    }

    private static ExitCode exit(List<TaskView> views, ExitCode empty) {
        return views.isEmpty()
               ? empty
               : ExitCode.ACCEPTED;
    }

    private static Promise<Outcome> eventLines(List<TaskEventDto> events, JsonMapper json, boolean asJson) {
        return eventOutput(events, json, asJson).async();
    }

    private static Result<Outcome> eventOutput(List<TaskEventDto> events, JsonMapper json, boolean asJson) {
        return asJson
               ? Rendered.text(events, "", true, json).map(text -> Outcome.outcome(text, ExitCode.ACCEPTED))
               : Result.success(textLines(events.stream().map(TaskLines::event).toList(),
                                          ExitCode.ACCEPTED));
    }

    private static Outcome textLines(List<String> lines, ExitCode exit) {
        return lines.isEmpty()
               ? Outcome.outcome(exit)
               : Outcome.outcome(String.join("\n", lines), exit);
    }

    private static Outcome viewLine(TaskView view) {
        return Outcome.outcome(TaskLines.task(view), ExitCode.ACCEPTED);
    }

    // ---- claim -------------------------------------------------------------------------------------------------------
    /// A `--checkout DIR` is checked first, so a refused directory claims nothing.
    private static Promise<Outcome> claimed(TaskApi api,
                                            JsonMapper json,
                                            CliCommand.TaskCommand.Claim claim,
                                            CliContext context,
                                            ProcessRunner runner) {
        return checkoutTarget(claim).async()
                             .flatMap(target -> claimInto(api, json, claim, context, runner, target));
    }

    private static Promise<Outcome> claimInto(TaskApi api,
                                              JsonMapper json,
                                              CliCommand.TaskCommand.Claim claim,
                                              CliContext context,
                                              ProcessRunner runner,
                                              Option<Path> target) {
        return api.claim(claim.task(),
                         claim.stage(),
                         claim.worker())
                  .flatMap(answer -> answered(json, claim, context, runner, target, answer));
    }

    private static Result<Option<Path>> checkoutTarget(CliCommand.TaskCommand.Claim claim) {
        return claim.checkout()
                    .map(TaskVerbs::emptyTarget)
                    .or(() -> Result.success(none()));
    }

    private static Result<Option<Path>> emptyTarget(String dir) {
        return Checkout.emptyTarget(dir).map(Option::some);
    }

    private static Promise<Outcome> answered(JsonMapper json,
                                             CliCommand.TaskCommand.Claim claim,
                                             CliContext context,
                                             ProcessRunner runner,
                                             Option<Path> target,
                                             Option<ClaimResponse> answer) {
        return answer.map(granted -> granted(json, claim, context, runner, target, granted))
                     .or(() -> Promise.success(Outcome.outcome("task - NONE nothing ready", ExitCode.NONE)));
    }

    /// The lease file first: once it is written the lease is the worker's, whatever the checkout does.
    private static Promise<Outcome> granted(JsonMapper json,
                                            CliCommand.TaskCommand.Claim claim,
                                            CliContext context,
                                            ProcessRunner runner,
                                            Option<Path> target,
                                            ClaimResponse granted) {
        return LeaseFiles.write(context,
                                granted.task(),
                                granted.epoch(),
                                granted.leaseToken())
                         .async()
                         .flatMap(_ -> stampedIf(json, context, runner, target, granted))
                         .flatMap(stamp -> claimOutput(json, claim, granted, target, stamp));
    }

    private static Promise<Option<ReadPointStamp>> stampedIf(JsonMapper json,
                                                             CliContext context,
                                                             ProcessRunner runner,
                                                             Option<Path> target,
                                                             ClaimResponse granted) {
        return target.map(dir -> madeIn(runner, json, dir, granted, context))
                     .or(() -> Promise.success(none()));
    }

    private static Promise<Option<ReadPointStamp>> madeIn(ProcessRunner runner,
                                                          JsonMapper json,
                                                          Path dir,
                                                          ClaimResponse granted,
                                                          CliContext context) {
        return Checkout.made(runner,
                             json,
                             dir,
                             granted,
                             context.clock().instant())
                       .map(Option::some);
    }

    /// The claim line, then the stamp line; with `--json` the claim answer without its token.
    private static Promise<Outcome> claimOutput(JsonMapper json,
                                                CliCommand.TaskCommand.Claim claim,
                                                ClaimResponse granted,
                                                Option<Path> target,
                                                Option<ReadPointStamp> stamp) {
        return Rendered.text(withoutToken(granted),
                             claimText(granted, target, stamp),
                             claim.json(),
                             json)
                       .map(text -> Outcome.outcome(text, ExitCode.ACCEPTED))
                       .async();
    }

    private static String claimText(ClaimResponse granted, Option<Path> target, Option<ReadPointStamp> stamp) {
        return Stream.of(Stream.of(TaskLines.claimed(granted)),
                         TaskLines.claimContext(granted).stream(),
                         Option.all(stamp, target).map(TaskLines::stamp).stream())
                     .flatMap(lines -> lines)
                     .collect(Collectors.joining("\n"));
    }

    /// The token stays in the lease file only (§11.2: never printed).
    private static ClaimResponse withoutToken(ClaimResponse granted) {
        return new ClaimResponse(granted.task(),
                                 granted.slug(),
                                 granted.stage(),
                                 granted.visit(),
                                 granted.epoch(),
                                 "",
                                 granted.readPoint(),
                                 granted.round(),
                                 granted.leaseUntil(),
                                 granted.deadline(),
                                 granted.repo(),
                                 granted.cloneUrl(),
                                 granted.items(),
                                 granted.rulings(),
                                 granted.evidence(),
                                 granted.jobs(),
                                 granted.checkpoint(),
                                 granted.warnings());
    }

    // ---- lease verbs -------------------------------------------------------------------------------------------------
    private static Promise<Outcome> heartbeat(TaskApi api,
                                              CliCommand.TaskCommand.Heartbeat heartbeat,
                                              CliContext context) {
        return LeaseFiles.held(context,
                               heartbeat.task())
                         .async()
                         .flatMap(held -> leaseVerb(api.heartbeat(held.task(),
                                                                  held.epoch(),
                                                                  held.token()),
                                                    held,
                                                    "HEARTBEAT",
                                                    false));
    }

    private static Promise<Outcome> waited(TaskApi api, CliCommand.TaskCommand.Wait wait, CliContext context) {
        return LeaseFiles.held(context,
                               wait.task())
                         .async()
                         .flatMap(held -> leaseVerb(api.waitOn(held.task(),
                                                               held.epoch(),
                                                               wait.jobs(),
                                                               wait.ci(),
                                                               held.token()),
                                                    held,
                                                    "WAITING",
                                                    true));
    }

    private static Promise<Outcome> released(TaskApi api, CliCommand.TaskCommand.Release release, CliContext context) {
        return LeaseFiles.held(context,
                               release.task())
                         .async()
                         .flatMap(held -> leaseVerb(api.release(held.task(),
                                                                held.epoch(),
                                                                release.reason(),
                                                                held.token()),
                                                    held,
                                                    "RELEASED",
                                                    true));
    }

    /// A lease call's answer. A call that ends the lease (`ends`: release, wait, park) deletes its lease file once it is
    /// accepted; a `409 lease-lost` deletes it too, so the file never outlives its lease.
    private static <T> Promise<Outcome> leaseVerb(Promise<T> call, LeaseFiles.Held held, String verb, boolean ends) {
        return leaseLine(call,
                         held,
                         TaskLines.leaseVerb(held.task(), verb, held.epoch()),
                         ends);
    }

    /// As [#leaseVerb], printing `line` once the call is accepted.
    private static <T> Promise<Outcome> leaseLine(Promise<T> call, LeaseFiles.Held held, String line, boolean ends) {
        return call.withFailure(cause -> LeaseFiles.forgetIfLost(held, cause))
                   .flatMap(_ -> leaseAnswered(held,
                                               Outcome.outcome(line, ExitCode.ACCEPTED),
                                               ends));
    }

    // ---- lane registry verbs -----------------------------------------------------------------------------------------
    /// `hb`: the lease first (no lease file is 64), then the tree measured by git, then ONE heartbeat that always carries
    /// the report; the agent states none of its numbers.
    private static Promise<Outcome> beat(TaskApi api,
                                         CliCommand.TaskCommand.Hb hb,
                                         CliContext context,
                                         ProcessRunner runner) {
        return LeaseFiles.held(context,
                               hb.task())
                         .async()
                         .flatMap(held -> TreeProbe.measure(runner,
                                                            hb.tree())
                                                   .flatMap(tree -> leaseLine(api.heartbeat(held.task(),
                                                                                            held.epoch(),
                                                                                            tree,
                                                                                            held.token()),
                                                                              held,
                                                                              TaskLines.heartbeat(held.task(),
                                                                                                  held.epoch(),
                                                                                                  tree),
                                                                              false)));
    }

    /// `checkpoint`: the files are read first (a bad one is 64 and sends nothing), then one POST under the lease; `201` is success.
    private static Promise<Outcome> checkpointed(TaskApi api,
                                                 JsonMapper json,
                                                 CliCommand.TaskCommand.Checkpoint checkpoint,
                                                 CliContext context) {
        return entries(json,
                       checkpoint.checklistFile(),
                       CheckpointDto.Item[].class).async()
                      .flatMap(checklist -> entries(json,
                                                    checkpoint.readMapFile(),
                                                    CheckpointDto.Entry[].class).async()
                                                   .flatMap(readMap -> LeaseFiles.held(context,
                                                                                       checkpoint.task())
                                                                                 .async()
                                                                                 .flatMap(held -> stored(api,
                                                                                                         held,
                                                                                                         checkpoint,
                                                                                                         checklist,
                                                                                                         readMap))));
    }

    private static Promise<Outcome> stored(TaskApi api,
                                           LeaseFiles.Held held,
                                           CliCommand.TaskCommand.Checkpoint checkpoint,
                                           List<CheckpointDto.Item> checklist,
                                           List<CheckpointDto.Entry> readMap) {
        return api.checkpoint(held.task(),
                              new LeaseCall.Checkpoint(held.epoch(),
                                                       checkpoint.pushedSha(),
                                                       checklist,
                                                       readMap,
                                                       checkpoint.note().or("")),
                              held.token())
                  .withFailure(cause -> LeaseFiles.forgetIfLost(held, cause))
                  .map(done -> Outcome.outcome(TaskLines.checkpointed(held.task(),
                                                                      done),
                                               ExitCode.ACCEPTED));
    }

    /// A JSON array file as a list; no file is an empty list.
    private static <T> Result<List<T>> entries(JsonMapper json, Option<String> file, Class<T[]> type) {
        return file.map(name -> Result.lift(thrown -> CliError.Unreadable.FACTORY.apply(name,
                                                                                        Causes.fromThrowable(thrown)),
                                            () -> Files.readString(Path.of(name)))
                                      .flatMap(text -> json.readString(text, type)
                                                           .mapError(cause -> CliError.BadSetting.FACTORY.apply("checkpoint file",
                                                                                                                name,
                                                                                                                cause)))
                                      .map(array -> List.copyOf(Arrays.asList(array))))
                   .or(() -> Result.success(List.of()));
    }

    private static <T> Promise<T> leaseAnswered(LeaseFiles.Held held, T answer, boolean ends) {
        return ends
               ? LeaseFiles.forget(held)
                           .map(_ -> answer)
                           .async()
               : Promise.success(answer);
    }

    /// The maintainer parks with the bearer token; only `--lease` parks under the task's lease (its file required), which the
    /// park then ends. A lease file lying around never decides which credential goes out (review #4).
    private static Promise<Outcome> parked(TaskApi api,
                                           JsonMapper json,
                                           CliCommand.TaskCommand.Park park,
                                           CliContext context) {
        return parkRequest(json,
                           park.request()).async()
                          .flatMap(request -> parkedAs(api, park, request, context));
    }

    private static Promise<Outcome> parkedAs(TaskApi api,
                                             CliCommand.TaskCommand.Park park,
                                             ParkRequestDto request,
                                             CliContext context) {
        return park.lease()
               ? parkedUnderLease(api, park, request, context)
               : api.park(park.task(),
                          new ParkCall(none(),
                                       request),
                          none())
                    .map(TaskVerbs::viewLine);
    }

    private static Promise<Outcome> parkedUnderLease(TaskApi api,
                                                     CliCommand.TaskCommand.Park park,
                                                     ParkRequestDto request,
                                                     CliContext context) {
        return LeaseFiles.held(context,
                               park.task())
                         .async()
                         .flatMap(held -> parkedWith(api, held, request));
    }

    private static Promise<Outcome> parkedWith(TaskApi api, LeaseFiles.Held held, ParkRequestDto request) {
        return api.park(held.task(),
                        new ParkCall(some(held.epoch()),
                                     request),
                        some(held.token()))
                  .withFailure(cause -> LeaseFiles.forgetIfLost(held, cause))
                  .flatMap(view -> leaseAnswered(held,
                                                 viewLine(view),
                                                 true));
    }

    private static Result<ParkRequestDto> parkRequest(JsonMapper json, String file) {
        return Result.lift(thrown -> CliError.Unreadable.FACTORY.apply(file,
                                                                       Causes.fromThrowable(thrown)),
                           () -> Files.readString(Path.of(file)))
                     .flatMap(text -> parkRequestOf(json, file, text));
    }

    private static Result<ParkRequestDto> parkRequestOf(JsonMapper json, String file, String text) {
        return json.readString(text, ParkRequestDto.class)
                   .mapError(cause -> CliError.BadSetting.FACTORY.apply("park request", file, cause));
    }

    record unused() implements TaskVerbs {}
}
