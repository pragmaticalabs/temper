package org.pragmatica.temper.cli.merge;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.command.CliCommand;
import org.pragmatica.temper.domain.exit.ExitCode;


/// `temper merge-gate <repo> <PR> <SHA>`: the [Evaluator]'s reads, then `POST fleet/go` when
/// they found nothing and temper issued a `freshToken`. GO exits 0, NO 1; a refused token 77, another controller
/// build 13 (the NO block is printed either way). A spent budget at any step is `source-unavailable budget-exceeded`
/// and no GO is created.
record MergeGateCommand(Evaluator evaluator, MergeApi api, CliCommand.MergeGate command, Budget budget, String asOf) {
    /// The reason word of a 412 (or a skewed answer) to `POST fleet/go` ("13 version skew on `POST fleet/go`
    /// (412; NO block with `source-unavailable temper 412`)").
    static final String SKEW_ON_WRITE = "412";
    static final String INVALIDATED = "GO_INVALIDATED";

    static Promise<Outcome> gate(CliCommand.MergeGate command,
                                 Evaluator evaluator,
                                 MergeApi api,
                                 Budget budget,
                                 String asOf) {
        var gating = new MergeGateCommand(evaluator, api, command, budget, asOf);

        return evaluator.evaluated(command.repo(),
                                   command.pr(),
                                   command.sha(),
                                   budget)
                        .flatMap(gating::decided);
    }

    private Promise<Outcome> decided(Evaluator.Evaluation evaluation) {
        return budget.isSpent()
               ? no(Reason.joined(evaluation.reasons(),
                                  List.of(Reason.budgetExceeded())),
                    ExitCode.FAILED)
               : evaluation.reasons()
                           .isEmpty()
                 ? goBody(evaluation).map(body -> created(evaluation, body))
                         .or(() -> no(List.of(Evaluator.badPayload()),
                                      ExitCode.FAILED))
                 : no(evaluation.reasons(), evaluation.exit());
    }

    /// Everything a GO needs: the verdict it rests on, the token, kiln's tip, the base. The freshToken HMAC binds kiln's
    /// `baseTip.sha` (lead ruling 2026-11-04), so the body carries that, not the tip G8 fetched; G8 still judged the
    /// fetched tip, and a non-empty reason list never reaches this point.
    private Option<MergeWire.GoBody> goBody(Evaluator.Evaluation evaluation) {
        return evaluation.fresh()
                         .flatMap(this::goBodyOf);
    }

    private Option<MergeWire.GoBody> goBodyOf(MergeWire.PrFresh fresh) {
        return Option.all(fresh.verdict(),
                          fresh.freshToken(),
                          fresh.baseTip().flatMap(MergeWire.BaseTip::sha),
                          fresh.base())
                     .map((verdict, token, baseTip, base) -> new MergeWire.GoBody(command.repo(),
                                                                                  command.pr(),
                                                                                  command.sha(),
                                                                                  base,
                                                                                  baseTip,
                                                                                  verdict.id(),
                                                                                  fresh.fleet().epoch(),
                                                                                  fresh.fleet().seq(),
                                                                                  fresh.checkedAt(),
                                                                                  token));
    }

    private Promise<Outcome> created(Evaluator.Evaluation evaluation, MergeWire.GoBody body) {
        return budget.bound()
                     .async()
                     .flatMap(bound -> api.createGo(body, bound))
                     .fold(result -> answered(result, evaluation, body));
    }

    private Promise<Outcome> answered(Result<MergeWire.GoCreated> result,
                                      Evaluator.Evaluation evaluation,
                                      MergeWire.GoBody body) {
        return result.fold(this::notCreated, go -> goOutcome(evaluation, body, go));
    }

    private Promise<Outcome> goOutcome(Evaluator.Evaluation evaluation, MergeWire.GoBody body, MergeWire.GoCreated go) {
        return evaluation.fresh()
                         .flatMap(MergeWire.PrFresh::verdict)
                         .map(verdict -> Promise.success(Outcome.outcome(MergeLines.go(body, verdict, go, asOf),
                                                                         ExitCode.PASSED)))
                         .or(() -> no(List.of(Evaluator.badPayload()),
                                      ExitCode.FAILED));
    }

    /// A 409 or 422 to the creation is a NO `go-invalidated <reason>` (the creation-time scan); any other
    /// failure is temper's.
    private Promise<Outcome> notCreated(Cause cause) {
        return cause instanceof GateFault.Answered answered && MergeLines.isRefusal(answered.status())
               ? no(List.of(Reason.reason(Reason.Gate.G7, "go-invalidated " + creationReason(answered.error()))),
                    ExitCode.FAILED)
               : no(List.of(Evaluator.temperReason(cause, budget, SKEW_ON_WRITE)),
                    Evaluator.exitOf(cause));
    }

    /// `GO_INVALIDATED checks-changed` → `checks-changed`; another code in its kebab form (`FRESH_TOKEN_EXPIRED` →
    /// `fresh-token-expired`, lead guess 5).
    static String creationReason(String error) {
        var text = error.strip();

        return text.startsWith(INVALIDATED + " ")
               ? text.substring(INVALIDATED.length() + 1)
               : MergeLines.kebab(text);
    }

    private Promise<Outcome> no(List<Reason> reasons, ExitCode exit) {
        return Promise.success(Outcome.outcome(MergeLines.no(reasons, asOf), exit));
    }
}
