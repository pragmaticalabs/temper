package org.pragmatica.kiln.service.github;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.feed.CheckFeed;
import org.pragmatica.kiln.core.feed.FreshGate;
import org.pragmatica.kiln.core.github.CheckRun;
import org.pragmatica.kiln.core.github.CommitStatus;
import org.pragmatica.kiln.core.github.Counted;
import org.pragmatica.kiln.core.github.PullRead;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.store.BaseTip;
import org.pragmatica.lang.Option;


/// The JSON of `GET /api/v1/pr-fresh`, as plain records with nullable fields (the API's rule,
/// `Views`): a value kiln does not know is `null`. [#reply] judges G2-G5 and G10 ([FreshGate]) on what one call read.
public sealed interface FreshReply {
    record PullJson(String state, String headSha, String headRef, String headRepo, String base, long changedFiles) {
        static PullJson pullJson(PullRead pull) {
            return new PullJson(pull.state(),
                                pull.head().value(),
                                pull.headRef(),
                                pull.headRepo().or((String) null),
                                pull.base().value(),
                                pull.changedFiles());
        }
    }

    record ContextJson(String context, Long appId) {}

    record RequiredJson(String state, List<ContextJson> contexts, String etag, String detail) {}

    record AgainJson(String state, List<ContextJson> contexts, String etag) {}

    record CheckJson(String context,
                     Long appId,
                     String outcome,
                     String observation,
                     String workflow,
                     Long runId,
                     Long runNumber,
                     Long attempt,
                     String conclusion) {}

    record CountJson(long read, long total) {}

    record CountsJson(CountJson checkRuns, CountJson workflowRuns) {}

    record FilesJson(long read, List<String> paths) {}

    record BaseTipJson(boolean tracked, String sha, String seenAt) {}

    record ReasonJson(String check, String line) {}

    /// The answer without `sha`: R1 only.
    record PullOnlyJson(String asOf, String repo, long pr, PullJson pull, int reads) {}

    record FreshJson(String asOf,
                     String repo,
                     long pr,
                     String sha,
                     PullJson pull,
                     RequiredJson required,
                     List<CheckJson> checks,
                     CountsJson counts,
                     FilesJson files,
                     AgainJson requiredAgain,
                     BaseTipJson baseTip,
                     List<ReasonJson> reasons,
                     int reads) {}

    /// A required list as read, with the ETag of its branch read.
    record ListRead(RequiredChecks list, Option<String> etag) {}

    /// R4-R6 as read: every page of check runs and of workflow runs, and every status.
    record CheckReads(List<Counted<CheckRun>> runs, List<Counted<WorkflowRun>> headRuns, List<CommitStatus> statuses) {}

    /// R7 as read: every file entry, in read order.
    record FileRead(List<Counted.File> files) {}

    /// Everything one call read, in the order it read it, then S1.
    record Gathered(PullRead pull, ListRead first, CheckReads checks, FileRead files, ListRead again, BaseTipJson tip) {}

    static FreshJson reply(Instant asOf, String repo, PrNumber pr, Sha sha, Gathered read, int reads) {
        var counts = FreshGate.counts(read.checks().runs(),
                                      read.checks().headRuns());
        var outcomes = outcomes(read, pr, counts);

        return new FreshJson(asOf.toString(),
                             repo,
                             pr.value(),
                             sha.value(),
                             PullJson.pullJson(read.pull()),
                             requiredJson(read.first()),
                             outcomes.stream().map(FreshReply::checkJson).toList(),
                             new CountsJson(new CountJson(counts.checkRunsRead(), counts.checkRunsTotal()),
                                            new CountJson(counts.workflowRunsRead(), counts.workflowRunsTotal())),
                             filesJson(read.files()),
                             againJson(read.again()),
                             read.tip(),
                             reasons(read, sha, counts, outcomes),
                             reads);
    }

    /// G4 is judged only on a list that requires checks and on a consistent read (inconsistent stops G4).
    private static List<FreshGate.Outcome> outcomes(Gathered read, PrNumber pr, FreshGate.Counts counts) {
        return switch (read.first()
                           .list()) {
            case RequiredChecks.Required list when counts.consistent() -> FreshGate.outcomes(list,
                                                                                             pr,
                                                                                             joined(read.checks().runs()),
                                                                                             read.checks().statuses(),
                                                                                             joined(read.checks().headRuns()));
            default -> List.of();
        };
    }

    private static <T> List<T> joined(List<Counted<T>> pages) {
        return pages.stream()
                    .flatMap(page -> page.items()
                                         .stream())
                    .toList();
    }

    /// G2, G3, G4 (the read's consistency, then the checks), G5, G10: in check order.
    private static List<ReasonJson> reasons(Gathered read,
                                            Sha sha,
                                            FreshGate.Counts counts,
                                            List<FreshGate.Outcome> outcomes) {
        var checks = FreshGate.checks(outcomes);

        return Stream.of(FreshGate.pull(sha,
                                        read.pull()),
                         FreshGate.required(read.first().list()),
                         g4Read(read, counts),
                         checks,
                         FreshGate.again(read.first().list(),
                                         read.first().etag(),
                                         read.again().list(),
                                         read.again().etag()))
                     .flatMap(List::stream)
                     .map(reason -> new ReasonJson(reason.check(),
                                                   reason.line()))
                     .toList();
    }

    private static List<FreshGate.Reason> g4Read(Gathered read, FreshGate.Counts counts) {
        return switch (read.first()
                           .list()) {
            case RequiredChecks.Required _ -> FreshGate.inconsistent(counts);
            default -> List.of();
        };
    }

    private static RequiredJson requiredJson(ListRead read) {
        return new RequiredJson(state(read.list()),
                                contexts(read.list()),
                                read.etag().or((String) null),
                                detail(read.list()));
    }

    private static AgainJson againJson(ListRead read) {
        return new AgainJson(state(read.list()),
                             contexts(read.list()),
                             read.etag().or((String) null));
    }

    private static String state(RequiredChecks list) {
        return switch (list) {
            case RequiredChecks.Required _ -> "REQUIRED";
            case RequiredChecks.NoneRequired _ -> "NONE_REQUIRED";
            case RequiredChecks.Unknown _ -> "UNKNOWN";
        };
    }

    private static String detail(RequiredChecks list) {
        return switch (list) {
            case RequiredChecks.Required _ -> null;
            case RequiredChecks.NoneRequired(var why, var _) -> why;
            case RequiredChecks.Unknown(var status) -> status;
        };
    }

    private static List<ContextJson> contexts(RequiredChecks list) {
        return switch (list) {
            case RequiredChecks.Required required -> required.checks().stream().map(FreshReply::contextJson).toList();
            default -> List.of();
        };
    }

    private static ContextJson contextJson(RequiredCheck check) {
        return new ContextJson(check.context().value(),
                               appId(check));
    }

    private static Long appId(RequiredCheck check) {
        return check.appId()
                    .map(AppId::value)
                    .or((Long) null);
    }

    private static CheckJson checkJson(FreshGate.Outcome outcome) {
        var observation = outcome.observation();
        var run = observation.flatMap(CheckFeed.Observation::run);

        return new CheckJson(outcome.check().context().value(),
                             appId(outcome.check()),
                             outcome.outcome(),
                             observation.map(_ -> FreshGate.observedIn(outcome)).or((String) null),
                             run.map(FreshReply::workflow).or((String) null),
                             run.map(WorkflowRun::id).or((Long) null),
                             run.flatMap(WorkflowRun::runNumber).or((Long) null),
                             run.map(WorkflowRun::attempt).or((Long) null),
                             observation.flatMap(CheckFeed.Observation::conclusion).or((String) null));
    }

    private static String workflow(WorkflowRun run) {
        return run.workflowId()
                  .map(String::valueOf)
                  .or(run.name());
    }

    /// `filename`, and for a rename also `previous_filename`, in read order; `read` counts the entries.
    private static FilesJson filesJson(FileRead read) {
        return new FilesJson(read.files().size(),
                             read.files()
                                 .stream()
                                 .flatMap(file -> Stream.concat(Stream.of(file.filename()),
                                                                file.previous().stream()))
                                 .toList());
    }

    static BaseTipJson tracked(Option<BaseTip> tip) {
        return new BaseTipJson(true,
                               tip.map(found -> found.sha()
                                                     .value()).or((String) null),
                               tip.map(found -> found.seenAt()
                                                     .truncatedTo(ChronoUnit.SECONDS)
                                                     .toString())
                                  .or((String) null));
    }

    static BaseTipJson untracked() {
        return new BaseTipJson(false, null, null);
    }

    record unused() implements FreshReply {}
}
