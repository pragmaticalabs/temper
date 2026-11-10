package org.pragmatica.temper.cli.command;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.rerun.RerunOutcome;
import org.pragmatica.temper.protocol.api.CancelResponse;
import org.pragmatica.temper.protocol.api.DiffRefusal;
import org.pragmatica.temper.protocol.api.DiffView;
import org.pragmatica.temper.protocol.api.GateView;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.KnownDto;
import org.pragmatica.temper.protocol.api.LineText;
import org.pragmatica.temper.protocol.api.LostAttemptDto;
import org.pragmatica.temper.protocol.api.RefusalDto;
import org.pragmatica.temper.protocol.api.RefusalMatcherDto;
import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.RerunView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SubmitResponse;
import org.pragmatica.temper.protocol.api.UserPropertyDto;
import org.pragmatica.temper.protocol.api.WsServerMessage.WsGap;


/// The stable one-line outputs (spec §8.1: `<verb> <job> <STATE|VERDICT> key=value …`; m0-design §8 table).
/// A value with whitespace, `"`, `=` or `\` is double-quoted with `\`-escapes; `detail` is always quoted.
///
/// `failed=` counts failed plus error testcases [G]: §5.3 treats both as red, and a FAILED verdict must never
/// print `failed=0` because the reds were JUnit errors.
///
/// `result` and `wait` end with the M1 fields (m1-design §7): `missing=` and `drift=` (the uncapped manifest counts)
/// only when the job has a manifest, and `evidence=stored|missing` always; then, for a rerun job's record, its
/// `rerun-of=` and `rerun(<class>)=…` annotations, and, on `result` and `gate`, the job's reruns as
/// `rerun=<id>:<STATE|VERDICT>,…` (m2-design §5.3, §5.5). `result`, `wait` and a job's state line then name every unit
/// that lost an attempt with its host (m4-design §11): `attempt(main)="2 (lost: cloudbb-3 a1)"`.
public sealed interface Lines {
    /// A value printed bare: no whitespace, quote, `=`, backslash, control or format character, or line/paragraph
    /// separator.
    Pattern BARE = Pattern.compile("^[^\\s\"=\\\\\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+$");
    /// The one-line form of a SHA.
    int SHORT_SHA = 7;

    /// `submit 4711 QUEUED sha=0cf5631 recipe=module-test deduplicated=false`.
    static String submitted(SubmitResponse response, String shortSha, String recipe) {
        return String.format(Locale.ROOT,
                             "submit %d %s sha=%s recipe=%s deduplicated=%s",
                             response.job(),
                             response.state(),
                             value(shortSha),
                             value(recipe),
                             response.deduplicated());
    }

    /// `submit - REFUSED code=R-M2 detail="…" job=4712` (`job` only when the controller persisted one, Q-16).
    static String refused(RefusedResponse response) {
        return refused("submit", response);
    }

    /// `<verb> - REFUSED code=… detail="…" job=…`: `rerun - REFUSED code=rerun detail="…" job=4741` for a rerun.
    static String refused(String verb, RefusedResponse response) {
        return String.format(Locale.ROOT,
                             "%s - REFUSED code=%s detail=%s%s",
                             verb,
                             value(response.refused()),
                             quoted(response.detail()),
                             jobSuffix(response.job()));
    }

    /// `rerun 4740 QUEUED of=4711 classes=org.x.FooTest,org.x.BarTest sha=0cf5631 deduplicated=false` (m2-design §5.5);
    /// `classes` and `sha` are `?` when the rerun job could not be read back.
    static String rerunSubmitted(SubmitResponse response, long parent, String classes, String shortSha) {
        return String.format(Locale.ROOT,
                             "rerun %d %s of=%d classes=%s sha=%s deduplicated=%s",
                             response.job(),
                             response.state(),
                             parent,
                             value(classes),
                             value(shortSha),
                             response.deduplicated());
    }

    /// `submit - USAGE not in M0`.
    static String notInM0(String verb) {
        return verb + " - USAGE not in M0";
    }

    /// `wait 4711 FAILED reason=tests testcases=12 failed=1 observed-failures=1 missing=0 drift=0 evidence=stored`;
    /// a reason with detail reads `reason=manifest detail="effective-pom rc=1"`. The user's `-D` properties end the line
    /// ([#userProperties]).
    static String waited(ResultRecordDto record) {
        return String.format(Locale.ROOT,
                             "wait %d %s%s testcases=%d failed=%d observed-failures=%d%s%s%s%s",
                             record.job(),
                             record.verdict(),
                             reason(record.reason(), record.reasonDetail()),
                             record.counts().testcases(),
                             red(record.counts()),
                             record.observedFailures(),
                             manifestAndEvidence(record),
                             rerunAnnotations(record),
                             recordAttempts(record),
                             userProperties(record.userProperties()));
    }

    /// `result 4711 PASSED testcases=12 failed=0 skipped=0 units=1 observed-failures=0 evidence=stored`; spec §5.3
    /// puts `observed-failures` on every one-line result. The user's `-D` properties end the line ([#userProperties]).
    static String result(ResultRecordDto record, Option<List<RerunView>> reruns) {
        return String.format(Locale.ROOT,
                             "result %d %s%s testcases=%d failed=%d skipped=%d units=%d observed-failures=%d%s%s%s%s%s",
                             record.job(),
                             record.verdict(),
                             reason(record.reason(), record.reasonDetail()),
                             record.counts().testcases(),
                             red(record.counts()),
                             record.counts().skipped(),
                             record.units().size(),
                             record.observedFailures(),
                             manifestAndEvidence(record),
                             rerunAnnotations(record),
                             reruns.map(Lines::reruns).or(" rerun=?"),
                             recordAttempts(record),
                             userProperties(record.userProperties()));
    }

    /// ` tests-skipped="-DskipTests=true" tests-narrowed="-Dtest=FooTest" property="-DfailIfNoSpecifiedTests=false"
    /// tests-narrowed="-pl core"` ([UserPropertyDto#token], each value always quoted): every `-D` property the user
    /// wrote, in argv order, those that skip or narrow the tests marked, then every reactor-narrowing option (m4-design
    /// §5.1, maintainer ruling Q-M4-11c), so a green job with tests skipped or narrowed never reads as tested; nothing for a job
    /// without one.
    static String userProperties(List<UserPropertyDto> properties) {
        return properties.stream()
                         .map(property -> " " + property.token())
                         .collect(Collectors.joining());
    }

    /// `gate <sha7> <ANSWER> …` (m2-design §1.5): the answer's own fields, then the history every answer may carry.
    ///
    /// - PASSED, FAILED, EMPTY: `job=4711 judge=1a2b3c4 testcases=21048 failed=1`, then the record's annotations
    ///   (`KNOWN(#1679)`, `evidence=missing`, `superseded`);
    /// - CONFLICT: `judge=1a2b3c4 jobs=4711:FAILED,4690:PASSED`, then `running=4720` when a newer Gate runs;
    /// - RUNNING: `job=4720 judge=1a2b3c4 state=QUEUED`, then `previous=4711:FAILED` when there is a vote;
    /// - NONE: nothing of its own;
    /// - then, when not empty: `older=4650:PASSED@5d6e7f8` (older judges' votes), `judge-changed=4660:FAILED@1a2b3c4>5d6e7f8`
    ///   (granted under the first judge, finished under the second, ruling R-M2-11) and `unjudged=4702` (VOID, ERROR,
    ///   CANCELLED).
    static String gate(GateView view) {
        return "gate " + shortSha(view.sha())
             + " " + view.answer() + answerFields(view) + reruns(view.reruns()) + history(view);
    }

    /// ` rerun=4740:PASSED,4745:RUNNING` for a job with reruns, nothing otherwise (`rerun=?` on `result` when they could
    /// not be read): information only, never an answer.
    private static String reruns(List<RerunView> reruns) {
        return listed("rerun",
                      reruns.stream().map(rerun -> rerun.job() + ":" + rerun.state()).collect(Collectors.joining(",")));
    }

    /// A rerun record's own annotations (`rerun-of=4711 rerun(org.x.FooTest)=PASSED`), in record order.
    private static String rerunAnnotations(ResultRecordDto record) {
        return annotations(record.annotations().stream().filter(RerunOutcome::isRerunAnnotation).toList());
    }

    /// `temper diff` (m2-design §3.4): the four sections first, each test on its own line as `class#name` (quoted when it
    /// is not bare, so a name never breaks a line), then the counts line (spec §8.2):
    ///
    /// ```
    /// added
    ///   org.x.FooTest#newCase [failed]
    /// removed
    /// newly-failing
    ///   org.x.BarTest#flips
    /// newly-passing
    /// diff 4690 4711 a=PASSED b=FAILED added=1 added-failing=1 removed=0 newly-failing=1 newly-passing=0 skip-changed=0 duplicates=0
    /// ```
    ///
    /// Every `added` test carries B's outcome (`[passed]`, `[failed]`, `[skipped]`), so a new failing test is never
    /// hidden among additions. With `--base`, `base=<job>` follows `b=` and names side A's Gate. Skip changes are counted
    /// here and listed in `--json` only (Q-M2-8).
    static String diff(DiffView view) {
        return section("added", view.added(), true) + section("removed", view.removed(), false) + section("newly-failing",
                                                                                                          view.newlyFailing(),
                                                                                                          false) + section("newly-passing",
                                                                                                                           view.newlyPassing(),
                                                                                                                           false) + diffCounts(view);
    }

    /// A diff that was not computed (m2-design §3.2, §3.4) [G]: `RUNNING` while a side is not terminal (exit 9),
    /// `NONE` otherwise (exit 8), then why. `-` stands for side A when `--base` named none.
    ///
    /// - `diff 4690 4711 NONE not-judged=b:VOID detail="side not judged: VOID"` (R-M2-13; also `a:ERROR,b:REFUSED`);
    /// - `diff 4690 4711 NONE pruned=a` (also `pruned=a,b`);
    /// - `diff 4690 4711 RUNNING not-terminal=b:QUEUED`;
    /// - `diff - 4711 NONE base=none sha=0cf5631`;
    /// - `diff - 4711 NONE base=conflict sha=0cf5631 jobs=4711:FAILED,4690:PASSED`.
    static String diffRefused(DiffRefusal refusal) {
        return "diff " + refusal.a()
                                .map(String::valueOf)
                                .or("-") + " " + refusal.b() + " " + diffObstacle(refusal);
    }

    private static String section(String title, List<DiffView.Entry> entries, boolean withOutcome) {
        return title
             + "\n" + entries.stream()
                             .map(item -> "  " + test(item, withOutcome) + "\n")
                             .collect(Collectors.joining());
    }

    private static String test(DiffView.Entry entry, boolean withOutcome) {
        return value(entry.className() + "#" + entry.name()) + (withOutcome
                                                                ? " [" + entry.outcome()
                                                                              .marker() + "]"
                                                                : "");
    }

    private static String diffCounts(DiffView view) {
        var counts = view.counts();

        return String.format(Locale.ROOT,
                             "diff %d %d a=%s b=%s%s added=%d added-failing=%d removed=%d newly-failing=%d newly-passing=%d skip-changed=%d duplicates=%d",
                             view.a().job(),
                             view.b().job(),
                             view.a().verdict(),
                             view.b().verdict(),
                             numbered("base", view.base()),
                             counts.added(),
                             counts.addedFailing(),
                             counts.removed(),
                             counts.newlyFailing(),
                             counts.newlyPassing(),
                             counts.skipChanged(),
                             counts.duplicates());
    }

    private static String diffObstacle(DiffRefusal refusal) {
        return switch (refusal.reason()) {
            case NOT_JUDGED -> "NONE not-judged=" + sideStates(refusal) + " detail=" + quoted("side not judged: " + verdicts(refusal));
            case PRUNED -> "NONE pruned=" + sides(refusal);
            case NOT_TERMINAL -> "RUNNING not-terminal=" + sideStates(refusal);
            case NO_BASE -> "NONE base=none" + baseSha(refusal);
            case BASE_CONFLICT -> "NONE base=conflict" + baseSha(refusal) + " jobs=" + votes(refusal.conflict(), false);
        };
    }

    private static String sides(DiffRefusal refusal) {
        return refusal.sides()
                      .stream()
                      .map(Lines::sideLabel)
                      .collect(Collectors.joining(","));
    }

    private static String sideStates(DiffRefusal refusal) {
        return refusal.sides()
                      .stream()
                      .map(side -> sideLabel(side) + ":" + side.state())
                      .collect(Collectors.joining(","));
    }

    /// `a` or `b`, as the line names a side.
    private static String sideLabel(DiffRefusal.SideState side) {
        return side.side()
                   .name()
                   .toLowerCase(Locale.ROOT);
    }

    private static String verdicts(DiffRefusal refusal) {
        return refusal.sides()
                      .stream()
                      .map(side -> side.state()
                                       .name())
                      .collect(Collectors.joining(", "));
    }

    private static String baseSha(DiffRefusal refusal) {
        return refusal.sha()
                      .map(sha -> " sha=" + shortSha(sha))
                      .or("");
    }

    /// The first [#SHORT_SHA] characters, or the whole text if the controller sent a shorter one.
    private static String shortSha(String sha) {
        return sha.substring(0,
                             Math.min(SHORT_SHA, sha.length()));
    }

    /// `refusal R-1553 ACTIVE matcher=excludeTestClass simpleClassName=StreamPublishReshuffleTest disabledMarker=#1553
    /// runner=failsafe issue=1553 until="#1553 closed" reason="…"`, or `EXPIRED … expired-at=<instant>`: one line
    /// per refusal for `refusal list`, `add` and `expire`. `reason` is always quoted, as `detail` is.
    static String refusal(RefusalDto refusal) {
        return String.format(Locale.ROOT,
                             "refusal %s %s %s%s%s reason=%s%s",
                             value(refusal.code()),
                             refusalState(refusal),
                             matcher(refusal.matcher()),
                             numbered("issue", refusal.issue()),
                             optional("until", refusal.until()),
                             quoted(refusal.reason()),
                             optional("expired-at",
                                      refusal.expiredAt().map(Instant::toString)));
    }

    /// `known 12 ACTIVE test=org.x.EmberAmnesiacRestartTest issue=1679 until="#1679 closes" reason="…" by=cto`, or
    /// `EXPIRED … expired-at=<instant>` (m2-design §4.4): one line per entry for `known list`, `add` and `expire`.
    /// `reason` is always quoted, as on a refusal line. A row the controller could not read (ruling R-M2-15) is
    /// `UNREADABLE`, its stored values printed as they are, then `unreadable="<why>"` (always quoted) last [G]:
    /// `known 13 UNREADABLE test=org.x.FooTest issue=7 until=u reason=" " by=cto unreadable="Invalid reason: ' '"`.
    static String known(KnownDto known) {
        return String.format(Locale.ROOT,
                             "known %d %s test=%s issue=%d until=%s reason=%s by=%s%s%s",
                             known.id(),
                             knownState(known),
                             value(known.testId()),
                             known.issue(),
                             value(known.until()),
                             quoted(known.reason()),
                             value(known.createdBy()),
                             optional("expired-at",
                                      known.expiredAt().map(Instant::toString)),
                             known.unreadable().map(why -> " unreadable=" + quoted(why)).or(""));
    }

    /// A job that has no result record to show: `result 4711 RUNNING`, `wait 4712 REFUSED reason=R-M2`, then the user's
    /// `-D` properties ([#userProperties]).
    static String state(String verb, JobView view) {
        return verb
             + " " + view.job()
             + " " + view.state() + reason(view.reason()) + viewAttempts(view) + userProperties(view.userProperties());
    }

    /// ` attempt(main)="2 (lost: cloudbb-3 a1)"` for every unit with a lost attempt (m4-design §11): its current attempt,
    /// then each lost one as `<host> a<attempt>`, oldest first; nothing for a unit that never lost one.
    private static String recordAttempts(ResultRecordDto record) {
        return record.units()
                     .stream()
                     .map(unit -> attempt(unit.id(),
                                          unit.attempt().or(0),
                                          unit.lostAttempts()))
                     .collect(Collectors.joining());
    }

    private static String viewAttempts(JobView view) {
        return view.units()
                   .stream()
                   .map(unit -> attempt(unit.name(),
                                        unit.attempt(),
                                        unit.lostAttempts()))
                   .collect(Collectors.joining());
    }

    private static String attempt(String unit, int attempt, List<LostAttemptDto> lost) {
        return lost.isEmpty()
               ? ""
               : " attempt(" + unit
                + ")=" + quoted(attempt
                               + " (lost: " + lost.stream()
                                                  .map(Lines::lostAttempt)
                                                  .collect(Collectors.joining(", "))
                               + ")");
    }

    private static String lostAttempt(LostAttemptDto lost) {
        return lost.host() + " a" + lost.attempt();
    }

    /// `cancel 4711 CANCELLING`, or the terminal state the job already had.
    static String cancelled(long job, CancelResponse response) {
        return "cancel " + job + " " + response.state();
    }

    /// `gap from=12 to=40`: events the controller no longer holds (spec §8.3).
    static String gap(WsGap gap) {
        return "gap from=" + gap.from() + " to=" + gap.to();
    }

    private static String answerFields(GateView view) {
        return switch (view.answer()) {
            case PASSED, FAILED, EMPTY -> verdictFields(view);
            case CONFLICT -> conflictFields(view);
            case RUNNING -> runningFields(view);
            case NONE -> "";
        };
    }

    private static String verdictFields(GateView view) {
        return numbered("job", view.job()) + judgeField(view) + countsFields(view) + annotations(view.annotations());
    }

    private static String conflictFields(GateView view) {
        return judgeField(view) + " jobs=" + votes(view.conflict(), false) + numbered("running", view.running());
    }

    private static String runningFields(GateView view) {
        return numbered("job", view.job()) + judgeField(view) + stateField(view) + previousField(view);
    }

    private static String countsFields(GateView view) {
        return view.counts()
                   .map(counts -> " testcases=" + counts.testcases() + " failed=" + red(counts))
                   .or("");
    }

    private static String stateField(GateView view) {
        return view.state()
                   .map(state -> " state=" + state)
                   .or("");
    }

    private static String previousField(GateView view) {
        return view.previous()
                   .map(previous -> " previous=" + vote(previous, false))
                   .or("");
    }

    private static String history(GateView view) {
        return listed("older", votes(view.older(), true)) + listed("judge-changed", judgeChanged(view.judgeChanged())) + listed("unjudged",
                                                                                                                                jobs(view.unjudged()));
    }

    private static String judgeChanged(List<GateView.JudgeChanged> changed) {
        return changed.stream()
                      .map(Lines::judgeChanged)
                      .collect(Collectors.joining(","));
    }

    private static String jobs(List<Long> jobs) {
        return jobs.stream()
                   .map(String::valueOf)
                   .collect(Collectors.joining(","));
    }

    private static String judgeField(GateView view) {
        return view.judge()
                   .map(judge -> " judge=" + shortJudge(judge))
                   .or("");
    }

    /// `4711:FAILED`, with `@<judge7>` when `withJudge`.
    private static String votes(List<GateView.Vote> votes, boolean withJudge) {
        return votes.stream()
                    .map(vote -> vote(vote, withJudge))
                    .collect(Collectors.joining(","));
    }

    private static String vote(GateView.Vote vote, boolean withJudge) {
        return vote.job() + ":" + vote.verdict() + (withJudge
                                                    ? "@" + shortJudge(vote.judge())
                                                    : "");
    }

    private static String judgeChanged(GateView.JudgeChanged changed) {
        return changed.job()
             + ":" + changed.verdict()
             + "@" + shortJudge(changed.grantedJudge())
             + ">" + shortJudge(changed.judge());
    }

    /// ` key=a,b` for a non-empty list, nothing for an empty one.
    private static String listed(String key, String joined) {
        return joined.isEmpty()
               ? ""
               : " " + key + "=" + joined;
    }

    /// A commit judge as its 7-hex form (`-dirty` kept); `unjudged` and `pre-judge` as they are.
    private static String shortJudge(String judge) {
        return Judge.judge(judge)
                    .map(Judge::shortForm)
                    .or(judge);
    }

    /// Each annotation bare when it is, or when it is `key=value` with both parts bare (`evidence=missing`); quoted
    /// otherwise.
    private static String annotations(List<String> annotations) {
        return annotations.stream()
                          .map(annotation -> " " + annotation(annotation))
                          .collect(Collectors.joining());
    }

    private static String annotation(String annotation) {
        return BARE.matcher(annotation.replaceFirst("=", "")).matches() && !annotation.startsWith("=") && !annotation.endsWith("=")
               ? annotation
               : quoted(annotation);
    }

    private static String knownState(KnownDto known) {
        return known.unreadable()
                    .map(_ -> "UNREADABLE")
                    .or(() -> lifecycle(known));
    }

    private static String lifecycle(KnownDto known) {
        return known.expiredAt()
                    .isPresent()
               ? "EXPIRED"
               : "ACTIVE";
    }

    private static String refusalState(RefusalDto refusal) {
        return refusal.expiredAt()
                      .isPresent()
               ? "EXPIRED"
               : "ACTIVE";
    }

    /// The matcher's kind and its fields under their wire names.
    private static String matcher(RefusalMatcherDto matcher) {
        return switch (matcher) {
            case RefusalMatcherDto.ExcludeTestClass exclude -> String.format(Locale.ROOT,
                                                                             "matcher=excludeTestClass simpleClassName=%s disabledMarker=%s%s",
                                                                             value(exclude.simpleClassName()),
                                                                             value(exclude.disabledMarker()),
                                                                             optional("runner", exclude.runner()));
            case RefusalMatcherDto.ItTestRequired itTest -> "matcher=itTestRequired moduleSegment=" + value(itTest.moduleSegment());
        };
    }

    /// ` key=value` for a present text, nothing for an absent one.
    private static String optional(String key, Option<String> text) {
        return text.map(present -> " " + key + "=" + value(present))
                   .or("");
    }

    /// ` missing=N drift=N` when the record has a manifest, then ` evidence=stored|missing`.
    private static String manifestAndEvidence(ResultRecordDto record) {
        return numbered("missing",
                        record.counts().missing()) + numbered("drift",
                                                              record.counts().drift())
             + " evidence=" + evidence(record.evidence());
    }

    /// ` key=N` for a present number, nothing for an absent one.
    private static String numbered(String key, Option<? extends Number> number) {
        return number.map(present -> " " + key + "=" + present)
                     .or("");
    }

    /// `missing` is the annotation's word for evidence that is not stored (m1-design §3.2).
    private static String evidence(ResultRecordDto.EvidenceDto evidence) {
        return evidence.stored()
               ? "stored"
               : "missing";
    }

    private static String jobSuffix(Option<Long> job) {
        return job.map(id -> " job=" + id)
                  .or("");
    }

    private static int red(ResultRecordDto.CountsDto counts) {
        return counts.failed() + counts.error();
    }

    private static String reason(Option<String> reason) {
        return reason.map(text -> " reason=" + value(text))
                     .or("");
    }

    /// ` reason=<token>`, then ` detail="…"` when the reason carried free text after its token.
    private static String reason(Option<String> reason, Option<String> detail) {
        return reason(reason) + detail.map(text -> " detail=" + quoted(text))
                                      .or("");
    }

    static String value(String text) {
        return BARE.matcher(text).matches()
               ? text
               : quoted(text);
    }

    /// One quoted field on one line ([LineText#quoted]).
    static String quoted(String text) {
        return LineText.quoted(text);
    }

    record unused() implements Lines {}
}
