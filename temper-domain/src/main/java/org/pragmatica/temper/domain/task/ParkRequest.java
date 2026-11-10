package org.pragmatica.temper.domain.task;

import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A NEEDS-RULING request (m3-design §5.5, HR3): the question (at most [#MAX_QUESTION_BYTES] of UTF-8), at least two
/// options with distinct ids, a recommendation naming one of them and why, and what the park blocks. `evidence` and
/// `items` are temper's facts for a round-cap park (§7.6: the last VERIFY and FIX evidence ids and the open items);
/// empty otherwise.
///
/// A request from a worker or the maintainer ([#requested]) may not use a kind temper raises itself, nor mark its
/// recommendation `temper-default`; temper's own ([#raisedByTemper]) always does both.
public record ParkRequest(RulingKind kind,
                          String question,
                          List<Choice> options,
                          Recommendation recommendation,
                          Blocked blocked,
                          List<Long> evidence,
                          List<String> items) {
    public static final int MAX_QUESTION_BYTES = 4096;
    /// An option id, as a checklist item id (`task_items.item`) [G].
    public static final Pattern OPTION_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,40}$");

    public ParkRequest {
        options = List.copyOf(options);
        evidence = List.copyOf(evidence);
        items = List.copyOf(items);
    }

    /// One option: its id (what `rule --option` names) and its text.
    public record Choice(String id, String text) {
        public static Result<Choice> choice(String id, String text) {
            return Result.all(Verify.ensure(id,
                                            Verify.Is::matches,
                                            OPTION_ID,
                                            invalid("option id")),
                              Verify.ensure(text,
                                            Verify.Is::present,
                                            invalid("option text")))
                         .map(Choice::new);
        }
    }

    /// The option the requester recommends and why; `temperDefault` marks temper's own recommendation (§5.5).
    public record Recommendation(String option, String why, boolean temperDefault) {
        public static Result<Recommendation> recommendation(String option, String why, boolean temperDefault) {
            return Verify.ensure(why, Verify.Is::present, invalid("recommendation why")).map(present -> new Recommendation(option,
                                                                                                                           present,
                                                                                                                           temperDefault));
        }
    }

    /// What the park blocks: other tasks by id, and a text.
    public record Blocked(List<TaskId> tasks, String text) {
        public Blocked {
            tasks = List.copyOf(tasks);
        }

        public static Result<Blocked> blocked(List<TaskId> tasks, String text) {
            return Verify.ensure(text, Verify.Is::present, invalid("blocked text")).map(present -> new Blocked(tasks,
                                                                                                               present));
        }
    }

    /// A request as a worker or the maintainer sends it (T10).
    public static Result<ParkRequest> requested(RulingKind kind,
                                                String question,
                                                List<Choice> options,
                                                Recommendation recommendation,
                                                Blocked blocked) {
        return Verify.ensure(kind,
                             present -> !present.isTemperRaised(),
                             invalid("kind"))
                     .flatMap(_ -> Verify.ensure(recommendation,
                                                 present -> !present.temperDefault(),
                                                 invalid("recommendation temperDefault")))
                     .flatMap(_ -> parkRequest(kind,
                                               question,
                                               options,
                                               recommendation,
                                               blocked,
                                               List.of(),
                                               List.of()));
    }

    /// Every rule of §5.5 over a request of any origin, as the store reads one back.
    public static Result<ParkRequest> parkRequest(RulingKind kind,
                                                  String question,
                                                  List<Choice> options,
                                                  Recommendation recommendation,
                                                  Blocked blocked,
                                                  List<Long> evidence,
                                                  List<String> items) {
        return Result.all(questionOf(question),
                          optionsOf(options).flatMap(checked -> recommended(checked, recommendation)))
                     .map((checkedQuestion, checkedOptions) -> new ParkRequest(kind,
                                                                               checkedQuestion,
                                                                               checkedOptions,
                                                                               recommendation,
                                                                               blocked,
                                                                               evidence,
                                                                               items));
    }

    /// Temper's own park (T8 second expiry, T7 third failure, T9′): two options, `retry` (back to the stage as a new
    /// visit) or `abandon`, `retry` recommended. A round-cap park carries the last VERIFY and FIX evidence and the
    /// open items.
    static ParkRequest raisedByTemper(RulingKind kind,
                                      String question,
                                      String retry,
                                      List<Long> evidence,
                                      List<String> items) {
        return new ParkRequest(kind,
                               question,
                               List.of(new Choice("retry", retry), new Choice("abandon", "Abandon the task")),
                               new Recommendation("retry",
                                                  "temper-default: a ruling returns the task as a new visit",
                                                  true),
                               new Blocked(List.of(), "this task until ruled"),
                               evidence,
                               items);
    }

    private static Result<String> questionOf(String question) {
        return Verify.ensure(question, Verify.Is::present, invalid("question")).flatMap(present -> Verify.ensure(present,
                                                                                                                 ParkRequest::isShortEnough,
                                                                                                                 invalid("question")));
    }

    /// Its UTF-8 length, counted from the code points (a lone surrogate counts as the 3 bytes of its replacement).
    private static boolean isShortEnough(String question) {
        return question.codePoints()
                       .map(ParkRequest::utf8Length)
                       .sum() <= MAX_QUESTION_BYTES;
    }

    private static int utf8Length(int codePoint) {
        return codePoint < 0x80
               ? 1
               : multiByteLength(codePoint);
    }

    private static int multiByteLength(int codePoint) {
        return codePoint < 0x800
               ? 2
               : wideLength(codePoint);
    }

    private static int wideLength(int codePoint) {
        return codePoint < 0x10000
               ? 3
               : 4;
    }

    private static Result<List<Choice>> optionsOf(List<Choice> options) {
        return Verify.ensure(options,
                             present -> present.size() >= 2,
                             invalid("options"))
                     .flatMap(present -> Verify.ensure(present,
                                                       ParkRequest::hasDistinctIds,
                                                       invalid("options")));
    }

    private static boolean hasDistinctIds(List<Choice> options) {
        return options.stream()
                      .map(Choice::id)
                      .distinct()
                      .count() == options.size();
    }

    /// The options, once the recommendation names one of them.
    private static Result<List<Choice>> recommended(List<Choice> options, Recommendation recommendation) {
        return Verify.ensure(recommendation,
                             present -> isOffered(options,
                                                  present.option()),
                             invalid("recommendation option"))
                     .map(_ -> options);
    }

    /// Whether `option` is one of the request's option ids (a ruling must name one, T11).
    public boolean isOffered(String option) {
        return isOffered(options, option);
    }

    private static boolean isOffered(List<Choice> options, String option) {
        return options.stream()
                      .anyMatch(choice -> choice.id()
                                                .equals(option));
    }
}
